# Call audio probe

Standalone check of whether a phone allows call-audio capture and injection from the adb shell.
It records the caller's voice to `/data/local/tmp/downlink.wav` on the phone (delete it afterwards)
and plays tones into the call that the other person should hear.

```bash
SDK=~/Library/Android/sdk; JAR=$SDK/platforms/android-36/android.jar
mkdir -p build && javac --release 17 -cp $JAR -d build CallAudioTest.java
$SDK/build-tools/36.0.0/d8 --min-api 31 --lib $JAR --output build build/*.class
(cd build && zip -q probe.jar classes.dex)
adb push build/probe.jar /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/probe.jar app_process / CallAudioTest 20 uplink   # then place a call
```
Modes: `intercept` / `direct` (record downlink 20 s + beeps), `uplink` (try injection methods A/B/C
with different tones).
