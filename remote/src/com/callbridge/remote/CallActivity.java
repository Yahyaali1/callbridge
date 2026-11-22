package com.callbridge.remote;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.callbridge.common.Proto;

import org.json.JSONObject;

/** Incoming / outgoing / active call screen. Works over the lock screen. */
public class CallActivity extends Activity implements LinkService.Listener {
    final Handler h = new Handler(Looper.getMainLooper());
    TextView name, number, status, audioInfo;
    LinearLayout ringingRow, activeRow, controls, dtmfPad;
    TextView muteBtn, speakerBtn;
    boolean muted, speaker, ended;
    /** "answering" / "ending" after the user taps a button, until the host phone confirms. */
    String pending;
    final Runnable finisher = this::finish;
    PowerManager.WakeLock proximity;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 64), Ui.dp(this, 24), Ui.dp(this, 32));

        status = Ui.text(this, "", 16, Ui.MUTED, false);
        root.addView(status);
        name = Ui.text(this, "", 32, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        name.setPadding(0, Ui.dp(this, 12), 0, 0);
        root.addView(name);
        number = Ui.text(this, "", 16, Ui.MUTED, false);
        root.addView(number);
        audioInfo = Ui.text(this, "", 12, Ui.MUTED, false);
        audioInfo.setGravity(Gravity.CENTER);
        audioInfo.setPadding(0, Ui.dp(this, 10), 0, 0);
        root.addView(audioInfo);

        LinearLayout spacer = new LinearLayout(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        // DTMF keypad (hidden until needed)
        dtmfPad = Ui.col(this);
        dtmfPad.setGravity(Gravity.CENTER);
        String keys = "123456789*0#";
        for (int r = 0; r < 4; r++) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.CENTER);
            for (int c = 0; c < 3; c++) {
                char k = keys.charAt(r * 3 + c);
                TextView key = Ui.circle(this, String.valueOf(k), Ui.SURFACE, Ui.TEXT, 24, 64);
                key.setOnClickListener(v -> {
                    LinkService s = LinkService.instance;
                    if (s != null && s.audio != null) s.audio.sendDtmf(k);
                    number.setText(number.getText() + String.valueOf(k));
                });
                row.addView(key);
            }
            dtmfPad.addView(row);
        }
        dtmfPad.setVisibility(android.view.View.GONE);
        root.addView(dtmfPad);

        // In-call controls
        controls = Ui.row(this);
        controls.setGravity(Gravity.CENTER);
        muteBtn = control("🎙", "Mute", v -> toggleMute());
        speakerBtn = control("🔈", "Speaker", v -> toggleSpeaker());
        TextView keypadBtn = control("⌨", "Keypad", v ->
                dtmfPad.setVisibility(dtmfPad.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE));
        TextView beep = control("🔔", "Test tone", v -> {
            LinkService s = LinkService.instance;
            if (s != null) s.send(Proto.m("beep"));
            audioInfo.setText("Sent a 1.5 s test tone — the other person should hear a beep");
        });
        controls.addView(wrapLabel(muteBtn, "Mute"));
        controls.addView(wrapLabel(speakerBtn, "Speaker"));
        controls.addView(wrapLabel(keypadBtn, "Keypad"));
        controls.addView(wrapLabel(beep, "Test tone"));
        root.addView(controls);

        ringingRow = Ui.row(this);
        ringingRow.setGravity(Gravity.CENTER);
        TextView decline = Ui.circle(this, "✕", Ui.RED, Ui.TEXT, 30, 76);
        decline.setOnClickListener(v -> hangup());
        TextView answer = Ui.circle(this, "📞", Ui.GREEN, Ui.TEXT, 30, 76);
        answer.setOnClickListener(v -> answer());
        ringingRow.addView(wrapLabel(decline, "Decline"));
        LinearLayout gap = new LinearLayout(this);
        ringingRow.addView(gap, new LinearLayout.LayoutParams(Ui.dp(this, 60), 1));
        ringingRow.addView(wrapLabel(answer, "Answer"));
        root.addView(ringingRow);

        activeRow = Ui.row(this);
        activeRow.setGravity(Gravity.CENTER);
        TextView hang = Ui.circle(this, "📞", Ui.RED, Ui.TEXT, 30, 76);
        hang.setRotation(135);
        hang.setOnClickListener(v -> hangup());
        activeRow.addView(hang);
        root.addView(activeRow);

        setContentView(root);
        proximity = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "callbridge:proximity");

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != 0)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        handle(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    void handle(Intent i) {
        if (i != null && "answer".equals(i.getAction())) answer();
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        LinkService.listeners.add(this);
        render();
        tick();
    }

    @Override protected void onPause() {
        super.onPause();
        LinkService.listeners.remove(this);
        h.removeCallbacksAndMessages(null);
    }

    @Override protected void onDestroy() {
        if (proximity.isHeld()) proximity.release();
        super.onDestroy();
    }

    @Override public void onEvent(JSONObject m) { render(); }

    void answer() {
        LinkService s = LinkService.instance;
        if (s != null) s.answer();
        pending = "answering";
        render();
    }

    void hangup() {
        LinkService s = LinkService.instance;
        if (s != null) s.hangup();
        pending = "ending";
        render();
    }

    void toggleMute() {
        muted = !muted;
        LinkService s = LinkService.instance;
        if (s != null && s.audio != null) s.audio.setMuted(muted);
        muteBtn.setBackground(circleBg(muted ? Ui.TEXT : Ui.SURFACE));
        muteBtn.setTextColor(muted ? Ui.BG : Ui.TEXT);
    }

    void toggleSpeaker() {
        speaker = !speaker;
        LinkService s = LinkService.instance;
        if (s != null && s.audio != null) s.audio.setSpeaker(speaker);
        speakerBtn.setBackground(circleBg(speaker ? Ui.TEXT : Ui.SURFACE));
        updateProximity();
    }

    void render() {
        JSONObject c = LinkService.call;
        if (c == null) {
            pending = null;
            if (!ended) {
                ended = true;
                status.setText("Call ended");
                ringingRow.setVisibility(android.view.View.GONE);
                activeRow.setVisibility(android.view.View.GONE);
                controls.setVisibility(android.view.View.GONE);
                dtmfPad.setVisibility(android.view.View.GONE);
                if (proximity.isHeld()) proximity.release();
                h.postDelayed(finisher, 1200);
            }
            return;
        }
        if (ended) { // a new call arrived while the "Call ended" screen was showing
            h.removeCallbacks(finisher);
            ended = false;
        }
        String n = c.optString("name"), num = c.optString("number");
        name.setText(n.isEmpty() ? (num.isEmpty() ? "Unknown" : num) : n);
        if (dtmfPad.getVisibility() != android.view.View.VISIBLE) number.setText(n.isEmpty() ? "" : num);
        String st = c.optString("state");
        if ("answering".equals(pending) && !"ringing".equals(st)) pending = null; // host confirmed
        boolean ringing = "ringing".equals(st) && pending == null;
        ringingRow.setVisibility(ringing ? android.view.View.VISIBLE : android.view.View.GONE);
        activeRow.setVisibility(ringing ? android.view.View.GONE : android.view.View.VISIBLE);
        controls.setVisibility(ringing ? android.view.View.GONE : android.view.View.VISIBLE);
        if ("ending".equals(pending)) status.setText("Ending…");
        else if ("answering".equals(pending)) status.setText("Connecting…");
        else if ("lost".equals(c.optString("link"))) status.setText("Reconnecting to " + LinkService.hostName + "…");
        else if (ringing) status.setText("Incoming call via " + LinkService.hostName);
        else if ("dialing".equals(st)) status.setText("Calling…");
        else status.setText(duration(c.optLong("since")));

        JSONObject e = LinkService.engineInfo;
        if (e != null && !ringing) {
            if (e.has("error")) {
                audioInfo.setText("⚠ " + e.optString("error"));
                audioInfo.setTextColor(Ui.RED);
            } else {
                boolean down = "ok".equals(e.optString("downlink"));
                String up = e.optString("uplink");
                audioInfo.setText((down ? "✓ You hear caller" : "✕ Can't capture caller audio") + "   ·   "
                        + ("fail".equals(up) ? "✕ Caller can't hear you" : "Your voice via method " + up));
                audioInfo.setTextColor(down && !"fail".equals(up) ? Ui.MUTED : Ui.RED);
            }
        }
        updateProximity();
    }

    int lastRx, lastTx;
    TextView diag;

    /** Live per-second audio counters so problems are visible during a call. */
    void updateDiag() {
        if (diag == null) {
            diag = Ui.text(this, "", 11, Ui.MUTED, false);
            diag.setGravity(Gravity.CENTER);
            ((LinearLayout) audioInfo.getParent()).addView(diag, ((LinearLayout) audioInfo.getParent()).indexOfChild(audioInfo) + 1);
        }
        LinkService s = LinkService.instance;
        AudioLink a = s == null ? null : s.audio;
        JSONObject st = LinkService.engineStats;
        if (a == null || LinkService.call == null) { diag.setText(""); return; }
        int rx = a.rxFrames - lastRx, tx = a.txFrames - lastTx;
        lastRx = a.rxFrames; lastTx = a.txFrames;
        diag.setText(String.format("caller→host %s · host→you %d/s · you→host %d/s · into call %s",
                st == null ? "–" : st.optLong("downDb") + " dB", rx, tx, st == null ? "–" : st.optLong("upFps") + "/s"));
    }

    void tick() {
        updateDiag();
        JSONObject c = LinkService.call;
        if (c != null && pending == null && "active".equals(c.optString("state")) && !"lost".equals(c.optString("link")))
            status.setText(duration(c.optLong("since")));
        h.postDelayed(this::tick, 1000);
    }

    void updateProximity() {
        JSONObject c = LinkService.call;
        boolean want = c != null && !"ringing".equals(c.optString("state")) && !speaker;
        if (want && !proximity.isHeld()) proximity.acquire(4 * 60 * 60 * 1000L);
        if (!want && proximity.isHeld()) proximity.release();
    }

    static String duration(long since) {
        if (since <= 0) return "Connected";
        long s = (System.currentTimeMillis() - since) / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

    TextView control(String icon, String label, android.view.View.OnClickListener l) {
        TextView t = Ui.circle(this, icon, Ui.SURFACE, Ui.TEXT, 22, 60);
        t.setOnClickListener(l);
        return t;
    }

    LinearLayout wrapLabel(TextView btn, String label) {
        LinearLayout c = Ui.col(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(btn);
        TextView l = Ui.text(this, label, 12, Ui.MUTED, false);
        l.setGravity(Gravity.CENTER);
        l.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        l.setOnClickListener(v -> btn.performClick()); // label is part of the touch target
        c.addView(l);
        return c;
    }

    static android.graphics.drawable.GradientDrawable circleBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }
}
