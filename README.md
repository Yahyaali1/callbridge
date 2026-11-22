# CallBridge

Use one Android phone's SIM from a second Android phone: make and receive **real cellular calls**
and **SMS**, with contacts and call history, over the first phone's Wi-Fi hotspot.

**No root, no extra hardware, no VoIP number.** The second phone doesn't need a SIM.

```
                 cellular network
                        │
┌───────────────────────┴───────────────────┐        Wi-Fi hotspot       ┌──────────────────────────┐
│ HOST phone (has the SIM)                  │◄──────────────────────────►│ PHONE (no SIM needed)    │
│                                           │  TCP :8765, pairing code   │                          │
│  Call Bridge app (normal app)             │  call control, SMS,        │  Bridge Phone app        │
│   · call state, dial/answer/hang up       │  contacts, 16 kHz audio    │   · dialer, recents,     │
│   · SMS send/receive, contacts, call log  │                            │     contacts, messages   │
│   · relays audio  ▲                       │                            │   · incoming-call screen │
│                   │ localhost :8766       │                            │   · mic / earpiece audio │
│  Audio engine (adb shell, uid 2000)       │                            │                          │
│   · captures caller's voice (downlink)    │                            │                          │
│   · injects your voice into call (uplink) │                            │                          │
└───────────────────────────────────────────┘                            └──────────────────────────┘
```

## How it works

