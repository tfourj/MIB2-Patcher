#!/usr/bin/env bash
# Packages the committed mib2-patcher folder as a zip that goes on the SD card as is:
# mib2-patcher/ and mod/command.sh (the M.I.B. hook for installing without SSH).
# Uses HEAD, so uncommitted changes are left out. Checks every patch jar against its .sha1.
#
# Usage: ./package.sh [name], e.g. ./package.sh MIB2-Patcher-v1.2.1
# Writes build/sdcard (the card layout) and build/<name>.zip, by default MIB2-Patcher-v<patcher version>
set -euo pipefail

cd "$(dirname "$0")"

VERSION=$(git show HEAD:mib2-patcher/patcher.sh | sed -n 's/^VERSION="\(.*\)"$/\1/p' | head -n 1)
[ -n "$VERSION" ] || { echo "error: no VERSION in mib2-patcher/patcher.sh" >&2; exit 1; }
NAME="${1:-MIB2-Patcher-v$VERSION}"
STAGE="build/sdcard"
ZIP="build/$NAME.zip"

rm -rf "$STAGE" "$ZIP"
mkdir -p "$STAGE/mod"
# git archive applies .gitattributes, so the scripts keep their LF line endings
git archive --format=tar HEAD mib2-patcher | tar -x -C "$STAGE"
git show HEAD:mib2-patcher/command.sh > "$STAGE/mod/command.sh"

# The installers refuse a jar that doesn't match its .sha1
for sha in "$STAGE"/mib2-patcher/patches/*/*/*.jar.sha1; do
    jar="${sha%.sha1}"
    if [ "$(sha1sum "$jar" | cut -d ' ' -f 1)" != "$(cat "$sha")" ]; then
        echo "error: ${jar#$STAGE/} does not match its .sha1, rebuild it with build.sh" >&2
        exit 1
    fi
done

# The MMI's sh can't run scripts with CRLF line endings
if grep -rlI $'\r' "$STAGE"; then
    echo "error: the files above have CRLF line endings" >&2
    exit 1
fi

PYTHON="$(command -v python3 || command -v python)"
"$PYTHON" - "$STAGE" "$ZIP" <<'EOF'
import os
import sys
import zipfile

stage, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, dirs, files in os.walk(stage):
        dirs.sort()
        for name in sorted(files):
            path = os.path.join(root, name)
            z.write(path, os.path.relpath(path, stage).replace(os.sep, "/"))
EOF

echo "packaged $ZIP (MIB2-Patcher $VERSION, $(git rev-parse --short HEAD))"
