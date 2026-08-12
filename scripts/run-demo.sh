#!/usr/bin/env bash
#
# Runs the demo main class from the built jar.
#
#   ./scripts/run-demo.sh                    # train + predict, no model written
#   ./scripts/run-demo.sh /tmp/model.json    # ...and round-trip the model through disk
#
set -euo pipefail
cd "$(dirname "$0")/.."

JAVA="java"
JAVA25_HOME="$(ls -d /usr/lib/jvm/java-25-openjdk-* 2>/dev/null | head -1 || true)"
[ -n "$JAVA25_HOME" ] && JAVA="$JAVA25_HOME/bin/java"

JARFILE="$(ls target/xgboost-javaclient-linux64-*.jar 2>/dev/null \
    | grep -v -e sources -e javadoc | head -1 || true)"
if [ -z "$JARFILE" ]; then
    echo "No jar found in target/ - run ./scripts/03-build.sh first." >&2
    exit 1
fi

LIBDIR="${XGBOOST_LIBRARY_PATH:-/opt/xgboost/lib}"
PROP=()
if [ -f "$LIBDIR/libxgboost.so" ]; then
    PROP+=("-Dxgboost.library.path=$LIBDIR")
    export LD_LIBRARY_PATH="$LIBDIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi

exec "$JAVA" --enable-native-access=ALL-UNNAMED "${PROP[@]}" -jar "$JARFILE" "$@"
