package com.callbridge.host;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Looper;

import com.callbridge.common.Proto;
import com.callbridge.common.Secure;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Privileged audio engine. Runs as the adb shell user (uid 2000) via app_process,
 * which holds CALL_AUDIO_INTERCEPTION / CAPTURE_AUDIO_OUTPUT / MODIFY_AUDIO_ROUTING.
 *
 * It idles on a localhost socket and only touches audio while the bridge app
 * has a session open during a call:
 *   downlink: caller's voice  -> AUDIO frames to the bridge app
 *   uplink:   AUDIO frames from the bridge app -> injected into the call
 *
 * Start: CLASSPATH=<host apk> app_process / com.callbridge.host.Engine <token>
 */
public class Engine {
    static final String SHELL_PKG = "com.android.shell";
    static final String VERSION = "3";
    static String token;
    static AudioManager am;

    public static void main(String[] args) throws Exception {
        token = args.length > 0 ? args[0] : "";
        // A failure in one call's audio thread must never take down the engine process.
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> log("uncaught in " + t.getName() + ": " + root(e)));
        Looper.prepareMainLooper();
        Context ctx = shellContext();
        am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        log("engine v" + VERSION + " started, interceptable=" + interceptable()
                + ", identity=" + currentOpPackage());
        if (token.equals("--selftest")) { selftest(ctx); System.exit(0); }

