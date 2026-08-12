#!/usr/bin/env bash
#
# Regenerates the Java FFM bindings for XGBoost into src/generated/java
# (package com.merusphere.devops.xgboost.javaclient.linux64.capi).
#
# The generated sources ARE committed, so a normal build never needs jextract.
# Run this only when the vendored header in native/include/xgboost/c_api.h is
# refreshed to a newer XGBoost release.
#
# Generation is FILTERED to the train/predict surface. c_api.h declares 102
# entry points; extracting all of them drags in the distributed tracker, the
# CUDA columnar ingest paths and the external-memory iterator callbacks -
# several hundred classes this client never calls. The filter below is the
# single place to widen the surface: add one --include-function line.
#
# Usage:  ./scripts/02-generate-bindings.sh
#
set -euo pipefail
cd "$(dirname "$0")/.."

# ------------------------------------------------------------------ jextract
if command -v jextract >/dev/null 2>&1; then
    JEXTRACT="jextract"
elif [ -x /opt/jextract/bin/jextract ]; then
    JEXTRACT="/opt/jextract/bin/jextract"
elif [ -n "${JEXTRACT_HOME:-}" ] && [ -x "$JEXTRACT_HOME/bin/jextract" ]; then
    JEXTRACT="$JEXTRACT_HOME/bin/jextract"
else
    echo "ERROR: jextract not found. Run ./scripts/01-install-prereqs-ubuntu.sh first." >&2
    exit 1
fi

HEADER="native/include/xgboost/c_api.h"
[ -f "$HEADER" ] || { echo "ERROR: $HEADER missing." >&2; exit 1; }

OUT="src/generated/java"
PKG="com.merusphere.devops.xgboost.javaclient.linux64.capi"

# Kept in step with <xgboost.header.version> in pom.xml.
HEADER_VERSION="3.2.0"

echo "==> jextract: $($JEXTRACT --version 2>/dev/null | grep -m1 '^jextract' || echo unknown)"
echo "==> header  : $HEADER (vendored from XGBoost $HEADER_VERSION)"
echo "==> package : $PKG"

# ---------------------------------------------------------------- the filter
FILTER=(
  --include-typedef bst_ulong
  --include-typedef DMatrixHandle
  --include-typedef BoosterHandle

  --include-function XGBoostVersion
  --include-function XGBGetLastError
  --include-function XGBSetGlobalConfig
  --include-function XGBGetGlobalConfig
  --include-function XGBuildInfo

  --include-function XGDMatrixCreateFromDense
  --include-function XGDMatrixCreateFromCSR
  --include-function XGDMatrixCreateFromURI
  --include-function XGDMatrixFree
  --include-function XGDMatrixNumRow
  --include-function XGDMatrixNumCol
  --include-function XGDMatrixNumNonMissing
  --include-function XGDMatrixSetFloatInfo
  --include-function XGDMatrixGetFloatInfo
  --include-function XGDMatrixSetInfoFromInterface
  --include-function XGDMatrixSetStrFeatureInfo
  --include-function XGDMatrixGetStrFeatureInfo
  --include-function XGDMatrixSaveBinary

  --include-function XGBoosterCreate
  --include-function XGBoosterFree
  --include-function XGBoosterReset
  --include-function XGBoosterSetParam
  --include-function XGBoosterGetNumFeature
  --include-function XGBoosterBoostedRounds
  --include-function XGBoosterUpdateOneIter
  --include-function XGBoosterEvalOneIter
  --include-function XGBoosterPredictFromDMatrix
  --include-function XGBoosterSaveModel
  --include-function XGBoosterLoadModel
  --include-function XGBoosterSaveModelToBuffer
  --include-function XGBoosterLoadModelFromBuffer
  --include-function XGBoosterSaveJsonConfig
  --include-function XGBoosterLoadJsonConfig
  --include-function XGBoosterSetAttr
  --include-function XGBoosterGetAttr
  --include-function XGBoosterGetAttrNames
  --include-function XGBoosterDumpModelEx
  --include-function XGBoosterFeatureScore
)

run_jextract() {
    "$JEXTRACT" \
        --output "$OUT" \
        -t "$PKG" \
        --header-class-name XgboostH \
        -I native/include \
        "$@" \
        "${FILTER[@]}" \
        "$HEADER"
}

rm -rf "$OUT/com"
mkdir -p "$OUT"

# c_api.h needs uint64_t from <stdint.h>. A jextract built against a distro
# libclang (rather than a self-contained LLVM release) cannot resolve the
# system stdint.h behind clang's builtin shim, and fails with
#   c_api.h:28:9: error: unknown type name 'uint64_t'
# Forcing the freestanding branch makes clang's own stdint.h define the
# fixed-width types itself. Harmless: c_api.h uses nothing else from libc.
ERRLOG="$(mktemp)"
trap 'rm -f "$ERRLOG"' EXIT
if ! run_jextract 2>"$ERRLOG"; then
    if grep -q "unknown type name 'uint64_t'" "$ERRLOG"; then
        echo "==> retrying with -D__STDC_HOSTED__=0 (libclang cannot see the system stdint.h)"
        rm -rf "$OUT/com"; mkdir -p "$OUT"
        run_jextract -D __STDC_HOSTED__=0
    else
        cat "$ERRLOG" >&2
        exit 1
    fi
fi

COUNT=$(find "$OUT" -name '*.java' | wc -l | tr -d ' ')
echo "==> generated $COUNT Java files in $OUT (package $PKG)"
echo "    ('warning: redefining builtin macro' from the retry path is expected)"
