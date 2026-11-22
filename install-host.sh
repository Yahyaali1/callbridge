#!/bin/bash
# One-time setup of the HOST phone (the one with the SIM), connected via USB.
# Installs the bridge app, grants its permissions, exempts it from battery optimisation,
# starts the audio engine and prints the pairing code.
# Usage: ./install-host.sh [serial]
set -euo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh
DEV=$(pick_device "${1:-}" "")
A="adb -s $DEV"
echo "Setting up host phone: $(device_label "$DEV")"

SDK=$($A shell getprop ro.build.version.sdk | tr -d '\r')
[ "$SDK" -ge 31 ] || { echo "Android 12 (API 31) or newer required, this phone has API $SDK."; exit 1; }

[ -f out/host.apk ] || ./build-apk.sh host
$A install -r out/host.apk
for p in READ_PHONE_STATE READ_CALL_LOG CALL_PHONE ANSWER_PHONE_CALLS RECEIVE_SMS READ_SMS SEND_SMS READ_CONTACTS; do
    $A shell pm grant $HOST_PKG android.permission.$p
done
[ "$SDK" -ge 33 ] && $A shell pm grant $HOST_PKG android.permission.POST_NOTIFICATIONS
$A shell cmd deviceidle whitelist +$HOST_PKG >/dev/null      # never doze-kill the bridge
$A shell cmd appops set $HOST_PKG RUN_ANY_IN_BACKGROUND allow
ANDROID_SERIAL=$DEV ./start-engine.sh
echo
echo "Pairing code: $($A shell run-as $HOST_PKG cat shared_prefs/bridge.xml 2>/dev/null | sed -n 's/.*name="code">\([0-9]*\)<.*/\1/p')"
echo "(also shown in the Call Bridge app on the host phone)"
echo "Now turn on the hotspot on this phone."
