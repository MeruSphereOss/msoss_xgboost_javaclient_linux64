#!/usr/bin/env bash
#
# Installs everything needed to build and release this library on Ubuntu Server
# x64 (tested on Ubuntu 24.04):
#   - OpenJDK 25, Maven, git, libclang
#   - libxgboost.so under /opt/xgboost/lib, registered with ldconfig
#   - jextract, built once from the pinned OpenJDK source (no binary download
#     needed; download.java.net is not required)
#
# jextract is only needed to REGENERATE the bindings. src/generated/java is
# committed, so a plain build does not need it - but a release box should have
# it so the generated sources can be reproduced from the vendored header.
#
# Usage:  ./scripts/01-install-prereqs-ubuntu.sh
#
set -euo pipefail

SUDO="sudo"
[ "$(id -u)" = "0" ] && SUDO=""

JEXTRACT_REPO="https://github.com/openjdk/jextract.git"
# Commit of the jextract master branch this project was generated/tested with.
JEXTRACT_COMMIT="9f9f184e27ca94d27158dccf903d9e6b44c19d68"
JEXTRACT_HOME="/opt/jextract"

XGBOOST_VERSION="${XGBOOST_VERSION:-3.2.0}"
XGBOOST_HOME="/opt/xgboost"

echo "==> Installing OS packages (JDK 25, maven, git, libclang, python3-pip, unzip)"
$SUDO apt-get update
$SUDO apt-get install -y openjdk-25-jdk-headless maven git libclang-dev \
    python3-pip unzip curl

JAVA25_HOME="$(ls -d /usr/lib/jvm/java-25-openjdk-* 2>/dev/null | head -1 || true)"
if [ -z "$JAVA25_HOME" ]; then
    echo "ERROR: OpenJDK 25 not found under /usr/lib/jvm." >&2
    echo "Install a JDK 25 (e.g. Temurin from https://adoptium.net) and re-run." >&2
    exit 1
fi
echo "==> JDK 25: $JAVA25_HOME"
"$JAVA25_HOME/bin/java" --version

# ------------------------------------------------------------- libxgboost.so
#
# XGBoost is not packaged in the Ubuntu archive. The official Python wheel is
# the fastest supported source of a prebuilt libxgboost.so - it is the exact
# binary the reference binding runs on, which is what makes the cross-check in
# scripts/reference-run.py meaningful. Building from source with cmake is the
# alternative if you need a custom (e.g. CUDA) build.
#
if [ -f "$XGBOOST_HOME/lib/libxgboost.so" ]; then
    echo "==> libxgboost.so already installed at $XGBOOST_HOME/lib"
else
    echo "==> Fetching libxgboost.so $XGBOOST_VERSION from the official wheel"
    TMP="$(mktemp -d)"
    ( cd "$TMP" && pip download "xgboost==$XGBOOST_VERSION" --no-deps -d . -q \
        && unzip -qo xgboost-*.whl )
    $SUDO mkdir -p "$XGBOOST_HOME/lib"
    $SUDO cp "$TMP"/xgboost/lib/libxgboost.so "$XGBOOST_HOME/lib/"
    # The wheel vendors its own patched libgomp; libxgboost.so links against it
    # by that exact soname, so it has to travel with it.
    if compgen -G "$TMP/xgboost.libs/*" > /dev/null; then
        $SUDO cp "$TMP"/xgboost.libs/* "$XGBOOST_HOME/lib/"
    fi
    rm -rf "$TMP"

    echo "$XGBOOST_HOME/lib" | $SUDO tee /etc/ld.so.conf.d/xgboost.conf >/dev/null
    $SUDO ldconfig
fi

echo "==> Checking libxgboost"
ls -l "$XGBOOST_HOME/lib/"
ldconfig -p | grep -i xgboost || echo "    (not in the ldconfig cache - export LD_LIBRARY_PATH=$XGBOOST_HOME/lib)"

# ------------------------------------------------------------------ jextract
if [ -x "$JEXTRACT_HOME/bin/jextract" ]; then
    echo "==> jextract already installed:"
    "$JEXTRACT_HOME/bin/jextract" --version
else
    echo "==> Building jextract from source (pinned commit ${JEXTRACT_COMMIT:0:10})"
    $SUDO mkdir -p "$JEXTRACT_HOME"
    $SUDO chown "$(id -u):$(id -g)" "$JEXTRACT_HOME"
    rm -rf "$JEXTRACT_HOME/src" "$JEXTRACT_HOME/classes"
    git clone "$JEXTRACT_REPO" "$JEXTRACT_HOME/src"
    git -C "$JEXTRACT_HOME/src" checkout --quiet "$JEXTRACT_COMMIT" \
        || echo "    (pinned commit not found; using current master)"

    mkdir -p "$JEXTRACT_HOME/classes"
    find "$JEXTRACT_HOME/src/src/main/java" -name '*.java' > /tmp/jextract-sources.txt
    "$JAVA25_HOME/bin/javac" --module-version 25.0.0 \
        -d "$JEXTRACT_HOME/classes" @/tmp/jextract-sources.txt
    cp -r "$JEXTRACT_HOME/src/src/main/resources/." "$JEXTRACT_HOME/classes/"

    LLVM_LIB="$(ls -d /usr/lib/llvm-*/lib 2>/dev/null | sort -V | tail -1 || true)"
    mkdir -p "$JEXTRACT_HOME/bin"
    cat > "$JEXTRACT_HOME/bin/jextract" <<EOF
#!/usr/bin/env bash
exec "$JAVA25_HOME/bin/java" \\
  --enable-native-access=org.openjdk.jextract \\
  -Djava.library.path="$LLVM_LIB" \\
  -p "$JEXTRACT_HOME/classes" \\
  -m org.openjdk.jextract/org.openjdk.jextract.JextractTool "\$@"
EOF
    chmod +x "$JEXTRACT_HOME/bin/jextract"
    echo "==> jextract installed:"
    "$JEXTRACT_HOME/bin/jextract" --version
fi

echo
echo "All prerequisites ready."
echo "  build            : ./scripts/03-build.sh"
echo "  regenerate binds : ./scripts/02-generate-bindings.sh   (only after a header bump)"
echo "  smoke test       : ./scripts/run-demo.sh"
echo
echo "  export XGBOOST_LIBRARY_PATH=$XGBOOST_HOME/lib   # if not using ldconfig"
