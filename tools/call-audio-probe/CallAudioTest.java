import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Looper;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;

/**
 * Feasibility test for bridging a cellular call's audio without root.
 * Run as the shell user via app_process (see run.sh).
 *
 * Usage: CallAudioTest [seconds] [intercept|direct]
 *   1. Start the test, 2. place/answer a call on the Pixel.
 *   It records the caller's voice (downlink) to /data/local/tmp/downlink.wav
 *   and plays a beep pattern into the call (uplink) that the far end should hear.
 */
public class CallAudioTest {
    static final int RATE = 16000;
    static final String OUT = "/data/local/tmp/downlink.wav";

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 15;
        String mode = args.length > 1 ? args[1] : "intercept";

        Looper.prepareMainLooper();
        Context ctx = shellContext();
        log("context package=" + ctx.getOpPackageName());
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);

        try {
            Method m = AudioManager.class.getMethod("isPstnCallAudioInterceptable");
            log("isPstnCallAudioInterceptable = " + m.invoke(am));
        } catch (Throwable t) {
            log("isPstnCallAudioInterceptable check failed: " + root(t));
        }

        log("Waiting for an active call... place or answer a call on the Pixel now.");
        long deadline = System.currentTimeMillis() + 180_000;
        while (am.getMode() != AudioManager.MODE_IN_CALL) {
            if (System.currentTimeMillis() > deadline) { log("No call within 3 min, exiting."); return; }
            Thread.sleep(250);
        }
        log("Call active (mode=IN_CALL). Waiting 2s for audio path to settle...");
        Thread.sleep(2000);

        if (mode.equals("uplink")) { uplinkProbe(am, ctx); System.exit(0); }

        AudioRecord rec = null;
        AudioTrack trk = null;
        if (mode.equals("intercept")) {
            try { rec = interceptRecord(am); log("downlink: intercept API OK"); }
            catch (Throwable t) { log("downlink intercept API FAILED: " + root(t)); }
            try { trk = interceptTrack(am); log("uplink: intercept API OK"); }
            catch (Throwable t) { log("uplink intercept API FAILED: " + root(t)); }
        }
        if (rec == null) {
            try { rec = directRecord(ctx); log("downlink: direct VOICE_DOWNLINK OK"); }
            catch (Throwable t) { log("downlink direct FAILED: " + root(t)); }
        }
        if (trk == null) {
            try { trk = directTrack(am); log("uplink: direct TELEPHONY_TX OK"); }
            catch (Throwable t) { log("uplink direct FAILED: " + root(t)); }
        }

        final AudioRecord fRec = rec;
        final AudioTrack fTrk = trk;
        Thread recT = null, trkT = null;
        if (fRec != null) { recT = new Thread(() -> recordLoop(fRec, seconds)); recT.start(); }
        if (fTrk != null) { trkT = new Thread(() -> beepLoop(fTrk, seconds)); trkT.start(); }
        if (recT != null) recT.join();
        if (trkT != null) trkT.join();
        log("Done. Downlink file: " + (fRec != null ? OUT : "(none)"));
        System.exit(0);
    }

    // ---------- context ----------

    static final String SHELL_PKG = "com.android.shell";

    static Context shellContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context sys = (Context) at.getMethod("getSystemContext").invoke(thread);
        return new ShellContext(sys);
    }

    /** Context that identifies as com.android.shell (uid 2000) for permission/appops checks. */
    static class ShellContext extends android.content.ContextWrapper {
        private AudioManager audio;
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL_PKG; }
        @Override public String getOpPackageName() { return SHELL_PKG; }
        @Override public String getAttributionTag() { return null; }
        @Override public android.content.AttributionSource getAttributionSource() {
            return new android.content.AttributionSource.Builder(android.os.Process.myUid())
                    .setPackageName(SHELL_PKG).build();
        }
        @Override public Context getApplicationContext() { return this; }
        @Override public Object getSystemService(String name) {
            if (Context.AUDIO_SERVICE.equals(name)) {
                if (audio == null) {
                    try {
                        java.lang.reflect.Constructor<AudioManager> c = AudioManager.class.getDeclaredConstructor(Context.class);
                        c.setAccessible(true);
                        audio = c.newInstance(this);
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
                return audio;
            }
            return super.getSystemService(name);
        }
    }

    // ---------- audio sources ----------

    static AudioFormat fmt(int channel) {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(channel)
                .build();
    }

    static AudioRecord interceptRecord(AudioManager am) throws Exception {
        Method m = AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class);
        return (AudioRecord) m.invoke(am, fmt(AudioFormat.CHANNEL_IN_MONO));
    }

    static AudioTrack interceptTrack(AudioManager am) throws Exception {
        Method m = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
        return (AudioTrack) m.invoke(am, fmt(AudioFormat.CHANNEL_OUT_MONO));
    }

    static AudioRecord directRecord(Context ctx) {
        AudioRecord r = new AudioRecord.Builder()
                .setContext(ctx)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_DOWNLINK)
                .setAudioFormat(fmt(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(RATE * 2)
                .build();
        if (r.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord not initialized");
        return r;
    }

    static AudioTrack directTrack(AudioManager am) {
        AudioDeviceInfo tx = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            log("  output device: type=" + d.getType() + " name=" + d.getProductName());
            if (d.getType() == AudioDeviceInfo.TYPE_TELEPHONY) tx = d;
        }
        if (tx == null) throw new IllegalStateException("no TYPE_TELEPHONY output device listed");
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(fmt(AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(RATE * 2)
                .build();
        if (!t.setPreferredDevice(tx)) throw new IllegalStateException("setPreferredDevice(TELEPHONY) returned false");
        return t;
    }

    // ---------- loops ----------

    static void recordLoop(AudioRecord rec, int seconds) {
        try (RandomAccessFile f = new RandomAccessFile(new File(OUT), "rw")) {
            f.setLength(0);
            f.write(new byte[44]);
            rec.startRecording();
            log("recording downlink, state=" + rec.getRecordingState());
            short[] buf = new short[RATE / 10];
            byte[] bytes = new byte[buf.length * 2];
            long total = 0;
            double sumSq = 0; int n = 0; int sec = 0;
            while (total < (long) RATE * seconds) {
                int r = rec.read(buf, 0, buf.length);
                if (r <= 0) { log("read returned " + r); break; }
                for (int i = 0; i < r; i++) {
                    bytes[2 * i] = (byte) buf[i];
                    bytes[2 * i + 1] = (byte) (buf[i] >> 8);
                    sumSq += (double) buf[i] * buf[i];
                }
                f.write(bytes, 0, r * 2);
                total += r; n += r;
                if (n >= RATE) {
                    double rms = Math.sqrt(sumSq / n);
                    double db = rms > 0 ? 20 * Math.log10(rms / 32768.0) : -120;
                    log(String.format("  downlink sec %2d: level %6.1f dBFS %s", ++sec, db, bar(db)));
                    sumSq = 0; n = 0;
                }
            }
            rec.stop(); rec.release();
            writeWavHeader(f, total * 2);
        } catch (Throwable t) {
            log("record loop error: " + root(t));
        }
    }

    static void beepLoop(AudioTrack trk, int seconds) {
        try {
            trk.play();
            log("injecting beeps into uplink, playState=" + trk.getPlayState());
            short[] buf = new short[RATE / 2]; // 0.5 s chunks
            for (int chunk = 0; chunk < seconds * 2; chunk++) {
                boolean on = chunk % 2 == 0;
                double freq = (chunk / 2) % 2 == 0 ? 660 : 880;
                for (int i = 0; i < buf.length; i++) {
                    buf[i] = on ? (short) (Math.sin(2 * Math.PI * freq * i / RATE) * 12000) : 0;
                }
                int w = trk.write(buf, 0, buf.length);
                if (w < 0) { log("track write returned " + w); break; }
            }
            trk.stop(); trk.release();
            log("beeps finished");
        } catch (Throwable t) {
            log("beep loop error: " + root(t));
        }
    }

    // ---------- uplink probe: try each injection method with a distinct tone ----------

    static void uplinkProbe(AudioManager am, Context ctx) throws Exception {
        String[] names = {"A-intercept-api (LOW tone 440Hz)", "B-call-assistant-usage (MID tone 880Hz)", "C-preferred-telephony-device (HIGH tone 1500Hz)"};
        double[] freqs = {440, 880, 1500};
        for (int i = 0; i < 3; i++) {
            if (am.getMode() != AudioManager.MODE_IN_CALL) { log("call ended, stopping probe"); return; }
            log("=== Method " + names[i] + " ===");
            AudioTrack t = null;
            try {
                if (i == 0) t = interceptTrack(am);
                else if (i == 1) t = callAssistantTrack();
                else t = directTrack(am);
                log("created OK, state=" + t.getState());
            } catch (Throwable e) {
                log("create FAILED: " + root(e));
                continue;
            }
            try {
                t.play();
                Thread.sleep(300);
                AudioDeviceInfo rd = t.getRoutedDevice();
                log("playState=" + t.getPlayState() + " routedDevice=" + (rd == null ? "null" : ("type " + rd.getType() + " " + rd.getProductName())));
                final int idx = i;
                Thread dump = new Thread(() -> dumpsys("media.audio_flinger", "/data/local/tmp/af_" + "ABC".charAt(idx) + ".txt"));
                dump.start();
                tone(t, freqs[i], 7);
                dump.join();
                t.stop();
            } catch (Throwable e) {
                log("play FAILED: " + root(e));
            } finally {
                t.release();
            }
            Thread.sleep(1500); // gap between methods
        }
        log("probe finished");
    }

    static AudioTrack callAssistantTrack() throws Exception {
        AudioAttributes.Builder b = new AudioAttributes.Builder();
        Method su = AudioAttributes.Builder.class.getMethod("setSystemUsage", int.class);
        su.invoke(b, 17 /* USAGE_CALL_ASSISTANT */);
        return new AudioTrack.Builder()
                .setAudioAttributes(b.build())
                .setAudioFormat(fmt(AudioFormat.CHANNEL_OUT_MONO))
                .setBufferSizeInBytes(RATE * 2)
                .build();
    }

    /** Continuous beeping (0.3s on / 0.2s off) at one frequency for the given seconds. */
    static void tone(AudioTrack t, double freq, int seconds) {
        short[] on = new short[RATE * 3 / 10];
        short[] off = new short[RATE * 2 / 10];
        for (int i = 0; i < on.length; i++) on[i] = (short) (Math.sin(2 * Math.PI * freq * i / RATE) * 16000);
        for (int k = 0; k < seconds * 2; k++) {
            if (t.write(on, 0, on.length) < 0 || t.write(off, 0, off.length) < 0) { log("write error"); return; }
        }
    }

    static void dumpsys(String svc, String outFile) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "dumpsys " + svc + " > " + outFile + " 2>&1").start();
            p.waitFor();
        } catch (Throwable e) { log("dumpsys failed: " + root(e)); }
    }

    // ---------- helpers ----------

    static void writeWavHeader(RandomAccessFile f, long dataLen) throws Exception {
        f.seek(0);
        f.writeBytes("RIFF"); f.write(le32(36 + dataLen)); f.writeBytes("WAVE");
        f.writeBytes("fmt "); f.write(le32(16)); f.write(le16(1)); f.write(le16(1));
        f.write(le32(RATE)); f.write(le32(RATE * 2)); f.write(le16(2)); f.write(le16(16));
        f.writeBytes("data"); f.write(le32(dataLen));
    }

    static byte[] le32(long v) { return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >> 24)}; }
    static byte[] le16(int v) { return new byte[]{(byte) v, (byte) (v >> 8)}; }

    static String bar(double db) {
        int n = (int) Math.max(0, Math.min(40, (db + 80) / 2));
        return "#".repeat(n);
    }

    static String root(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    static final java.io.PrintStream LOGFILE = openLog();
    static java.io.PrintStream openLog() {
        try { return new java.io.PrintStream(new java.io.FileOutputStream("/data/local/tmp/calltest.log", false), true); }
        catch (Exception e) { return null; }
    }
    static void log(String s) {
        String line = String.format("%tT [calltest] %s", System.currentTimeMillis(), s);
        System.out.println(line);
        if (LOGFILE != null) LOGFILE.println(line);
    }
}
