#!/bin/bash
# Build RELEASE (the deployed build: R8 + resource shrink, fast on TV
# hardware) and install to the streamer. This is the normal streamer deploy.
source "$(dirname "$0")/common.sh"
build :app:assembleRelease
install_to "$STREAMER_SERIAL" app/build/outputs/apk/release/app-release.apk
echo "Release installed on streamer ($STREAMER_SERIAL)"
# R8 can silently strip reflective rules -> crash at launch. Smoke-test.
"$ADB" -s "$STREAMER_SERIAL" shell am force-stop com.hans.aura
"$ADB" -s "$STREAMER_SERIAL" shell am start -n com.hans.aura/.MainActivity
sleep 6
if "$ADB" -s "$STREAMER_SERIAL" logcat -d | grep -m1 FATAL; then
    echo "WARNING: FATAL exception after launch — check logcat."
    exit 1
fi
echo "Launch smoke test passed."
