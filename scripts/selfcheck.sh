#!/usr/bin/env bash
#
# Compiles and runs the dependency-free smoke test (scripts/selfcheck/SelfCheck.java).
# Needs only a JDK 25 and libxgboost.so - no Maven, no network, no JUnit.
#
#   ./scripts/selfcheck.sh                            # 36 checks
#   ./scripts/selfcheck.sh /tmp/java_predictions.txt  # ...and dump predictions
#                                                     #    for scripts/reference-run.py
#
set -euo pipefail
cd "$(dirname "$0")/.."

JAVAC="javac"; JAVA="java"
JAVA25_HOME="$(ls -d /usr/lib/jvm/java-25-openjdk-* 2>/dev/null | head -1 || true)"
if [ -n "$JAVA25_HOME" ]; then
    JAVAC="$JAVA25_HOME/bin/javac"; JAVA="$JAVA25_HOME/bin/java"
fi

if [ ! -d src/generated/java/com ]; then
    echo "Bindings not present - running ./scripts/02-generate-bindings.sh first"
    ./scripts/02-generate-bindings.sh
fi

OUT="target/selfcheck-classes"
rm -rf "$OUT" && mkdir -p "$OUT"

find src/main/java src/generated/java -name '*.java' > target/selfcheck-sources.txt
"$JAVAC" --release 25 -d "$OUT" @target/selfcheck-sources.txt
"$JAVAC" --release 25 -cp "$OUT" -d "$OUT" scripts/selfcheck/SelfCheck.java

LIBDIR="${XGBOOST_LIBRARY_PATH:-/opt/xgboost/lib}"
PROP=()
if [ -f "$LIBDIR/libxgboost.so" ]; then
    PROP+=("-Dxgboost.library.path=$LIBDIR")
    export LD_LIBRARY_PATH="$LIBDIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

exec "$JAVA" -cp "$OUT" --enable-native-access=ALL-UNNAMED "${PROP[@]}" SelfCheck "$@"
