#!/bin/bash
# Shared env for Aura install scripts. Source this, don't run it.
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
ADB="$ANDROID_HOME/platform-tools/adb"

EMULATOR_SERIAL="${EMULATOR_SERIAL:-emulator-5554}"
# Streamer auto-registers via mDNS after pairing; the IP:port pairing trick fails.
STREAMER_SERIAL="${STREAMER_SERIAL:-adb-63231HFAG1KAFS-EsqC2A._adb-tls-connect._tcp}"

cd "$(dirname "$0")/.." || exit 1

build() { ./gradlew -q "$1" || exit 1; }

wait_boot() { # $1 = serial
    until [ "$("$ADB" -s "$1" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 3
    done
}

install_to() { # $1 = serial, $2 = apk path
    "$ADB" -s "$1" install -r "$2" || exit 1
}
