#!/bin/bash
# One-time setup of the second phone (no SIM needed), connected via USB.
# Installs the Bridge Phone app and grants its permissions. Optionally stores the pairing code.
# Usage: ./install-phone.sh [pairing-code] [serial]
# (Without USB you can also just copy out/remote.apk to the phone and install it; the app asks
#  for the permissions and the pairing code itself.)
set -euo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh
CODE="${1:-}"
DEV=$(pick_device "${2:-}" "")
A="adb -s $DEV"
echo "Setting up phone: $(device_label "$DEV")"

SDK=$($A shell getprop ro.build.version.sdk | tr -d '\r')
[ "$SDK" -ge 31 ] || { echo "Android 12 (API 31) or newer required, this phone has API $SDK."; exit 1; }

[ -f out/remote.apk ] || ./build-apk.sh remote
$A install -r out/remote.apk
$A shell pm grant $PHONE_PKG android.permission.RECORD_AUDIO
[ "$SDK" -ge 33 ] && $A shell pm grant $PHONE_PKG android.permission.POST_NOTIFICATIONS
[ "$SDK" -ge 34 ] && $A shell cmd appops set $PHONE_PKG USE_FULL_SCREEN_INTENT allow
$A shell cmd deviceidle whitelist +$PHONE_PKG >/dev/null      # stay connected in the background

if [ -n "$CODE" ]; then
    $A shell am force-stop $PHONE_PKG
    $A shell "run-as $PHONE_PKG sh -c 'mkdir -p shared_prefs && cat > shared_prefs/remote.xml'" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="code">$CODE</string>
</map>
EOF
    echo "Pairing code stored."
fi
$A shell am start -n $PHONE_PKG/.MainActivity >/dev/null
echo "Done. Join the host phone's hotspot on this phone; the app connects by itself."
