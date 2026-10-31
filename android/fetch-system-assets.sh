#!/usr/bin/env bash
# Copies the system image that upstream keeps out of git (imagefs.txz, proton-9.0-*.txz)
# from an official Winlator Cmod release APK into <work-dir>/app/src/main/assets.
#
# Usage: android/fetch-system-assets.sh <work-dir> [apk-url]
# Without apk-url the newest Cmod release APK is looked up with the GitHub CLI (`gh`).
set -euo pipefail

WORK="${1:?work dir}"
APK_URL="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=UPSTREAM.env
source "$ROOT/android/UPSTREAM.env"
ASSETS="$WORK/app/src/main/assets"
NEEDED=(imagefs.txz proton-9.0-x86_64.txz proton-9.0-arm64ec.txz)

missing=0
for f in "${NEEDED[@]}"; do [ -s "$ASSETS/$f" ] || missing=1; done
if [ "$missing" = 0 ]; then echo "System assets already present"; exit 0; fi

if [ -z "$APK_URL" ]; then
    # Prefer an APK whose name mentions Bionic (matches the pinned branch), else the first APK.
    APK_URL="$(gh api "repos/$UPSTREAM_GH/releases?per_page=30" --jq '
        [ .[] | .assets[] | select(.name | test("\\.apk$"; "i")) ] as $apks
        | ([ $apks[] | select(.name | test("bionic"; "i")) ] + $apks)[0].browser_download_url')"
fi
[ -n "$APK_URL" ] && [ "$APK_URL" != "null" ] || { echo "Could not find a Cmod release APK" >&2; exit 1; }
echo "Using system assets from $APK_URL"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL --retry 4 -o "$TMP/cmod.apk" "$APK_URL"
for f in "${NEEDED[@]}"; do
    unzip -o -j -q "$TMP/cmod.apk" "assets/$f" -d "$ASSETS" || { echo "assets/$f not found in the APK" >&2; exit 1; }
done
ls -la "${NEEDED[@]/#/$ASSETS/}"
