#!/bin/bash
# Build debug and install to the EMULATOR ONLY (never the streamer).
source "$(dirname "$0")/common.sh"
build :app:assembleDebug
install_to "$EMULATOR_SERIAL" app/build/outputs/apk/debug/app-debug.apk
echo "Debug installed on emulator ($EMULATOR_SERIAL)"
