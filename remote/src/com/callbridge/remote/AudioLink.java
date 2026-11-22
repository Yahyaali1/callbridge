package com.callbridge.remote;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;

import com.callbridge.common.Proto;

/** Phone B's side of call audio: mic -> host, host -> earpiece/speaker. */
class AudioLink {
    interface Sink { void send(byte[] b, int len); }

    final AudioManager am;
    final Sink sink;
    AudioRecord rec;
    AudioTrack trk;
    Thread recThread;
    volatile boolean running;
    volatile boolean muted;
    volatile boolean speaker;
    long written; // samples written to the track
    volatile int rxFrames, txFrames, dropped; // counters for the in-call diagnostics line
    long rxSumSq, rxSamples, lastLog;
    volatile double[] dtmf;
    volatile long dtmfUntil;

    AudioLink(Context ctx, Sink sink) {
        this.am = ctx.getSystemService(AudioManager.class);
        this.sink = sink;
    }

    void start() {
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(false);
        int recMin = AudioRecord.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Proto.RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(recMin, Proto.FRAME_BYTES * 4));
        if (AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler e = AcousticEchoCanceler.create(rec.getAudioSessionId()); if (e != null) e.setEnabled(true); }
        if (NoiseSuppressor.isAvailable()) { NoiseSuppressor n = NoiseSuppressor.create(rec.getAudioSessionId()); if (n != null) n.setEnabled(true); }

        int trkMin = AudioTrack.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        trk = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Proto.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(Math.max(trkMin, Proto.FRAME_BYTES * 20))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setSessionId(rec.getAudioSessionId())
                .build();
        // Start playing after 60 ms of audio (default is "when the buffer is full"), and keep at most
        // ~250 ms queued so latency stays low; a full buffer makes non-blocking writes drop frames.
        trk.setStartThresholdInFrames(Proto.FRAME_SAMPLES * 3);
        trk.setBufferSizeInFrames(Math.min(trk.getBufferCapacityInFrames(), Proto.RATE / 4));
        trk.play();

        running = true;
        recThread = new Thread(() -> {
            byte[] buf = new byte[Proto.FRAME_BYTES];
            long sample = 0;
            rec.startRecording();
            while (running) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) break;
                if (muted) java.util.Arrays.fill(buf, (byte) 0);
                double[] d = dtmf;
                if (d != null && System.currentTimeMillis() < dtmfUntil) Proto.mixTone(buf, n / 2, d[0], d[1], sample);
                sample += n / 2;
                sink.send(buf, n);
                txFrames++;
            }
        }, "mic");
        recThread.start();
    }

    /** Called from the network thread for each downlink frame. Never blocks. */
    void play(byte[] pcm) {
        AudioTrack t = trk;
        if (t == null || !running) return;
        rxFrames++;
        long sq = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) { int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8)); sq += (long) v * v; }
        rxSumSq += sq; rxSamples += pcm.length / 2;
        long now = System.currentTimeMillis();
        if (now - lastLog > 2000) {
            double rms = rxSamples > 0 ? Math.sqrt((double) rxSumSq / rxSamples) : 0;
            android.util.Log.i("CallBridgeB", String.format("audio rx=%d dropped=%d tx=%d level=%.0f dB head=%d written=%d vol=%d/%d",
                    rxFrames, dropped, txFrames, rms > 0 ? 20 * Math.log10(rms / 32768) : -99,
                    t.getPlaybackHeadPosition(), written, am.getStreamVolume(AudioManager.STREAM_VOICE_CALL),
                    am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)));
            lastLog = now; rxSumSq = 0; rxSamples = 0;
        }
        int w = t.write(pcm, 0, pcm.length, AudioTrack.WRITE_NON_BLOCKING);
        if (w > 0) written += w / 2;
        if (w < pcm.length) dropped++; // buffer full (network burst): drop to stay real-time
    }

    void setMuted(boolean m) { muted = m; }

    void setSpeaker(boolean on) {
        speaker = on;
        int want = on ? AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : AudioDeviceInfo.TYPE_BUILTIN_EARPIECE;
        for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
            if (d.getType() == want) { am.setCommunicationDevice(d); return; }
        }
        if (!on) am.clearCommunicationDevice();
    }

    void sendDtmf(char c) {
        double[] d = Proto.dtmf(c);
        if (d == null) return;
        dtmf = d;
        dtmfUntil = System.currentTimeMillis() + 180;
    }

    void stop() {
        running = false;
        try { if (recThread != null) recThread.join(300); } catch (InterruptedException ignored) {}
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) {}
        try { if (trk != null) { trk.stop(); trk.release(); } } catch (Exception ignored) {}
        rec = null;
        trk = null;
        am.clearCommunicationDevice();
        am.setMode(AudioManager.MODE_NORMAL);
    }
}
