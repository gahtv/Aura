#!/bin/bash
# Install a DEBUG build on the streamer — ONLY when debugging something the
# emulator can't reproduce. Debug builds are much slower on TV hardware;
# redeploy release with deploy-streamer.sh afterwards.
source "$(dirname "$0")/common.sh"
build :app:assembleDebug
install_to "$STREAMER_SERIAL" app/build/outputs/apk/debug/app-debug.apk
echo "Debug installed on streamer ($STREAMER_SERIAL) — remember to redeploy release after debugging."
