package com.callbridge.host;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Status / setup screen on the host phone. Normally you never need to open it. */
public class MainActivity extends Activity {
    static final String[] PERMS = {
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.CALL_PHONE, Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS,
    };
    static String[] perms() {
        if (android.os.Build.VERSION.SDK_INT < 33) return PERMS;
        String[] p = java.util.Arrays.copyOf(PERMS, PERMS.length + 1);
        p[PERMS.length] = Manifest.permission.POST_NOTIFICATIONS;
        return p;
    }

    final Handler h = new Handler(Looper.getMainLooper());
    TextView code, addr, phoneB, engine, perms;
    Button grant, battery;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(48), pad, pad);

        TextView title = text("Call Bridge · Host", 24, true);
        root.addView(title);
        root.addView(text("This phone shares its SIM with phone B over the hotspot.", 14, false));

        root.addView(label("Pairing code (enter on phone B)"));
        code = text("", 40, true);
        code.setTextColor(Color.rgb(0x4f, 0xc3, 0xf7));
        root.addView(code);

        root.addView(label("Bridge address"));
        addr = text("", 16, false); root.addView(addr);
        root.addView(label("Phone B"));
        phoneB = text("", 16, false); root.addView(phoneB);
        root.addView(label("Audio engine"));
        engine = text("", 16, false); root.addView(engine);
        root.addView(label("Permissions"));
        perms = text("", 16, false); root.addView(perms);

        grant = new Button(this); grant.setText("Grant permissions");
        grant.setOnClickListener(v -> requestPermissions(perms(), 1));
        root.addView(grant);
        battery = new Button(this); battery.setText("Allow running in background");
        battery.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(battery);
        Button regen = new Button(this); regen.setText("New pairing code");
        regen.setOnClickListener(v -> {
            BridgeService svc = BridgeService.instance;
            if (svc != null) svc.regenerateCode();
            else getSharedPreferences("bridge", MODE_PRIVATE).edit().putString("code", BridgeService.newCode())
                    .putInt("pair_failures", 0).putLong("pair_lock_until", 0).apply();
            refresh();
        });
        root.addView(regen);
        Button unpair = new Button(this); unpair.setText("Unpair phone B");
        unpair.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setMessage("Phone B will be disconnected and must pair again with a new code.")
                .setPositiveButton("Unpair", (d, w) -> {
                    BridgeService svc = BridgeService.instance;
                    if (svc != null) svc.unpair();
                    else getSharedPreferences("bridge", MODE_PRIVATE).edit().remove("pair_key")
                            .putString("code", BridgeService.newCode()).apply();
                    refresh();
                })
                .setNegativeButton("Cancel", null).show());
        root.addView(unpair);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        BridgeService.start(this);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override protected void onPause() { super.onPause(); h.removeCallbacksAndMessages(null); }

    void refresh() {
        h.removeCallbacksAndMessages(null);
        code.setText(getSharedPreferences("bridge", MODE_PRIVATE).getString("code", "…"));
        addr.setText(String.join("\n", localAddresses()) + "\nport 8765");
        BridgeService svc = BridgeService.instance;
        boolean paired = !getSharedPreferences("bridge", MODE_PRIVATE).getString("pair_key", "").isEmpty();
        phoneB.setText(svc == null ? "Service starting…" : svc.clientConnected() ? "● Connected"
                : paired ? "○ Paired, not connected" : "○ Not paired — enter the code below on phone B");

        boolean allGranted = true;
        for (String p : perms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) allGranted = false;
        perms.setText(allGranted ? "● All granted" : "○ Missing — tap Grant permissions");
        grant.setEnabled(!allGranted);
        boolean ignoring = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        battery.setEnabled(!ignoring);
        battery.setText(ignoring ? "Background running allowed" : "Allow running in background");

        String token = getSharedPreferences("bridge", MODE_PRIVATE).getString("engine_token", "");
        new Thread(() -> {
            JSONObject pong = BridgeService.EngineLink.ping(token);
            h.post(() -> engine.setText(pong != null
                    ? "● Running" + (pong.optBoolean("interceptable") ? "" : " (call audio not supported?)")
                    : "○ Not running — connect USB to a computer and run start-engine.sh (needed after every reboot)"));
        }).start();
        h.postDelayed(this::refresh, 3000);
    }

    static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address) out.add(a.getHostAddress() + "  (" + ni.getName() + ")");
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) out.add("Turn on the hotspot");
        return out;
    }

    TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    TextView label(String s) {
        TextView t = text(s.toUpperCase(), 12, true);
        t.setPadding(0, dp(18), 0, 0);
        t.setTextColor(Color.GRAY);
        t.setGravity(Gravity.START);
        return t;
    }

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
