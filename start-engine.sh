#!/bin/bash
# (Re)start the privileged audio engine on the host phone (the one with the SIM).
# Needed once after every reboot of the host phone. Safe to run any time.
# Usage: ./start-engine.sh            (finds the phone that has the bridge app installed)
#        ANDROID_SERIAL=<serial> ./start-engine.sh
set -euo pipefail
cd "$(dirname "$0")"
. scripts/lib.sh
DEV=$(pick_device "" "$HOST_PKG")
A="adb -s $DEV"

APK=$($A shell pm path $HOST_PKG | head -1 | sed 's/^package://' | tr -d '\r')
if [ -z "$APK" ]; then echo "Bridge app not installed on $(device_label "$DEV"). Run ./install-host.sh first."; exit 1; fi
TOKEN=$(openssl rand -hex 16)

# [E]ngine: a pattern that matches the engine but not this shell command itself
$A shell "pkill -f com.callbridge.host.[E]ngine" >/dev/null 2>&1 || true
sleep 0.5
$A shell "CLASSPATH=$APK setsid nohup app_process / com.callbridge.host.Engine $TOKEN > /data/local/tmp/callbridge-engine.log 2>&1 < /dev/null &"
$A shell am start-foreground-service -n $HOST_PKG/.BridgeService --es engine_token "$TOKEN" >/dev/null
sleep 1.5
if $A shell "pgrep -f com.callbridge.host.[E]ngine" >/dev/null; then
    echo "Audio engine running on $(device_label "$DEV"). You can unplug the USB cable."
    $A shell cat /data/local/tmp/callbridge-engine.log | grep -m1 "started"
else
    echo "Engine failed to start. Log:"
    $A shell cat /data/local/tmp/callbridge-engine.log
    exit 1
fi
