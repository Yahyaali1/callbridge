package com.callbridge.common;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire format shared by phone B, the host bridge app and the audio engine.
 * Frame = [1 byte type][4 bytes big-endian length][payload].
 * JSON frames carry control messages ({"t": "..."}), AUDIO frames carry
 * 16 kHz mono PCM16 little-endian, normally 20 ms (640 bytes) each.
 */
public final class Proto {
    public static final int JSON = 1;
    public static final int AUDIO = 2;

    public static final int RATE = 16000;
    public static final int FRAME_SAMPLES = RATE / 50;      // 20 ms
    public static final int FRAME_BYTES = FRAME_SAMPLES * 2;

    public static final int BRIDGE_PORT = 8765;  // host bridge app <-> phone B (over the hotspot)
    public static final int ENGINE_PORT = 8766;  // host bridge app <-> audio engine (localhost only)

    private Proto() {}

    public static final class Frame {
        public final int type;
        public final byte[] data;
        Frame(int type, byte[] data) { this.type = type; this.data = data; }
        public JSONObject json() throws org.json.JSONException {
            return new JSONObject(new String(data, StandardCharsets.UTF_8));
        }
    }

    public static final int MAX_FRAME = 16 * 1024 * 1024;

    public static Frame read(DataInputStream in) throws IOException {
        return read(in, MAX_FRAME);
    }

    /** Reads one frame, rejecting payloads over {@code max} bytes (use a small max before authentication). */
    public static Frame read(DataInputStream in, int max) throws IOException {
        int type = in.readUnsignedByte();
        int len = in.readInt();
        if (len < 0 || len > max) throw new IOException("bad frame length " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new Frame(type, b);
    }

    /** Reads and decrypts one frame of an established secure session. */
    public static Frame read(DataInputStream in, Secure s) throws IOException {
        Frame f = read(in, MAX_FRAME);
        return new Frame(f.type, s.open(f.type, f.data));
    }

    public static void write(DataOutputStream out, int type, byte[] data, int off, int len) throws IOException {
        synchronized (out) {
            out.writeByte(type);
            out.writeInt(len);
            out.write(data, off, len);
            out.flush();
        }
    }

    /** Encrypts and writes one frame of an established secure session. */
    public static void write(DataOutputStream out, Secure s, int type, byte[] data, int off, int len) throws IOException {
        synchronized (out) { // encrypt under the stream lock so nonce order == wire order
            byte[] ct = s.seal(type, data, off, len);
            write(out, type, ct, 0, ct.length);
        }
    }

    public static void json(DataOutputStream out, JSONObject o) throws IOException {
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        write(out, JSON, b, 0, b.length);
    }

    public static void json(DataOutputStream out, Secure s, JSONObject o) throws IOException {
        byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
        write(out, s, JSON, b, 0, b.length);
    }

    public static JSONObject msg(String type) {
        JSONObject o = new JSONObject();
        try { o.put("t", type); } catch (org.json.JSONException ignored) {}
        return o;
    }

    /** Builds {"t": type, k1: v1, k2: v2, ...} without checked exceptions. */
    public static JSONObject m(String type, Object... kv) {
        JSONObject o = msg(type);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try { o.put((String) kv[i], kv[i + 1] == null ? JSONObject.NULL : kv[i + 1]); }
            catch (org.json.JSONException ignored) {}
        }
        return o;
    }

    /** Adds a sine tone (e.g. DTMF or a test beep) into a PCM frame in place. */
    public static void mixTone(byte[] pcm, int samples, double f1, double f2, long startSample) {
        for (int i = 0; i < samples; i++) {
            double t = (startSample + i) / (double) RATE;
            double v = Math.sin(2 * Math.PI * f1 * t) + (f2 > 0 ? Math.sin(2 * Math.PI * f2 * t) : 0);
            int s = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
            s += (int) (v * (f2 > 0 ? 7000 : 12000));
            if (s > 32767) s = 32767;
            if (s < -32768) s = -32768;
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
    }

    /** DTMF frequency pair for a keypad character, or null. */
    public static double[] dtmf(char c) {
        String rows = "123A456B789C*0#D";
        int i = rows.indexOf(Character.toUpperCase(c));
        if (i < 0) return null;
        double[] low = {697, 770, 852, 941};
        double[] high = {1209, 1336, 1477, 1633};
        return new double[]{low[i / 4], high[i % 4]};
    }
}
