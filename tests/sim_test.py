#!/usr/bin/env python3
"""
End-to-end call-flow tests using simulated call states injected into the Pixel bridge
(adb-only test hook). Drives phone B through adb and checks its screen / notifications.

Usage: python3 tests/sim_test.py [test numbers...]
Both phones connected over USB, phone B joined to the host's hotspot. Phones are found by which
app is installed; override with CALLBRIDGE_HOST=<serial> CALLBRIDGE_PHONE=<serial>.
"""
import os, re, subprocess, sys, time

NUM = "+15555550123"      # fictional caller number (555-01xx range)
NUM2 = "+15555550188"


def find_device(pkg, env):
    if os.environ.get(env):
        return os.environ[env]
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    for serial in [l.split()[0] for l in out.splitlines()[1:] if l.endswith("device")]:
        r = subprocess.run(["adb", "-s", serial, "shell", "pm", "path", pkg], capture_output=True, text=True).stdout
        if r.strip():
            return serial
    sys.exit(f"No connected device has {pkg} installed (set {env}=<serial>)")


HOST = find_device("com.callbridge.host", "CALLBRIDGE_HOST")
B = find_device("com.callbridge.remote", "CALLBRIDGE_PHONE")

results = []


def sh(serial, cmd, timeout=30):
    r = subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=timeout)
    return r.stdout


def sim(state=None, number=None, sms=None):
    extra = ""
    if state: extra += f" --es sim_state {state}"
    if number: extra += f" --es sim_number '{number}'"
    if sms: extra += f" --es sim_sms_from '{sms[0]}' --es sim_sms_body '{sms[1]}'"
    sh(HOST, f"am start-foreground-service -n com.callbridge.host/.BridgeService{extra}")


def b_top():
    out = sh(B, "dumpsys activity activities | grep -m1 topResumedActivity")
    m = re.search(r"com\.[\w.]+/[\w.]+", out)
    return m.group(0) if m else out.strip()


def b_awake():
    return "mWakefulness=Awake" in sh(B, "dumpsys power | grep mWakefulness=")


