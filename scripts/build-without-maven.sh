#!/usr/bin/env bash
#
# Fallback build that needs only JDK 25 (no Maven, no network):
# compiles src/main/java + src/generated/java with javac and packages the jar.
# Skips the JUnit tests (those need Maven to fetch JUnit) - run
# ./scripts/selfcheck.sh instead, which covers the same ground with no
# dependencies at all.
#
# The library has no compile dependencies, so unlike most projects this really
# does work offline from a bare JDK.
#
# Usage:  ./scripts/build-without-maven.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

JAVAC="javac"; JAR="jar"
JAVA25_HOME="$(ls -d /usr/lib/jvm/java-25-openjdk-* 2>/dev/null | head -1 || true)"
if [ -n "$JAVA25_HOME" ]; then
    JAVAC="$JAVA25_HOME/bin/javac"; JAR="$JAVA25_HOME/bin/jar"
fi

if [ ! -d src/generated/java/com ]; then
    echo "Bindings not present - running ./scripts/02-generate-bindings.sh first"
    ./scripts/02-generate-bindings.sh
fi

# Keep in step with <version> in pom.xml.
VERSION="1.0.0"
OUT="target/classes"
rm -rf "$OUT" && mkdir -p "$OUT" target

find src/main/java src/generated/java -name '*.java' > target/sources.txt
echo "==> compiling $(wc -l < target/sources.txt | tr -d ' ') sources with --release 25"
"$JAVAC" --release 25 -d "$OUT" @target/sources.txt

cat > target/MANIFEST.MF <<'EOF'
Main-Class: com.merusphere.devops.xgboost.javaclient.linux64.demo.XgboostDemo
Enable-Native-Access: ALL-UNNAMED
EOF

"$JAR" --create --file "target/xgboost-javaclient-linux64-$VERSION.jar" \
    --manifest target/MANIFEST.MF -C "$OUT" .

echo "==> Built:"
ls -l "target/xgboost-javaclient-linux64-$VERSION.jar"
echo
echo "Run it:  ./scripts/run-demo.sh"