Normal Android apps can't touch the audio of a cellular call. The **adb shell** user, however,
holds the permissions Google's own Call Screen uses (`CALL_AUDIO_INTERCEPTION`,
`CAPTURE_AUDIO_OUTPUT`, `MODIFY_AUDIO_ROUTING`). CallBridge launches a small **audio engine** from
the adb shell (like [scrcpy](https://github.com/Genymobile/scrcpy) launches its server). The engine
uses Android's own call-audio APIs to:

- capture the caller's voice (`AudioManager.getCallDownlinkExtractionAudioRecord`, falling back to
  the `VOICE_DOWNLINK` audio source), and
- play your voice into the call (`AudioManager.getCallUplinkInjectionAudioTrack`, falling back to
  the call-assistant usage or explicit routing to the telephony output).

Everything else (call control, SMS, contacts, notifications) is done by a regular app on each phone.
The engine sits idle until a call starts and only touches audio during the call.

**Trade-off:** because the engine is started over adb, it has to be restarted from a computer
**after every reboot of the host phone** (`./start-engine.sh`, takes a few seconds). Calls still
ring and SMS still work without it; only call audio needs it.

## Features

- Outgoing calls from the second phone using the host's number and SIM
- Incoming calls ring on the second phone: full-screen call screen when locked, heads-up with
  Answer/Decline when in use, ringtone and vibration
- Answer, decline, hang up, mute, speaker, in-call keypad (DTMF tones)
- SMS: conversations, send, incoming-message notifications
- Contacts and call history of the host phone
- Automatic pairing over the hotspot (finds the host as the Wi-Fi gateway)
- Survives link drops: the hotspot can go away and come back. During a call the screen shows
  *Reconnecting…* and audio resumes when the link returns
- The host phone stays the source of truth: the second phone re-syncs call state every heartbeat
  and can't get stuck on a call that has ended
- Battery-friendly: no polling while disconnected (wakes on network events), exponential backoff on
  the wrong Wi-Fi, 30 s heartbeat when idle, no wake locks when idle

## Requirements

| | |
|---|---|
| Host phone | Android 12+ with a SIM. Best: Android 13+ on a phone whose vendor supports call-audio interception (Pixels do; the engine reports it). Older/other phones fall back to the `VOICE_DOWNLINK` source and alternative injection methods, which may or may not work depending on the vendor. |
| Second phone | Android 12+. No SIM needed. |
| Computer | macOS or Linux with `adb`, a JDK 17+, and the Android SDK (build-tools and an android-34+ platform). Only needed for setup and after host reboots. |

Both phones need **USB debugging** enabled for the scripted setup (Settings → About phone → tap
*Build number* 7×, then Developer options → USB debugging).

## Setup

```bash
git clone <this repo> && cd callbridge
./build-apk.sh host && ./build-apk.sh remote

# 1. Host phone (with SIM) on USB:
./install-host.sh            # installs, grants permissions, starts engine, prints pairing code

# 2. Second phone on USB (or copy out/remote.apk to it and install manually):
./install-phone.sh 123456    # optional: pass the pairing code from step 1
```

Then:

1. Turn on the **hotspot** on the host phone.
2. Join that hotspot on the second phone. The *Bridge Phone* app connects by itself and shows
   **● Connected to &lt;host phone name&gt;**.
3. Make a test call. On the call screen, the line under the name shows the audio status, and
   the **Test tone** button plays a beep the other person should hear.

If both phones are plugged in at once, the scripts pick the right one by which app is installed,
or you can set `ANDROID_SERIAL=<serial>`.

### After the host phone reboots

The Call Bridge app restarts by itself. Only the audio engine needs restarting:

```bash
./start-engine.sh
```

The Call Bridge app on the host shows *Audio engine: ● Running* once it's up. Remember to turn the
hotspot back on too.

## Settings (⚙ on the second phone)

- **Pairing code**: shown in the Call Bridge app on the host. Only needed once: after pairing the
  phones use a stored key and the host shows a new code. To pair again, enter the new code here.
- **Host phone address**: leave empty. It's detected automatically as the hotspot gateway.
- **Call audio method**: *Auto* tries A → B → C. If the other person can't hear you on some phone,
  pick a method manually: A = official interception API, B = call-assistant usage,
  C = route to the telephony output device.
- **Mute the host phone's own microphone**: stops the host phone from also picking up its
  surroundings during bridged calls.
- **Full-screen incoming calls** / **Keep connected in background**: shortcuts to the system
  settings that make incoming calls reliable.

## Troubleshooting

| Symptom | What to check |
|---|---|
| "Audio engine not running" on the call screen | Run `./start-engine.sh` (needed after every host reboot). |
| You can't hear the caller | The diagnostic line on the call screen: `caller→host` should be around −20…−50 dB while they talk. If it's −99 dB, the host can't capture call audio on this phone/Android version. |
| They can't hear you | Use **Test tone**. If they don't hear it, try methods A/B/C in settings. `into call` should be ~50/s. |
| Not connected | Is the second phone on the host's hotspot (not your home Wi-Fi)? Is the pairing code right? |
| Calls don't ring when the screen is off | Settings → *Allow full-screen incoming calls*, and *Keep connected in background*. |

Logs: `adb shell cat /data/local/tmp/callbridge-engine.log` (engine), `adb logcat -s CallBridge`
(host app), `adb logcat -s CallBridgeB` (second phone).

## Security and privacy

- **The engine runs with adb-shell privileges** on the host phone. It only listens on
  `127.0.0.1`, and each start generates a new random token that only the bridge app receives.
- **Pairing**: the 6-digit code is used once. Pairing derives a random 256-bit key on both phones
  (ECDH P-256, bound to the code) and the host then rotates the code. Wrong codes are throttled:
  5 free tries, then a lockout that doubles from 30 s up to 1 h. *New pairing code* on the host
  clears the lockout, and *Unpair phone B* revokes the key.
- **Every connection is mutually authenticated** with that key. The host proves itself first, so if
  the second phone joins some other Wi-Fi, that network's gateway learns nothing.
- **Traffic is encrypted** (AES-256-GCM, fresh ephemeral keys per connection, counter nonces against
  replay/reordering) on top of the hotspot's own WPA2/WPA3.
- The bridge only accepts connections from private/link-local addresses (never the cellular side)
  and limits unauthenticated connections.
- Remaining risk: an attacker who is actively intercepting the hotspot *at the moment you pair* could
  brute-force the 6-digit code offline. Pair on a hotspot with a strong password.
- Nothing is recorded or stored. Audio is streamed in memory only. Nothing leaves your two
  phones except the call itself.
- The test hooks in the host app (simulated call states, used by `tests/`) can only be triggered
  over adb (the service requires the `DUMP` permission).
- Call audio capture/injection laws differ by country. CallBridge only relays calls you are a
  party to, but check your local rules.

## Project layout

```
common/            wire protocol shared by all parts (Proto.java, Secure.java: pairing + encryption)
host/              Call Bridge app for the host phone
  .../Engine.java    privileged audio engine (runs via app_process as the shell user)
  .../BridgeService  always-on service: server for the phone, call state, SMS, engine link
remote/            Bridge Phone app for the second phone
  .../LinkService    connection, reconnects, ringing, notifications, call state
  .../AudioLink      mic → host, host → earpiece/speaker
tests/
  sim_test.py        end-to-end UI/call-flow tests with simulated call states (both phones on USB)
  engine_stress.py   opens/tears down many audio sessions to check the engine never crashes
tools/call-audio-probe/  standalone probe used to check call-audio capture/injection on a phone
build-apk.sh  install-host.sh  install-phone.sh  start-engine.sh
```

The apps are plain Java with programmatic UIs and no third-party dependencies, built without
Gradle (`aapt2` + `javac` + `d8` + `apksigner`).

## Testing

With both phones on USB and the second phone on the host's hotspot:

```bash
python3 tests/sim_test.py        # 13 scenarios: incoming (screen off / app open / heads-up),
                                 # answer/decline on either phone, back-to-back calls, SMS,
                                 # app restarts, hotspot drops while idle / ringing / in a call
python3 tests/engine_stress.py   # 40 audio sessions torn down in random ways
```

Real calls can't be simulated, so before a release also check by hand: an outgoing call, an
incoming call, audio both ways, hanging up from either side, and sending an SMS.

## Limitations

- After a host reboot the engine needs a computer (no root means no other way to get the
  call-audio permissions).
- One second phone at a time. A new connection replaces the old one.
- Call waiting / conference calls aren't handled specially.
- For outgoing calls the call timer starts when dialing (Android doesn't tell normal apps when
  the other side picks up).
- Call audio support depends on the host phone's vendor implementation. Pixels work. Other phones:
  please report results.

## License

MIT, see [LICENSE](LICENSE).
