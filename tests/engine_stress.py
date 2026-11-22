#!/usr/bin/env python3
"""
Stress-tests the audio engine's session lifecycle without a phone call: opens and tears down
many sessions (abrupt closes, explicit stops, varying lengths) and checks the engine survives
with no thread errors. Usage: python3 tests/engine_stress.py [sessions]
"""
import json, os, random, re, socket, struct, subprocess, sys, time

N = int(sys.argv[1]) if len(sys.argv) > 1 else 40


def find_host():
    if os.environ.get("CALLBRIDGE_HOST"):
        return os.environ["CALLBRIDGE_HOST"]
    out = subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout
    for serial in [l.split()[0] for l in out.splitlines()[1:] if l.endswith("device")]:
        if subprocess.run(["adb", "-s", serial, "shell", "pm", "path", "com.callbridge.host"],
                          capture_output=True, text=True).stdout.strip():
            return serial
    sys.exit("No connected device has com.callbridge.host installed (set CALLBRIDGE_HOST=<serial>)")


HOST = find_host()


def adb(*a):
    return subprocess.run(["adb", "-s", HOST, *a], capture_output=True, text=True).stdout


def frame(t, b): return struct.pack(">BI", t, len(b)) + b
def js(o): return frame(1, json.dumps(o).encode())


def recv(s):
    h = b""
    while len(h) < 5: h += s.recv(5 - len(h))
    t, n = struct.unpack(">BI", h)
    d = b""
    while len(d) < n: d += s.recv(n - len(d))
    return t, d


prefs = adb("shell", "run-as com.callbridge.host cat shared_prefs/bridge.xml")
token = re.search(r'engine_token">([0-9a-f]+)<', prefs).group(1)
log_before = int(adb("shell", "wc -l < /data/local/tmp/callbridge-engine.log").strip() or 0)
adb("forward", "tcp:8766", "tcp:8766")
try:
    for i in range(N):
        s = socket.create_connection(("127.0.0.1", 8766), timeout=10)
        s.sendall(js({"t": "start", "token": token, "method": random.choice(["B", "C"]), "test": True}))
        recv(s)
        for _ in range(random.randint(0, 60)):       # 0 - 1.2 s of uplink audio
            s.sendall(frame(2, bytes(640)))
            time.sleep(0.02)
        if random.random() < 0.5:
            s.sendall(js({"t": "stop"}))
        s.close()                                     # abrupt close = call ended
        time.sleep(random.uniform(0, 0.3))
    s = socket.create_connection(("127.0.0.1", 8766), timeout=5)
    s.sendall(js({"t": "ping", "token": token}))
    pong = json.loads(recv(s)[1])
finally:
    adb("forward", "--remove", "tcp:8766")

time.sleep(3.5)  # let the last session's teardown finish
new_lines = adb("shell", f"tail -n +{log_before + 1} /data/local/tmp/callbridge-engine.log").splitlines()
errors = [l for l in new_lines if re.search(r"uncaught|thread:|did not stop|session error", l)]
alive = pong.get("t") == "pong"
print(f"{N} sessions; engine alive: {alive}; thread errors: {len(errors)}")
for e in errors[:5]: print("  ", e)
sys.exit(0 if alive and not errors else 1)
