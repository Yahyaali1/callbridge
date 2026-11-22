package com.callbridge.host;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.callbridge.common.Proto;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Always-on (foreground) service on the host phone (the one with the SIM). Accepts one connection from phone B over
 * the hotspot, relays call control / SMS, and during a call connects phone B's audio to
 * the privileged audio Engine over localhost.
 */
public class BridgeService extends Service {
    static final String TAG = "CallBridge";
    static volatile BridgeService instance;

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    SharedPreferences prefs;
    TelecomManager telecom;
    TelephonyManager tel;
    ServerSocket server;
    PhoneStateListener phoneListener; // must be a field: telephony only keeps a weak reference
    volatile Client client;
    volatile EngineLink engineLink;

    // call state
    volatile String callState = "idle"; // idle | ringing | active
    volatile String callNumber = "";
    volatile boolean callIncoming;
    volatile String lastDialed = "";
    volatile long callStart;

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("bridge", MODE_PRIVATE);
        if (!prefs.contains("code")) {
            prefs.edit().putString("code", String.format("%06d", new SecureRandom().nextInt(1_000_000))).apply();
        }
        telecom = getSystemService(TelecomManager.class);
        tel = getSystemService(TelephonyManager.class);
        goForeground("Waiting for phone B");
        try {
            phoneListener = new PhoneStateListener() {
                @Override public void onCallStateChanged(int state, String number) { onCallState(state, number); }
            };
            tel.listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (SecurityException e) {
            Log.e(TAG, "no phone state permission", e);
        }
        new Thread(this::serve, "bridge-server").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("engine_token")) {
            prefs.edit().putString("engine_token", intent.getStringExtra("engine_token")).apply();
            Log.i(TAG, "engine token updated");
        }
        if (intent != null && intent.hasExtra("phone_state")) {   // from Receivers.CallState (backup path)
            onCallState(intent.getIntExtra("phone_state", 0), intent.getStringExtra("number"));
        }
        if (intent != null && intent.hasExtra("sim_state")) {     // test hook, adb only (service needs DUMP)
            String st = intent.getStringExtra("sim_state");
            int code = "ringing".equals(st) ? TelephonyManager.CALL_STATE_RINGING
                    : "offhook".equals(st) ? TelephonyManager.CALL_STATE_OFFHOOK : TelephonyManager.CALL_STATE_IDLE;
            if (intent.hasExtra("sim_dialed")) lastDialed = intent.getStringExtra("sim_dialed");
            onCallState(code, intent.getStringExtra("sim_number"));
        }
        if (intent != null && intent.getBooleanExtra("sim_audio", false)) {  // test hook: 4 s tone to B
            new Thread(() -> {
                byte[] f = new byte[Proto.FRAME_BYTES];
                long sample = 0;
                for (int i = 0; i < 200; i++) {
                    java.util.Arrays.fill(f, (byte) 0);
                    Proto.mixTone(f, Proto.FRAME_SAMPLES, 440, 0, sample);
                    sample += Proto.FRAME_SAMPLES;
                    sendAudio(f, f.length);
                    try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                }
            }).start();
        }
        if (intent != null && intent.hasExtra("sim_sms_from")) {  // test hook, adb only
            onSms(intent.getStringExtra("sim_sms_from"), intent.getStringExtra("sim_sms_body"), System.currentTimeMillis());
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        instance = null;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    String pairCode() { return prefs.getString("code", ""); }

    /** User-visible device name ("Pixel 8", "Galaxy S23"...), shown on phone B. */
    String deviceName() {
        String n = android.provider.Settings.Global.getString(getContentResolver(), android.provider.Settings.Global.DEVICE_NAME);
        return n == null || n.isEmpty() ? android.os.Build.MODEL : n;
    }
    String engineToken() { return prefs.getString("engine_token", ""); }
    boolean clientConnected() { return client != null; }

    void goForeground(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "Call bridge", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "bridge")
                .setSmallIcon(android.R.drawable.stat_sys_phone_call_forward)
                .setContentTitle("Call Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (android.os.Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    // ------------------------------------------------------------------ calls

    /** Called from the listener and the PHONE_STATE broadcast; duplicates are ignored. */
    synchronized void onCallState(int state, String number) {
        String want = state == TelephonyManager.CALL_STATE_RINGING ? "ringing"
                : state == TelephonyManager.CALL_STATE_OFFHOOK ? "active" : "idle";
        boolean hasNumber = number != null && !number.isEmpty();
        if (want.equals(callState)) {
            // same state again: only useful if it now carries the caller's number
            if (hasNumber && callNumber.isEmpty() && !want.equals("idle")) {
                callNumber = number;
                send(want.equals("ringing")
                        ? Proto.m("ringing", "number", callNumber, "name", Data.nameFor(this, callNumber))
                        : callJson("active"));
            }
            return;
        }
        Log.i(TAG, "call state " + callState + " -> " + want);
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                callState = "ringing";
                callIncoming = true;
                callNumber = number == null ? "" : number;
                send(Proto.m("ringing", "number", callNumber, "name", Data.nameFor(this, callNumber)));
                break;
            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (!callState.equals("ringing")) { callIncoming = false; callNumber = hasNumber ? number : lastDialed; }
                callState = "active";
                callStart = System.currentTimeMillis();
                send(callJson("active"));
                startEngine();
                break;
            case TelephonyManager.CALL_STATE_IDLE:
                callState = "idle";
                callNumber = "";
                send(Proto.m("ended"));
                stopEngine();
                break;
        }
        main.post(() -> goForeground(client != null ? "Phone B connected" + (callState.equals("idle") ? "" : " · call " + callState) : "Waiting for phone B"));
    }

    JSONObject callJson(String type) {
        JSONObject o = Proto.msg(type);
        try {
            o.put("number", callNumber).put("name", Data.nameFor(this, callNumber))
                    .put("incoming", callIncoming).put("since", callStart);
        } catch (Exception ignored) {}
        return o;
    }

    void dial(String number) {
        lastDialed = number;
        Bundle extras = new Bundle();
        extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false);
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), extras);
        } catch (Exception e) {
            Log.e(TAG, "dial failed", e);
            send(Proto.m("error", "error", "Could not place call: " + e.getMessage()));
            if (callState.equals("idle")) send(Proto.m("ended"));
        }
    }

    @SuppressWarnings("deprecation")
    void answer() {
        try { telecom.acceptRingingCall(); } catch (SecurityException e) { Log.e(TAG, "answer", e); }
    }

    @SuppressWarnings("deprecation")
    void hangup() {
        try { telecom.endCall(); } catch (SecurityException e) { Log.e(TAG, "hangup", e); }
    }

    void startEngine() {
        stopEngine();
        if (client == null) return; // nobody to bridge to; phone B can still pick up audio later
        engineLink = new EngineLink(this, prefs.getString("method", "auto"), prefs.getBoolean("muteMic", false));
        engineLink.start();
    }

    void stopEngine() {
        EngineLink e = engineLink;
        engineLink = null;
        if (e != null) e.stop();
    }

    // ------------------------------------------------------------------ sms

    void onSms(String from, String body, long date) {
        send(Proto.m("sms", "address", from, "name", Data.nameFor(this, from),
                "body", body, "date", date));
    }

    void sendSms(String to, String body) {
        try {
            SmsManager sm = getSystemService(SmsManager.class);
            ArrayList<String> parts = sm.divideMessage(body);
            sm.sendMultipartTextMessage(to, null, parts, null, null);
            send(Proto.m("sms_sent", "address", to, "ok", true));
        } catch (Exception e) {
            send(Proto.m("sms_sent", "address", to, "ok", false, "error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ network

    void serve() {
        while (true) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(Proto.BRIDGE_PORT));
                while (true) {
                    Socket s = server.accept();
                    s.setTcpNoDelay(true);
                    s.setKeepAlive(true);
                    new Client(s).start();
                }
            } catch (Exception e) {
                Log.e(TAG, "server error, restarting", e);
                try { if (server != null) server.close(); } catch (Exception ignored) {}
                if (instance == null) return;
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            }
        }
    }

    void send(JSONObject o) {
        Client c = client;
        if (c != null) c.send(o);
    }

    void sendAudio(byte[] b, int len) {
        Client c = client;
        if (c != null) c.sendAudio(b, len);
    }

    class Client extends Thread {
        final Socket sock;
        DataOutputStream out;

        Client(Socket s) { super("bridge-client"); sock = s; }

        final ExecutorService writer = Executors.newSingleThreadExecutor();

        /** Queues a control message; callable from any thread including main. */
        void send(JSONObject o) {
            DataOutputStream o2 = out;
            if (o2 == null) return;
            writer.execute(() -> {
                try { Proto.json(o2, o); } catch (Exception e) { close(); }
            });
        }

        void sendAudio(byte[] b, int len) {
            try { Proto.write(out, Proto.AUDIO, b, 0, len); } catch (Exception e) { close(); }
        }

        void close() {
            try { sock.close(); } catch (Exception ignored) {}
        }

        @Override public void run() {
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(sock.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(sock.getOutputStream()));
                sock.setSoTimeout(10000);
                JSONObject hello = Proto.read(in).json();
                if (!"hello".equals(hello.optString("t")) || !pairCode().equals(hello.optString("code"))) {
                    Proto.json(out, Proto.msg("auth_fail"));
                    close();
                    return;
                }
                sock.setSoTimeout(90000); // B pings every 30 s when idle, 5 s in a call
                Client old = client;
                client = this;
                if (old != null) old.close();
                Proto.json(out, Proto.m("welcome", "callState", callState, "device", deviceName()));
                if (!callState.equals("idle")) {
                    send(callState.equals("ringing")
                            ? Proto.msg("ringing").put("number", callNumber).put("name", Data.nameFor(BridgeService.this, callNumber))
                            : callJson("active"));
                    if (callState.equals("active") && engineLink == null) startEngine();
                }
                main.post(() -> goForeground("Phone B connected"));

                while (true) {
                    Proto.Frame f = Proto.read(in);
                    if (f.type == Proto.AUDIO) {
                        EngineLink e = engineLink;
                        if (e != null) e.sendUp(f.data);
                    } else {
                        handle(f.json());
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "client gone: " + e);
            } finally {
                close();
                writer.shutdown();
                if (client == this) {
                    client = null;
                    stopEngine();
                    main.post(() -> goForeground("Waiting for phone B"));
                }
            }
        }

        void handle(JSONObject m) {
            String t = m.optString("t");
            switch (t) {
                case "ping": send(Proto.m("pong", "callState", callState)); break;
                case "dial": dial(m.optString("number")); break;
                case "answer": answer(); break;
                case "hangup": hangup(); break;
                case "beep": { EngineLink e = engineLink; if (e != null) e.beep(); break; }
                case "settings":
                    prefs.edit().putString("method", m.optString("method", "auto"))
                            .putBoolean("muteMic", m.optBoolean("muteMic", false)).apply();
                    break;
                case "restart_audio": startEngine(); break;
                case "resync":
                    if (callState.equals("ringing"))
                        send(Proto.m("ringing", "number", callNumber, "name", Data.nameFor(BridgeService.this, callNumber)));
                    else if (callState.equals("active")) {
                        send(callJson("active"));
                        if (engineLink == null) startEngine();
                    }
                    break;
                case "sms_send": worker.execute(() -> sendSms(m.optString("to"), m.optString("body"))); break;
                case "threads": worker.execute(() -> send(Data.threads(BridgeService.this))); break;
                case "thread": worker.execute(() -> send(Data.thread(BridgeService.this, m.optLong("id"), m.optString("address")))); break;
                case "contacts": worker.execute(() -> send(Data.contacts(BridgeService.this))); break;
                case "recents": worker.execute(() -> send(Data.recents(BridgeService.this))); break;
                case "status": worker.execute(() -> send(engineStatus())); break;
            }
        }
    }

    JSONObject engineStatus() {
        JSONObject o = Proto.msg("status");
        try {
            JSONObject pong = EngineLink.ping(engineToken());
            o.put("engine", pong != null);
            if (pong != null) o.put("interceptable", pong.optBoolean("interceptable"));
            o.put("callState", callState);
        } catch (Exception ignored) {}
        return o;
    }

    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, BridgeService.class));
    }

    /** Session with the privileged audio engine for the duration of one call. */
    static class EngineLink {
        final BridgeService svc;
        final String method;
        final boolean muteMic;
        volatile Socket sock;
        volatile DataOutputStream out;
        volatile boolean stopped;

        EngineLink(BridgeService svc, String method, boolean muteMic) {
            this.svc = svc; this.method = method; this.muteMic = muteMic;
        }

        void start() {
            new Thread(() -> {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Proto.ENGINE_PORT), 2000);
                    s.setTcpNoDelay(true);
                    sock = s;
                    if (stopped) { s.close(); return; }
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                    Proto.json(out, Proto.msg("start").put("token", svc.engineToken())
                            .put("method", method).put("muteMic", muteMic));
                    while (!stopped) {
                        Proto.Frame f = Proto.read(in);
                        if (f.type == Proto.AUDIO) svc.sendAudio(f.data, f.data.length);
                        else svc.send(f.json()); // engine status ("engine") goes straight to phone B
                    }
                } catch (java.net.ConnectException e) {
                    svc.send(Proto.m("engine", "error", "Audio engine not running on the host phone. Connect it by USB and run start-engine.sh"));
                } catch (Exception e) {
                    if (!stopped) svc.send(Proto.m("engine", "error", "audio link closed: " + e.getMessage()));
                }
            }, "engine-link").start();
        }

        void sendUp(byte[] pcm) {
            DataOutputStream o = out;
            if (o == null) return;
            try { Proto.write(o, Proto.AUDIO, pcm, 0, pcm.length); } catch (Exception ignored) {}
        }

        void beep() {
            DataOutputStream o = out;
            if (o == null) return;
            try { Proto.json(o, Proto.msg("beep")); } catch (Exception ignored) {}
        }

        void stop() {
            stopped = true;
            try { DataOutputStream o = out; if (o != null) Proto.json(o, Proto.msg("stop")); } catch (Exception ignored) {}
            try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignored) {}
        }

        /** Returns the engine's pong, or null if it is not running / token is wrong. */
        static JSONObject ping(String token) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), Proto.ENGINE_PORT), 1000);
                s.setSoTimeout(2000);
                DataOutputStream o = new DataOutputStream(s.getOutputStream());
                Proto.json(o, Proto.msg("ping").put("token", token));
                JSONObject r = Proto.read(new DataInputStream(s.getInputStream())).json();
                return "pong".equals(r.optString("t")) ? r : null;
            } catch (Exception e) {
                return null;
            }
        }
    }
}
