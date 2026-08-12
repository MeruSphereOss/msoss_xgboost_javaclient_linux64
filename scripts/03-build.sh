#!/usr/bin/env bash
#
# Builds the library jar with Maven on Java 25 and runs the tests.
# The tests need libxgboost.so, but nothing else - no cluster, no GPU.
#
# Usage:
#   ./scripts/03-build.sh
#   XGBOOST_LIBRARY_PATH=/opt/xgboost/lib ./scripts/03-build.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA25_HOME="$(ls -d /usr/lib/jvm/java-25-openjdk-* 2>/dev/null | head -1 || true)"
if [ -n "$JAVA25_HOME" ]; then
    export JAVA_HOME="$JAVA25_HOME"
fi

if [ ! -d src/generated/java/com ]; then
    echo "Bindings not present - running ./scripts/02-generate-bindings.sh first"
    ./scripts/02-generate-bindings.sh
fi

# Default to the location scripts/01-install-prereqs-ubuntu.sh uses.
LIBDIR="${XGBOOST_LIBRARY_PATH:-/opt/xgboost/lib}"
ARGS=()
if [ -f "$LIBDIR/libxgboost.so" ]; then
    echo "==> libxgboost.so: $LIBDIR"
    ARGS+=("-Dxgboost.library.path=$LIBDIR")
    export LD_LIBRARY_PATH="$LIBDIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
else
    echo "==> libxgboost.so not at $LIBDIR - relying on ldconfig / LD_LIBRARY_PATH"
fi

mvn -B clean package "${ARGS[@]}"

echo
echo "==> Built:"
ls -l target/xgboost-javaclient-linux64-*.jar
echo
echo "Demo:              ./scripts/run-demo.sh"
echo "Cross-check:       python3 scripts/reference-run.py <predictions file>"
