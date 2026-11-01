#!/usr/bin/env bash
# Builds libcpkhook.so for the MMX (QNX 6.5, 32-bit ARM) with LLVM, without a QNX SDK.
# Needs Homebrew llvm and lld, and the unit's libc.so.3 (from /proc/boot/libc.so.3 on the MMX).
#
# Usage: LIBC=path/to/libc.so.3 ./build.sh
# Writes build/libcpkhook.so next to this script
set -euo pipefail

cd "$(dirname "$0")"

LLVM="${LLVM:-/opt/homebrew/opt/llvm/bin}"
LLD="${LLD:-/opt/homebrew/opt/lld/bin/ld.lld}"
LIBC="${LIBC:?set LIBC to the unit libc.so.3}"

[ -x "$LLVM/clang" ] || { echo "error: $LLVM/clang not found, brew install llvm" >&2; exit 1; }
[ -x "$LLD" ] || { echo "error: $LLD not found, brew install lld" >&2; exit 1; }

mkdir -p build

# Builds build/lib<name>.so from <name>.c
build_lib() {
    local name="$1"

    # ARM mode, no floats in our calls, nothing the QNX loader lacks (unwind tables, stack protector)
    "$LLVM/clang" -target armv7a-none-eabi -marm -mfloat-abi=soft -fPIC -O2 -Wall -Wextra \
        -ffreestanding -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables \
        -c "$name.c" -o "build/$name.o"

    # The QNX loader only reads SYSV hash tables. libc's section table is broken, so lld can't
    # read its soname: link it by file name, so DT_NEEDED is libc.so.3 and not the path on this Mac
    "$LLD" -shared --hash-style=sysv -soname "lib$name.so" -z noexecstack \
        -o "build/lib$name.so" "build/$name.o" -L "$(dirname "$LIBC")" "-l:$(basename "$LIBC")"

    "$LLVM/llvm-readelf" -d "build/lib$name.so" | grep -q "Shared library: \[libc.so.3\]" ||
        { echo "error: DT_NEEDED of lib$name.so is not libc.so.3" >&2; exit 1; }
    echo "built build/lib$name.so"
}

# cpkhook: preloaded into dio_manager. cpkinject: test writer for the cluster's MOST channel
build_lib cpkhook
build_lib cpkinject