def b_ui():
    sh(B, "uiautomator dump /sdcard/cb_ui.xml >/dev/null 2>&1")
    x = sh(B, "cat /sdcard/cb_ui.xml")
    nodes = re.findall(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x)
    return [(t, ((int(a) + int(c)) // 2, (int(b) + int(d)) // 2)) for t, a, b, c, d in nodes if t]


def b_texts():
    return [clean(t) for t, _ in b_ui()]


def clean(t):
    return re.sub(r"[\u2066-\u2069\ufffd\xa0]", "", t).strip()


def b_tap_text(label):
    for t, (x, y) in b_ui():
        if clean(t) == label:
            sh(B, f"input tap {x} {y}")
            return True
    return False


def b_notif():
    """Returns (title, isCallStyle) of the remote app's foreground notification."""
    out = sh(B, "dumpsys notification --noredact")
    blocks = out.split("NotificationRecord(")
    for blk in blocks:
        if "pkg=com.callbridge.remote" in blk and "id=1 " in blk[:300]:
            title = re.search(r"android\.title=String \(([^)]*)\)", blk)
            callstyle = "android.callType" in blk or "CallStyle" in blk
            return (title.group(1) if title else None, callstyle)
    return (None, False)


def b_sms_notif(sender):
    out = sh(B, "dumpsys notification --noredact")
    return any("pkg=com.callbridge.remote" in blk and sender in blk for blk in out.split("NotificationRecord("))


def b_audio_mode():
    m = re.search(r"Actual mode = MODE_(\w+)", sh(B, "dumpsys audio | grep -m1 'Actual mode'"))
    return m.group(1) if m else "?"


def wait(fn, timeout=8.0, step=0.4):
    end = time.time() + timeout
    val = fn()
    while not val and time.time() < end:
        time.sleep(step)
        val = fn()
    return val


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + (f"   [{detail}]" if detail and not ok else ""))


def in_call_screen():
    return "CallActivity" in b_top()


def call_timer():
    return in_call_screen() and any(re.fullmatch(r"\d+:\d\d", t) for t in b_texts())


def open_app():
    sh(B, "am start -n com.callbridge.remote/.MainActivity")
    time.sleep(1.5)


def reset():
    sim("idle")
    time.sleep(1.5)
    sh(B, "cmd statusbar collapse")
    sh(B, "input keyevent KEYCODE_WAKEUP")
    sh(B, "input keyevent KEYCODE_HOME")
    time.sleep(1)


def connected():
    t = b_notif()[0]
    return t is not None and t.startswith("Connected to")


# ----------------------------------------------------------------------------- tests

def t_incoming_screen_off_then_missed():
    print("\n[1] Incoming call while B's screen is off and the app is in background, caller gives up")
    reset()
    sh(B, "input keyevent KEYCODE_SLEEP")
    time.sleep(2)
    sim("ringing", NUM)
    check("B screen turned on", wait(b_awake))
    check("ringing (CallStyle) notification", wait(lambda: b_notif()[1]), str(b_notif()))
    check("B shows call screen or incoming-call notification with Answer",
          wait(lambda: in_call_screen() or ("Answer" in b_texts() and any(NUM in t for t in b_texts()))),
          str(b_texts()[:10]))
    check("ringtone/vibration mode audio not in call", b_audio_mode() != "IN_COMMUNICATION")
    sim("idle")
    check("call screen closes when caller hangs up", wait(lambda: not in_call_screen(), 6), b_top())
    check("notification back to 'Connected to Pixel'", wait(connected, 6), str(b_notif()))


def t_answer_on_b():
    print("\n[2] Incoming call while B app is open: answer on B, then the other side hangs up")
    reset()
    open_app()
    sim("ringing", NUM)
    wait(in_call_screen)
    time.sleep(1)
    check("tap Answer", b_tap_text("Answer"))
    check("B shows 'Connecting…'", wait(lambda: "Connecting…" in b_texts(), 3), str(b_texts()))
    sim("offhook", NUM)   # what the Pixel reports once acceptRingingCall() succeeds
    check("B shows call timer", wait(call_timer), str(b_texts()))
    check("B audio in communication mode", wait(lambda: b_audio_mode() == "IN_COMMUNICATION"), b_audio_mode())
    check("ongoing-call notification", wait(lambda: b_notif()[1]), str(b_notif()))
    sim("idle")
    check("call screen closes", wait(lambda: not in_call_screen(), 6), b_top())
    check("B audio released", wait(lambda: b_audio_mode() == "NORMAL", 5), b_audio_mode())
    check("notification back to 'Connected to Pixel'", wait(connected, 6), str(b_notif()))


def t_answer_on_pixel():
    print("\n[3] Incoming call answered on the Pixel itself")
    reset()
    open_app()
    sim("ringing", NUM)
    wait(in_call_screen)
    sim("offhook", NUM)
    check("B switches to in-call (timer)", wait(call_timer), str(b_texts()))
    check("no Answer button any more", "Answer" not in b_texts())
    sim("idle")
    check("B ends call", wait(lambda: not in_call_screen(), 6), b_top())


def t_decline_on_b():
    print("\n[4] Incoming call declined on B")
    reset()
    open_app()
    sim("ringing", NUM)
    wait(in_call_screen)
    time.sleep(1)
    check("tap Decline", b_tap_text("✕"))
    check("B shows 'Ending…'", wait(lambda: "Ending…" in b_texts(), 3), str(b_texts()))
    sim("idle")  # what the Pixel reports after endCall()
    check("call screen closes", wait(lambda: not in_call_screen(), 6), b_top())
    check("notification back to 'Connected to Pixel'", wait(connected, 6), str(b_notif()))


def t_bridge_restart_mid_call():
    print("\n[5] Pixel bridge app dies during a call (B must not stay stuck in a call)")
    reset()
    open_app()
    sim("ringing", NUM)
    wait(in_call_screen)
    sim("offhook", NUM)
    wait(call_timer)
    sh(HOST, "am force-stop com.callbridge.host")
    check("B leaves the call screen", wait(lambda: not in_call_screen(), 10), b_top())
    check("B audio released", wait(lambda: b_audio_mode() == "NORMAL", 5), b_audio_mode())
    sh(HOST, "am start-foreground-service -n com.callbridge.host/.BridgeService")
    check("B reconnects automatically", wait(connected, 15), str(b_notif()))


def t_headsup_answer():
    print("\n[8] Incoming call while B is unlocked and using another app (heads-up notification)")
    reset()
    sh(B, "am start -a android.settings.SETTINGS")
    time.sleep(1.5)
    sim("ringing", NUM)
    check("call notification posted", wait(lambda: b_notif()[1], 6), str(b_notif()))
    if "Answer" not in b_texts():   # heads-up already collapsed: open the shade like a user would
        sh(B, "cmd statusbar expand-notifications"); time.sleep(1.5)
    check("notification shows Answer button", wait(lambda: "Answer" in b_texts(), 4), str(b_texts()[:12]))
    tapped = b_tap_text("Answer")
    if not tapped:  # heads-up collapsed between dump and tap: use the shade
        sh(B, "cmd statusbar expand-notifications"); time.sleep(1.5)
        tapped = b_tap_text("Answer")
    check("tap Answer in notification", tapped)
    check("call screen opens", wait(in_call_screen, 5), b_top())
    check("B shows 'Connecting…'", wait(lambda: "Connecting…" in b_texts(), 3), str(b_texts()))
    sim("offhook", NUM)
    check("B shows call timer", wait(call_timer), str(b_texts()))
    check("B audio in communication mode", wait(lambda: b_audio_mode() == "IN_COMMUNICATION"), b_audio_mode())
    sim("idle")
    check("call screen closes", wait(lambda: not in_call_screen(), 6), b_top())
    check("B audio released", wait(lambda: b_audio_mode() == "NORMAL", 5), b_audio_mode())


def t_back_to_back():
    print("\n[9] Second call arrives right after the first ends")
    reset()
    open_app()
    sim("ringing", NUM); wait(in_call_screen)
    sim("idle"); time.sleep(0.5)
    sim("ringing", NUM2)
    time.sleep(2.5)  # longer than the 'Call ended' close delay
    check("new call screen stays open", in_call_screen(), b_top())
    check("shows the new caller", any(NUM2 in t for t in b_texts()), str(b_texts()))
    sim("idle")
    check("closes after second call ends", wait(lambda: not in_call_screen(), 6), b_top())


def b_wifi(on):
    sh(B, f"svc wifi {'enable' if on else 'disable'}")


def t_wifi_drop_idle():
    print("\n[10] Hotspot link drops while idle, then comes back")
    reset()
    b_wifi(False)
    check("B notices within 5 s", wait(lambda: not connected(), 5), str(b_notif()))
    check("B says to join the hotspot", wait(lambda: "hotspot" in (b_notif_text() or ""), 5), str(b_notif_text()))
    time.sleep(5)
    b_wifi(True)
    check("B reconnects automatically within 25 s", wait(connected, 25), str(b_notif()))


def t_wifi_drop_in_call():
    print("\n[11] Hotspot link drops during a call and comes back: call resumes")
    reset()
    open_app()
    sim("ringing", NUM); wait(in_call_screen)
    sim("offhook", NUM)
    wait(call_timer)
    b_wifi(False)
    check("B shows 'Reconnecting…' (call kept)", wait(lambda: in_call_screen() and any(t.startswith("Reconnecting") for t in b_texts()), 6),
          str(b_texts()))
    check("B audio paused while disconnected", wait(lambda: b_audio_mode() == "NORMAL", 5), b_audio_mode())
    time.sleep(3)
    b_wifi(True)
    check("call resumes with timer after reconnect", wait(call_timer, 25), str(b_texts()))
    check("B audio running again", wait(lambda: b_audio_mode() == "IN_COMMUNICATION", 5), b_audio_mode())
    sim("idle")
    check("call ends normally afterwards", wait(lambda: not in_call_screen(), 6), b_top())


def t_wifi_drop_call_ends_meanwhile():
    print("\n[12] Link drops during a call and the call ends before B reconnects")
    reset()
    open_app()
    sim("ringing", NUM); wait(in_call_screen)
    sim("offhook", NUM)
    wait(call_timer)
    b_wifi(False)
    wait(lambda: any(t.startswith("Reconnecting") for t in b_texts()), 6)
    sim("idle")           # caller hung up while B was away
    time.sleep(2)
    b_wifi(True)
    check("B closes the stale call after reconnecting", wait(lambda: not in_call_screen(), 25), b_top())
    check("B back to 'Connected'", wait(connected, 10), str(b_notif()))


def t_wifi_drop_while_ringing():
    print("\n[13] Link drops while ringing; still ringing when B is back")
    reset()
    sim("ringing", NUM)
    wait(lambda: b_notif()[1])
    b_wifi(False)
    time.sleep(4)
    b_wifi(True)
    check("B rings again after reconnect", wait(lambda: b_notif()[1] and connected_or_call(), 25), str(b_notif()))
    sim("idle")
    check("and stops when the caller gives up", wait(connected, 10), str(b_notif()))


def connected_or_call():
    return "lost" not in sh(B, "dumpsys notification --noredact | grep -A3 'pkg=com.callbridge.remote' | grep -m1 Reconnecting")


def b_notif_text():
    out = sh(B, "dumpsys notification --noredact")
    for blk in out.split("NotificationRecord("):
        if "pkg=com.callbridge.remote" in blk and "id=1 " in blk[:300]:
            m = re.search(r"android\.text=String \(([^)]*)\)", blk)
            return m.group(1) if m else None
    return None


def t_incoming_sms():
    print("\n[6] Incoming SMS while B app is in background")
    reset()
    sender = "+15555550142"
    sim(sms=(sender, "Test message from sim"))
    check("SMS notification on B", wait(lambda: b_sms_notif(sender), 6))


def t_ring_after_reconnect():
    print("\n[7] Call already ringing when B (re)connects")
    reset()
    sim("ringing", NUM)
    wait(in_call_screen)
    sh(B, "am force-stop com.callbridge.remote")   # B app killed while ringing
    time.sleep(1)
    sh(B, "am start -n com.callbridge.remote/.MainActivity")
    check("B shows the ringing call again after restart", wait(in_call_screen, 12), b_top())
    sim("idle")
    check("and closes when it ends", wait(lambda: not in_call_screen(), 6), b_top())


if __name__ == "__main__":
    sh(B, "am start -n com.callbridge.remote/.MainActivity")
    if not wait(connected, 15):
        print("Phone B is not connected to the Pixel bridge:", b_notif()); sys.exit(1)
    tests = [t_incoming_screen_off_then_missed, t_answer_on_b, t_answer_on_pixel, t_decline_on_b,
             t_bridge_restart_mid_call, t_incoming_sms, t_ring_after_reconnect, t_headsup_answer, t_back_to_back,
             t_wifi_drop_idle, t_wifi_drop_in_call, t_wifi_drop_call_ends_meanwhile, t_wifi_drop_while_ringing]
    only = sys.argv[1:]
    for i, t in enumerate(tests, 1):
        if not only or str(i) in only:
            try: t()
            except Exception as e: check(f"{t.__name__} crashed: {e}", False)
    b_wifi(True)
    reset()
    sh(B, "rm -f /sdcard/cb_ui.xml")
    passed = sum(ok for _, ok in results)
    print(f"\n{passed}/{len(results)} checks passed")
    sys.exit(0 if passed == len(results) else 1)