        // Heartbeat every 5 min + signal logging, to diagnose the engine being killed.
        Thread hb = new Thread(() -> {
            while (true) {
                try { Thread.sleep(300_000); } catch (InterruptedException e) { return; }
                log("heartbeat");
            }
        }, "heartbeat");
        hb.setDaemon(true);
        hb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> log("shutting down (signal)")));

        ServerSocket server = new ServerSocket(Proto.ENGINE_PORT, 4, InetAddress.getByName("127.0.0.1"));
        while (true) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                new Thread(() -> session(s, ctx), "session").start();
            } catch (Throwable e) {
                log("accept error: " + root(e));
                Thread.sleep(500);
            }
        }
    }

    // ------------------------------------------------------------------ session

    static void session(Socket s, Context ctx) {
        AudioRecord rec = null;
        AudioTrack trk = null;
        boolean mutedMic = false;
        final boolean[] running = {true};
        Thread[] workers = new Thread[3]; // downlink, uplink, stats
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            // Any local app can reach this port: bound the unauthenticated hello in size and time.
            s.setSoTimeout(5000);
            JSONObject hello = Proto.read(in, 4096).json();
            s.setSoTimeout(0);
            if (token.isEmpty() || !Secure.equal(token, hello.optString("token"))) {
                Proto.json(out, Proto.msg("error").put("error", "bad token"));
                return;
            }
            String cmd = hello.optString("t");
            if (cmd.equals("ping")) {
                Proto.json(out, Proto.msg("pong").put("version", VERSION).put("interceptable", interceptable()));
                return;
            }
            if (!cmd.equals("start")) return;

            // Wait for the modem audio path (mode IN_CALL) for up to 15 s.
            boolean test = hello.optBoolean("test", false); // stress test without a call
            long deadline = System.currentTimeMillis() + 15000;
            while (!test && am.getMode() != AudioManager.MODE_IN_CALL && System.currentTimeMillis() < deadline) Thread.sleep(100);
            if (!test && am.getMode() != AudioManager.MODE_IN_CALL) {
                Proto.json(out, Proto.msg("engine").put("error", "no active call"));
                return;
            }
            if (!test) Thread.sleep(300);

            String method = hello.optString("method", "auto");
            JSONObject status = Proto.msg("engine");
            try { rec = openDownlink(ctx); status.put("downlink", "ok"); }
            catch (Throwable e) { status.put("downlink", "fail: " + root(e)); log("downlink: " + root(e)); }

            String[] order = method.equals("auto") ? new String[]{"A", "B", "C"} : new String[]{method};
            for (String m : order) {
                try { trk = openUplink(m, ctx); status.put("uplink", m); break; }
                catch (Throwable e) { status.put("uplink_" + m, root(e)); log("uplink " + m + ": " + root(e)); }
            }
            if (trk == null) status.put("uplink", "fail");
            if (trk != null) {
                trk.play();
                AudioDeviceInfo rd = trk.getRoutedDevice();
                status.put("routed", rd == null ? "none" : rd.getType() + ":" + rd.getProductName());
            }
            if (hello.optBoolean("muteMic", false)) { am.setMicrophoneMute(true); mutedMic = true; }
            Proto.json(out, status);
            log("session started " + status);

            final AudioRecord fRec = rec;
            final AudioTrack fTrk = trk;
            final ArrayBlockingQueue<byte[]> up = new ArrayBlockingQueue<>(16);
            final long[] beepUntil = {0};
            final long[] st = new long[4]; // downFrames, downSumSq(scaled), downSamples, upFrames

            Thread downT = null, upT = null;
            if (fRec != null) {
                downT = new Thread(() -> {
                    byte[] buf = new byte[Proto.FRAME_BYTES];
                    fRec.startRecording();
                    try { while (running[0]) {
                        int n = fRec.read(buf, 0, buf.length);
                        if (n <= 0) break;
                        long sq = 0;
                        for (int i = 0; i + 1 < n; i += 2) { int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8)); sq += (long) v * v; }
                        synchronized (st) { st[0]++; st[1] += sq / 1024; st[2] += n / 2; }
                        try { Proto.write(out, Proto.AUDIO, buf, 0, n); } catch (Exception e) { break; }
                    } } catch (Throwable e) { if (running[0]) log("downlink thread: " + root(e)); }
                    running[0] = false;
                }, "downlink");
                downT.start();
                workers[0] = downT;
            }
            if (fTrk != null) {
                upT = new Thread(() -> {
                    long sample = 0;
                    try { while (running[0]) {
                        byte[] f;
                        try { f = up.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS); }
                        catch (InterruptedException e) { break; }
                        if (f == null) f = new byte[Proto.FRAME_BYTES]; // keep the stream fed with silence
                        if (System.currentTimeMillis() < beepUntil[0]) Proto.mixTone(f, f.length / 2, 1000, 0, sample);
                        sample += f.length / 2;
                        if (fTrk.write(f, 0, f.length) < 0) break;
                        synchronized (st) { st[3]++; }
                    } } catch (Throwable e) { if (running[0]) log("uplink thread: " + root(e)); }
                }, "uplink");
                upT.start();
                workers[1] = upT;
            }

            Thread statsT = new Thread(() -> {
                int statTicks = 0;
                while (running[0]) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    long df, ss, ns, uf;
                    synchronized (st) { df = st[0]; ss = st[1]; ns = st[2]; uf = st[3]; st[0] = st[1] = st[2] = st[3] = 0; }
                    double rms = ns > 0 ? Math.sqrt(ss * 1024.0 / ns) : 0;
                    double db = rms > 0 ? 20 * Math.log10(rms / 32768) : -99;
                    if (++statTicks % 15 == 1) // log every 30 s; phone B gets every update
                        log("stats downFps=" + df / 2 + " downDb=" + Math.round(db) + " upFps=" + uf / 2);
                    try {
                        Proto.json(out, Proto.m("stats", "downFps", df / 2, "downDb", Math.round(db), "upFps", uf / 2));
                    } catch (Exception e) { break; }
                }
            }, "stats");
            statsT.setDaemon(true);
            statsT.start();
            workers[2] = statsT;

            // Reader: uplink audio + control from the bridge app. Ends when app closes the session.
            while (running[0]) {
                Proto.Frame fr = Proto.read(in);
                if (fr.type == Proto.AUDIO) {
                    if (!up.offer(fr.data)) { up.poll(); up.offer(fr.data); } // drop oldest if behind
                } else {
                    JSONObject c = fr.json();
                    if ("beep".equals(c.optString("t"))) beepUntil[0] = System.currentTimeMillis() + 1500;
                    if ("stop".equals(c.optString("t"))) break;
                }
            }
        } catch (java.io.EOFException ignored) {
        } catch (Throwable e) {
            log("session error: " + root(e));
        } finally {
            // Always (normal stop, EOF, error): stop the workers and wait for them BEFORE releasing
            // the AudioRecord/AudioTrack; releasing while a thread is inside read()/write() crashes.
            running[0] = false;
            try { if (rec != null) rec.stop(); } catch (Throwable ignored) {}
            try { if (trk != null) { trk.pause(); trk.flush(); } } catch (Throwable ignored) {}
            if (workers[2] != null) workers[2].interrupt();
            for (Thread w : workers) {
                if (w == null) continue;
                try { w.join(3000); } catch (InterruptedException ignored) {}
                if (w.isAlive()) log("worker " + w.getName() + " did not stop in time");
            }
            if (mutedMic) am.setMicrophoneMute(false);
            if (rec != null) { try { rec.stop(); } catch (Throwable ignored) {} rec.release(); }
            if (trk != null) { try { trk.stop(); } catch (Throwable ignored) {} trk.release(); }
            try { s.close(); } catch (Exception ignored) {}
            log("session closed");
        }
    }

    // ------------------------------------------------------------------ audio

    static AudioFormat fmt(int ch) {
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.RATE).setChannelMask(ch).build();
    }

    static AudioRecord openDownlink(Context ctx) throws Exception {
        try {
            Method m = AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class);
            AudioRecord r = (AudioRecord) m.invoke(am, fmt(AudioFormat.CHANNEL_IN_MONO));
            if (r != null && r.getState() == AudioRecord.STATE_INITIALIZED) { log("downlink via intercept API"); return r; }
        } catch (Throwable e) {
            log("intercept downlink unavailable: " + root(e));
        }
        AudioRecord r = new AudioRecord.Builder().setContext(ctx)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                .setAudioFormat(fmt(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(Proto.FRAME_BYTES * 8).build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("record not initialized");
        log("downlink via VOICE_DOWNLINK source");
        return r;
    }

    /** A: official interception API, B: CALL_ASSISTANT usage, C: explicit TELEPHONY output device. */
    static AudioTrack openUplink(String method, Context ctx) throws Exception {
        int minBuf = AudioTrack.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int buf = Math.max(minBuf, Proto.FRAME_BYTES * 4);
        AudioTrack t;
        switch (method) {
            case "A": {
                Method m = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
                t = (AudioTrack) m.invoke(am, fmt(AudioFormat.CHANNEL_OUT_MONO));
                break;
            }
            case "B": {
                AudioAttributes.Builder b = new AudioAttributes.Builder();
                AudioAttributes.Builder.class.getMethod("setSystemUsage", int.class).invoke(b, 17 /* USAGE_CALL_ASSISTANT */);
                t = new AudioTrack.Builder().setAudioAttributes(b.build())
                        .setAudioFormat(fmt(AudioFormat.CHANNEL_OUT_MONO)).setBufferSizeInBytes(buf).build();
                break;
            }
            case "C": {
                AudioDeviceInfo tx = null;
                for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                    if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) tx = d;
                if (tx == null) throw new IllegalStateException("no telephony output device");
                t = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(fmt(AudioFormat.CHANNEL_OUT_MONO)).setBufferSizeInBytes(buf).build();
                if (!t.setPreferredDevice(tx)) { t.release(); throw new IllegalStateException("setPreferredDevice failed"); }
                break;
            }
            default: throw new IllegalArgumentException("unknown method " + method);
        }
        if (t == null || t.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("track not initialized");
        return t;
    }

    static boolean interceptable() {
        try { return (Boolean) AudioManager.class.getMethod("isPstnCallAudioInterceptable").invoke(am); }
        catch (Throwable e) { return false; }
    }

    // ------------------------------------------------------------------ shell context

    static Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
        ShellContext ctx = new ShellContext(sys);
        // Framework code (AudioTrack, AudioRecord, AudioManager internals) asks
        // ActivityThread.currentApplication() for the caller identity. systemMain() sets that to
        // the "android" package, which does not belong to uid 2000, so AudioFlinger rejects the
        // stream. Swap in an Application whose base context is our shell identity.
        android.app.Application app = new android.app.Application();
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
        attach.setAccessible(true);
        attach.invoke(app, ctx);
        java.lang.reflect.Field f = at.getDeclaredField("mInitialApplication");
        f.setAccessible(true);
        f.set(thread, app);
        return ctx;
    }

    static String currentOpPackage() {
        try { return (String) Class.forName("android.app.ActivityThread").getMethod("currentOpPackageName").invoke(null); }
        catch (Throwable t) { return "?" + root(t); }
    }

    /** Checks which audio paths can be created (run without a call: A needs an active call). */
    static void selftest(Context ctx) {
        try { AudioRecord r = openDownlink(ctx); log("selftest downlink: OK state=" + r.getState()); r.release(); }
        catch (Throwable e) { log("selftest downlink: FAIL " + root(e)); }
        for (String m : new String[]{"A", "B", "C"}) {
            try { AudioTrack t = openUplink(m, ctx); log("selftest uplink " + m + ": OK"); t.release(); }
            catch (Throwable e) { log("selftest uplink " + m + ": FAIL " + root(e)); }
        }
    }

    /** Identifies as com.android.shell (uid 2000) so permission / app-op checks pass. */
    static class ShellContext extends ContextWrapper {
        private AudioManager audio;
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL_PKG; }
        @Override public String getOpPackageName() { return SHELL_PKG; }
        @Override public String getAttributionTag() { return null; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PKG).build();
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public Object getSystemService(String name) {
            if (Context.AUDIO_SERVICE.equals(name)) {
                if (audio == null) {
                    try {
                        Constructor<AudioManager> c = AudioManager.class.getDeclaredConstructor(Context.class);
                        c.setAccessible(true);
                        audio = c.newInstance(this);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                return audio;
            }
            return super.getSystemService(name);
        }
    }

    static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static void log(String s) {
        System.out.println(String.format("%tF %<tT [engine] %s", System.currentTimeMillis(), s));
    }
}
