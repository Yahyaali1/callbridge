#!/bin/bash
# Build one of the apps without Gradle: ./build-apk.sh host|remote
# Needs a JDK (17+) and the Android SDK (build-tools + an android-34 or newer platform).
# Output: out/<app>.apk, signed with a local debug key (~/.android/callbridge-debug.keystore).
set -euo pipefail
cd "$(dirname "$0")"
APP="${1:?usage: ./build-apk.sh host|remote}"

# --- locate JDK ---
if [ -z "${JAVA_HOME:-}" ]; then
    for j in /opt/homebrew/opt/openjdk@21 /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@21 /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-17-openjdk*; do
        [ -x "$j/bin/javac" ] && export JAVA_HOME="$j" && break
    done
fi
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"
javac -version >/dev/null 2>&1 || { echo "No JDK found. Install JDK 17+ and/or set JAVA_HOME."; exit 1; }

# --- locate Android SDK ---
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ]; then
    for s in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do [ -d "$s" ] && SDK="$s" && break; done
fi
[ -d "${SDK:-}" ] || { echo "Android SDK not found. Set ANDROID_HOME."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM=$(ls "$SDK/platforms" | grep -E '^android-[0-9]+$' | sort -t- -k2 -n | tail -1)
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"
[ "${PLATFORM#android-}" -ge 34 ] || { echo "Need an android-34+ platform (have $PLATFORM)."; exit 1; }

B="build/$APP"
rm -rf "$B" && mkdir -p "$B/classes" "$B/dex" out
"$BT/aapt2" link --debug-mode -I "$ANDROID_JAR" --manifest "$APP/AndroidManifest.xml" -o "$B/unsigned.apk"
javac -nowarn -Xlint:-deprecation -Xlint:-options --release 17 -cp "$ANDROID_JAR" -d "$B/classes" \
    $(find common/src "$APP/src" -name '*.java') 2>&1 | grep -v '^Note:' || true
[ -n "$(find "$B/classes" -name '*.class' | head -1)" ] || { echo "Compilation failed"; exit 1; }
"$BT/d8" --min-api 31 --lib "$ANDROID_JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
(cd "$B/dex" && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"

KS="$HOME/.android/callbridge-debug.keystore"
if [ ! -f "$KS" ]; then
    mkdir -p "$HOME/.android"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias callbridge \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=CallBridge Debug" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "out/$APP.apk" "$B/aligned.apk"
echo "Built out/$APP.apk"
