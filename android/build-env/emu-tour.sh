#!/bin/sh
# Build the debug app, install it on the running android-emu container and screenshot each tab.
# usage (on jerry, from ~/marquee-src/android): build-env/emu-tour.sh  -> /tmp/shots/*.png
set -e
cd "$(dirname "$0")/.."
# HOME lives in the cache volume so the debug signing key stays the same between builds
docker run --rm -v android-gradle-cache:/cache alpine sh -c "mkdir -p /cache/home && chown 1000:1000 /cache/home"
docker run --rm -u 1000:1000 -e HOME=/cache/home -v "$PWD":/src -v android-gradle-cache:/cache android-build \
  gradle --no-daemon -q assembleDebug || { echo "debug build failed"; exit 1; }
E="docker exec android-emu adb"
docker cp app/build/outputs/apk/debug/app-debug.apk android-emu:/tmp/app-debug.apk
$E uninstall com.basdhaweio.marquee.debug >/dev/null 2>&1 || true
$E install /tmp/app-debug.apk >/dev/null
$E shell am force-stop com.basdhaweio.marquee.debug
$E shell am start -n com.basdhaweio.marquee.debug/com.basdhaweio.marquee.MainActivity >/dev/null
rm -rf /tmp/shots && mkdir -p /tmp/shots
shot() { sleep "$2"; $E exec-out screencap -p > "/tmp/shots/$1.png"; }
tap() { $E shell input tap "$1" "$2"; }
Y=2274   # bottom navigation bar on a 1080x2400 screen
shot 1-home 10
tap 319 $Y; shot 2-queue 8
tap 540 $Y; shot 3-library 8
tap 540 560; shot 4-show 8          # first show in the list
$E shell input keyevent KEYCODE_BACK
tap 760 $Y; shot 5-claude 6
tap 980 $Y; shot 6-more 4
tap 300 260; shot 7-requests 8      # More -> Requests
$E shell input keyevent KEYCODE_BACK
tap 300 420; shot 8-activity 10     # More -> Activity & stats
$E logcat -d -s AndroidRuntime:E | tail -20
ls /tmp/shots
