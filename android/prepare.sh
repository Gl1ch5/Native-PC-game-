#!/usr/bin/env bash
# Assembles a buildable Android project for one game pack:
#   upstream Winlator Cmod + patches + launcher overlay + games/<id> config and branding.
#
# Usage: android/prepare.sh <game-id> <work-dir>
#   android/prepare.sh f1-2012 build/cmod
#
# The system image (imagefs.txz, proton-*.txz) is not in the upstream git tree;
# fetch it with android/fetch-system-assets.sh before building.
set -euo pipefail

GAME="${1:?game id, e.g. f1-2012}"
WORK="${2:?work dir, e.g. build/cmod}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GAME_DIR="$ROOT/games/$GAME"
# shellcheck source=UPSTREAM.env
source "$ROOT/android/UPSTREAM.env"

UPSTREAM_REPO="${UPSTREAM_REPO_OVERRIDE:-$UPSTREAM_REPO}"  # e.g. a local clone
[ -f "$GAME_DIR/gamepack.json" ] || { echo "No $GAME_DIR/gamepack.json" >&2; exit 1; }

if [ ! -d "$WORK/.git" ]; then
    mkdir -p "$WORK"
    git -C "$WORK" init -q
    git -C "$WORK" remote add origin "$UPSTREAM_REPO"
fi
GIT_LFS_SKIP_SMUDGE=1 git -C "$WORK" fetch -q --depth 1 origin "$UPSTREAM_COMMIT"
git -C "$WORK" checkout -q --force FETCH_HEAD
git -C "$WORK" clean -qfdx -e app/src/main/assets/imagefs.txz -e 'app/src/main/assets/proton-*.txz'

for patch in "$ROOT"/android/patches/*.patch; do
    echo "Applying $(basename "$patch")"
    git -C "$WORK" apply --whitespace=nowarn "$patch"
done

cp -R "$ROOT/android/overlay/." "$WORK/"

RES="$WORK/app/src/main/res"
ASSETS="$WORK/app/src/main/assets/gamepack"
mkdir -p "$ASSETS"
cp "$GAME_DIR/gamepack.json" "$ASSETS/gamepack.json"
for layout in controls_touch.icp controls_tilt.icp; do
    if [ -f "$GAME_DIR/$layout" ]; then cp "$GAME_DIR/$layout" "$ASSETS/$layout"
    else echo "Warning: $GAME_DIR/$layout missing, that control mode will show no on-screen buttons" >&2; fi
done

# Background: games/<id>/branding/background.(jpg|png|webp) or the neutral placeholder.
mkdir -p "$RES/drawable" "$RES/drawable-nodpi"
background=""
for ext in jpg png webp; do
    [ -f "$GAME_DIR/branding/background.$ext" ] && background="$GAME_DIR/branding/background.$ext" && break
done
if [ -n "$background" ]; then
    cp "$background" "$RES/drawable-nodpi/gamepack_background.${background##*.}"
else
    cp "$ROOT/android/defaults/gamepack_background.xml" "$RES/drawable/gamepack_background.xml"
fi

# Icon: games/<id>/branding/icon.png (square, 512x512 recommended) or the default adaptive icon.
if [ -f "$GAME_DIR/branding/icon.png" ]; then
    mkdir -p "$RES/mipmap-xxxhdpi"
    cp "$GAME_DIR/branding/icon.png" "$RES/mipmap-xxxhdpi/gamepack_ic_launcher.png"
else
    cp -R "$ROOT/android/defaults/icon/." "$RES/"
fi

# App name shown under the home-screen icon.
python3 - "$GAME_DIR/gamepack.json" "$RES/values/gamepack_app.xml" <<'PY'
import json, sys
from xml.sax.saxutils import escape
title = json.load(open(sys.argv[1], encoding="utf-8"))["title"]
title = escape(title).replace("'", "\\'")
open(sys.argv[2], "w", encoding="utf-8").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <string name="gamepack_app_name">{title}</string>\n</resources>\n')
PY

echo "Prepared $GAME in $WORK"
