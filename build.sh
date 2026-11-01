#!/usr/bin/env bash
# Builds mib2-patcher/patches/<train>/<patch>/<patch>.jar from source/<train>/<patch>/src
# against the compile-only stubs in source/<train>/<patch>/stubs.
# Needs a JDK 8 (newer JDKs cannot target the old class format the MMI's J9 VM expects).
# Set JAVA_HOME to a JDK 8 if javac on PATH is newer. Set JXE to also check the jar against the firmware.
#
# Usage: ./build.sh <train> <patch>, e.g. ./build.sh MHI2_ER_AU57x_K3663_1 TEL2ACP
set -euo pipefail

cd "$(dirname "$0")"

if [ $# -ne 2 ]; then
    echo "usage: $0 <train> <patch>" >&2
    echo "available:" >&2
    for d in source/*/*/; do
        d="${d%/}"
        echo "  ${d#source/}" | tr '/' ' ' >&2
    done
    exit 1
fi

TRAIN="$1"
PATCH="$2"
SRC="source/$TRAIN/$PATCH"
OUT="mib2-patcher/patches/$TRAIN/$PATCH"
BUILD="build/$TRAIN/$PATCH"

[ -d "$SRC/src" ] || { echo "error: $SRC/src not found" >&2; exit 1; }
[ -d "$OUT" ] || { echo "error: $OUT not found, add the patch folder first" >&2; exit 1; }

BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
JAVAC="${BIN}javac"
JAR="${BIN}jar"

if ! "$JAVAC" -version 2>&1 | grep -q '1\.8\.'; then
    echo "error: JDK 8 javac required, got: $("$JAVAC" -version 2>&1)" >&2
    echo "set JAVA_HOME to a JDK 8 install" >&2
    exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/stubs" "$BUILD/classes"

# Stubs are only used to resolve firmware types and never go into the jar.
# A patch that only replaces a self-contained class (NAVIGNORE) has none
STUBS=$(find "$SRC/stubs" -name '*.java' 2> /dev/null || true)
if [ -n "$STUBS" ]; then
    "$JAVAC" -nowarn -source 1.3 -target 1.2 -d "$BUILD/stubs" $STUBS
fi
"$JAVAC" -nowarn -source 1.3 -target 1.2 -cp "$BUILD/stubs" -d "$BUILD/classes" $(find "$SRC/src" -name '*.java')

"$JAR" cf "$OUT/$PATCH.jar" -C "$BUILD/classes" .

# The installer compares this against the jar on the SD card and on the unit
sha1sum "$OUT/$PATCH.jar" | cut -d ' ' -f 1 > "$OUT/$PATCH.jar.sha1"
echo "built $OUT/$PATCH.jar ($(cat "$OUT/$PATCH.jar.sha1"))"

# Optional: check the jar against the firmware (JXE=/path/to/lsd.jxe ./build.sh <train> <patch>)
if [ -n "${JXE:-}" ]; then
    PYTHON="$(command -v python3 || command -v python)"
    "$PYTHON" tools/jxe/checkrefs.py "$JXE" "$OUT/$PATCH.jar"
fi
