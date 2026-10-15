#!/bin/sh
# Build a signed release APK on jerry and publish it to Marquee's self-update folder.
# usage: android/build.sh <versionCode> <versionName> "<release notes>"
# Needs: docker image `android-build` (android/build-env), keys in ~/marquee-android/keys.
set -e
VC=${1:?versionCode}; VN=${2:?versionName}; NOTES=${3:-}
SRC=$(cd "$(dirname "$0")" && pwd)
KEYS=$HOME/marquee-android/keys
OUT=$HOME/marquee/data/app
docker run --rm -u 1000:1000 -e HOME=/cache/home -v "$SRC":/src -v "$KEYS":/keys:ro -v android-gradle-cache:/cache android-build \
  gradle --no-daemon -q assembleRelease -PmarqueeKeystore=/keys/keystore.properties -PversionCode="$VC" -PversionName="$VN"
mkdir -p "$OUT"
cp "$SRC/app/build/outputs/apk/release/app-release.apk" "$OUT/marquee.apk"
python3 -c 'import json,sys; json.dump({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "notes": sys.argv[3]}, open(sys.argv[4], "w"))' \
  "$VC" "$VN" "$NOTES" "$OUT/latest.json"
echo "published $VN ($VC): $OUT/marquee.apk"
