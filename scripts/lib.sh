# Shared helpers for the setup scripts. Source it: . "$(dirname "$0")/scripts/lib.sh"

HOST_PKG=com.callbridge.host
PHONE_PKG=com.callbridge.remote

# Prints the serial of the device to use. Order: $1 (explicit) -> $ANDROID_SERIAL ->
# the device that has package $2 installed -> the only connected device.
pick_device() {
    local explicit="$1" pkg="$2" serials s
    if [ -n "$explicit" ]; then echo "$explicit"; return; fi
    if [ -n "${ANDROID_SERIAL:-}" ]; then echo "$ANDROID_SERIAL"; return; fi
    serials=$(adb devices | awk 'NR>1 && $2=="device"{print $1}')
    [ -z "$serials" ] && { echo "No device connected over USB (is USB debugging on?)" >&2; return 1; }
    if [ -n "$pkg" ]; then
        for s in $serials; do
            if adb -s "$s" shell pm path "$pkg" 2>/dev/null | grep -q package:; then echo "$s"; return; fi
        done
    fi
    if [ "$(echo "$serials" | wc -l | tr -d ' ')" = "1" ]; then echo "$serials"; return; fi
    echo "Several devices connected; choose one with ANDROID_SERIAL=<serial>:" >&2
    adb devices -l | tail -n +2 >&2
    return 1
}

device_label() { echo "$(adb -s "$1" shell getprop ro.product.model | tr -d '\r') ($1)"; }
