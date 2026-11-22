package com.callbridge.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.callbridge.common.Proto;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps this phone connected to the host phone (the one with the SIM) over its hotspot, shows
 * call notifications, rings for incoming calls and runs call audio. UI screens subscribe as listeners.
 *
 * Battery: nothing polls while disconnected. With no Wi-Fi the connect loop sleeps until Android
 * reports a network; on a Wi-Fi without the host, retries back off to 60 s. While connected a small
 * heartbeat runs every 30 s (every 5 s during a call). No wake locks are held while idle.
 */
public class LinkService extends Service {
    static final String TAG = "CallBridgeB";

    static final long PING_IDLE_MS = 30_000;
    static final long PING_CALL_MS = 5_000;
    static final int READ_TIMEOUT_MS = 75_000;     // > 2 idle pings: a silent dead link is dropped
    static final long CALL_STALL_MS = 15_000;      // during a call, no data for this long = dead link
    static final long CALL_GRACE_MS = 30_000;      // keep a call on screen this long while reconnecting
    static final long BACKOFF_MAX_MS = 60_000;

    public interface Listener { void onEvent(JSONObject m); }

    static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    static volatile LinkService instance;
    static volatile String linkState = "connecting"; // connecting | connected | need_code | auth_fail | no_wifi
    static volatile String linkDetail = "";
    static volatile String hostName = "host phone";
    /** Current call or null: {number, name, state: ringing|dialing|active, incoming, since, link?: "lost"} */
    static volatile JSONObject call;
    static volatile JSONObject engineInfo;
    static volatile JSONObject engineStats; // {downFps, downDb, upFps} from the host engine every 2 s

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService writer = Executors.newSingleThreadExecutor();
    final Object wake = new Object();
    SharedPreferences prefs;
    ConnectivityManager cm;
    ConnectivityManager.NetworkCallback netCallback;
    volatile Socket sock;
    volatile Network sockNetwork;
    volatile DataOutputStream out;
    volatile boolean running = true;
    volatile long lastRx;
    volatile AudioLink audio;
    Ringtone ringtone;
    boolean micForeground;
    final Runnable graceExpired = this::onGraceExpired;

    // ------------------------------------------------------------------ lifecycle

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        hostName = prefs.getString("hostName", hostName);
        cm = getSystemService(ConnectivityManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("link", "Connection", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel calls = new NotificationChannel("calls", "Calls", NotificationManager.IMPORTANCE_HIGH);
        calls.setSound(null, null); // we ring ourselves so we can stop on answer
        nm.createNotificationChannel(calls);
        nm.createNotificationChannel(new NotificationChannel("sms", "Messages", NotificationManager.IMPORTANCE_HIGH));
        updateForeground();

        // Event-driven reconnects: react instantly to Wi-Fi coming and going instead of polling.
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network n) { wakeUp(); }
            @Override public void onLost(Network n) {
                if (n.equals(sockNetwork)) { Log.i(TAG, "Wi-Fi lost"); closeSock(); }
                wakeUp();
            }
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // hotspot may have no internet
                .build(), netCallback);

        new Thread(this::connectLoop, "link").start();
        new Thread(this::pingLoop, "ping").start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if ("decline".equals(a) || "hangup".equals(a)) hangup();
        if ("reconnect".equals(a)) reconnect();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        instance = null;
        try { cm.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
        closeSock();
        wakeUp();
        stopAudio();
        stopRinging();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    static void start(Context ctx) { ctx.startForegroundService(new Intent(ctx, LinkService.class)); }

    // ------------------------------------------------------------------ network

    void connectLoop() {
        long backoff = 2000;
        while (running) {
            String code = prefs.getString("code", "");
            if (code.isEmpty()) {
                setLink("need_code", "Enter the pairing code shown on the host phone");
                waitWake(0); // until settings are saved (reconnect() wakes us)
                continue;
            }
            Network wifi = wifiNetwork();
            String host = prefs.getString("host", "").trim();
            if (host.isEmpty()) host = gateway(wifi);
            if (wifi == null || host == null) {
                setLink("no_wifi", "Connect to the host phone's hotspot");
                waitWake(0); // sleep until Android reports a Wi-Fi network: zero battery while away
                backoff = 2000;
                continue;
            }
            setLink("connecting", "Connecting to " + host + "…");
            boolean welcomed = false;
            try {
                Socket s = wifi.getSocketFactory().createSocket();
                s.connect(new InetSocketAddress(host, Proto.BRIDGE_PORT), 4000);
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.setSoTimeout(READ_TIMEOUT_MS);
                sock = s;
                sockNetwork = wifi;
                lastRx = System.currentTimeMillis();
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                Proto.json(out, Proto.m("hello", "code", code));
                while (running) {
                    Proto.Frame f = Proto.read(in);
                    lastRx = System.currentTimeMillis();
                    if (f.type == Proto.AUDIO) {
                        AudioLink a = audio;
                        if (a != null) a.play(f.data);
                    } else {
                        JSONObject m = f.json();
                        if ("welcome".equals(m.optString("t"))) welcomed = true;
                        handle(m);
                    }
                }
            } catch (Exception e) {
                Log.i(TAG, "link error: " + e);
            } finally {
                closeSock();
            }
            if (!running) break;
            if (call != null) onLinkLostDuringCall();
            if ("auth_fail".equals(linkState)) { waitWake(BACKOFF_MAX_MS); continue; }
            setLink("connecting", "Reconnecting…");
            if (welcomed) backoff = 2000;                 // link was healthy: retry quickly
            waitWake(backoff);                            // a network change wakes us early
            if (!welcomed) backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
        }
    }

    void pingLoop() {
        while (running) {
            boolean inCall = call != null;
            waitWake(inCall ? PING_CALL_MS : PING_IDLE_MS); // dial() wakes us to switch to call rate
            if (!"connected".equals(linkState)) continue;
            if (inCall && System.currentTimeMillis() - lastRx > CALL_STALL_MS) {
                Log.i(TAG, "no data from host for " + CALL_STALL_MS + " ms during call, reconnecting");
                closeSock();
                continue;
            }
            send(Proto.m("ping"));
        }
    }

    void wakeUp() { synchronized (wake) { wake.notifyAll(); } }

    void waitWake(long ms) {
        synchronized (wake) {
            try { if (ms <= 0) wake.wait(); else wake.wait(ms); } catch (InterruptedException ignored) {}
        }
    }

    Network wifiNetwork() {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    /** The hotspot owner (the host phone) is the Wi-Fi default gateway. */
    String gateway(Network n) {
        if (n == null) return null;
        LinkProperties lp = cm.getLinkProperties(n);
        if (lp == null) return null;
        for (RouteInfo r : lp.getRoutes())
            if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) return r.getGateway().getHostAddress();
        return null;
    }

    /** Settings changed: drop the current link and try again now. */
    void reconnect() {
        closeSock();
        if ("auth_fail".equals(linkState) || "need_code".equals(linkState)) linkState = "connecting";
        wakeUp();
    }

    void closeSock() {
        Socket s = sock;
        sock = null;
        sockNetwork = null;
        out = null;
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    /** Queues a control message (safe to call from the UI thread). Returns false if not connected. */
    boolean send(JSONObject o) {
        DataOutputStream o2 = out;
        if (o2 == null) return false;
        writer.execute(() -> {
            try { Proto.json(o2, o); } catch (Exception e) { if (out == o2) closeSock(); }
        });
        return true;
    }

    void sendAudio(byte[] b, int len) {
        DataOutputStream o2 = out;
        if (o2 == null) return;
        try { Proto.write(o2, Proto.AUDIO, b, 0, len); } catch (Exception e) { if (out == o2) closeSock(); }
    }

    void setLink(String state, String detail) {
        boolean changed = !state.equals(linkState) || !detail.equals(linkDetail);
        linkState = state;
        linkDetail = detail;
        if (changed) { main.post(this::updateForeground); dispatch(Proto.m("link")); }
    }

    // ------------------------------------------------------------------ link loss during a call

    /**
     * The hotspot dropped mid-call. The call itself continues on the host phone, so keep it on
     * screen as "reconnecting" and resume when the link returns; give up after CALL_GRACE_MS.
     */
    void onLinkLostDuringCall() {
        JSONObject c = call;
        if (c == null || "lost".equals(c.optString("link"))) return;
        try { call = new JSONObject(c.toString()).put("link", "lost"); } catch (Exception e) { call = c; }
        Log.i(TAG, "link lost during call, waiting up to " + CALL_GRACE_MS + " ms");
        main.post(() -> {
            stopRinging();
            stopAudio(true); // keep the microphone foreground type so audio can resume in background
            updateForeground();
            main.removeCallbacks(graceExpired);
            main.postDelayed(graceExpired, CALL_GRACE_MS);
        });
        dispatch(Proto.m("link"));
    }

    void onGraceExpired() {
        JSONObject c = call;
        if (c != null && "lost".equals(c.optString("link"))) {
            Log.i(TAG, "could not reconnect in time, closing call screen");
            endLocalCall();
        }
    }

    void endLocalCall() {
        call = null;
        main.removeCallbacks(graceExpired);
        main.post(() -> { stopRinging(); stopAudio(false); updateForeground(); });
        dispatch(Proto.m("ended"));
    }

    // ------------------------------------------------------------------ messages from the host

    void handle(JSONObject m) {
        String t = m.optString("t");
        switch (t) {
            case "welcome":
                String dev = m.optString("device");
                if (!dev.isEmpty()) { hostName = dev; prefs.edit().putString("hostName", dev).apply(); }
                setLink("connected", "Connected to " + hostName);
                pushSettings();
                // The host resends any ringing/active call right after this; if it has none, clear ours.
                if ("idle".equals(m.optString("callState")) && call != null) endLocalCall();
                break;
            case "auth_fail":
                setLink("auth_fail", "Wrong pairing code");
                break;
            case "ringing": {
                JSONObject prev = call;
                boolean wasRinging = prev != null && "ringing".equals(prev.optString("state")) && !"lost".equals(prev.optString("link"));
                call = Proto.m("call", "number", m.optString("number"), "name", m.optString("name"),
                        "state", "ringing", "incoming", true);
                main.removeCallbacks(graceExpired);
                if (!wasRinging) { engineInfo = null; engineStats = null; }
                main.post(() -> { if (!wasRinging) startRinging(); updateForeground(); });
                break;
            }
            case "active": {
                long since = m.optLong("since");
                if (since <= 0) since = System.currentTimeMillis();
                JSONObject c = Proto.m("call", "number", m.optString("number"), "name", m.optString("name"),
                        "state", "active", "incoming", m.optBoolean("incoming"), "since", since);
                JSONObject prev = call;
                if (prev != null && m.optString("number").isEmpty()) {
                    try { c.put("number", prev.optString("number")).put("name", prev.optString("name")); } catch (Exception ignored) {}
                }
                call = c;
                main.removeCallbacks(graceExpired);
                main.post(() -> { stopRinging(); startAudio(); updateForeground(); });
                break;
            }
            case "ended":
                call = null;
                main.removeCallbacks(graceExpired);
                main.post(() -> { stopRinging(); stopAudio(false); updateForeground(); });
                break;
            case "engine":
                engineInfo = m;
                break;
            case "stats":
                engineStats = m;
                break;
            case "pong":
                reconcile(m.optString("callState"));
                break;
            case "error":
                main.post(() -> android.widget.Toast.makeText(this, m.optString("error"), android.widget.Toast.LENGTH_LONG).show());
                break;
            case "sms":
                main.post(() -> notifySms(m));
                break;
        }
        dispatch(m);
    }

    /**
     * The host is the source of truth. If our idea of the call disagrees with what it reports
     * (e.g. a missed "ended" message), fix it here. Gives a fresh outgoing call 15 s to connect.
     */
    void reconcile(String hostState) {
        JSONObject c = call;
        if ("idle".equals(hostState) && c != null) {
            boolean freshDial = "dialing".equals(c.optString("state"))
                    && System.currentTimeMillis() - c.optLong("created") < 15000;
            if (freshDial) return;
            Log.i(TAG, "reconcile: host is idle, ending local call");
            endLocalCall();
        } else if (("ringing".equals(hostState) || "active".equals(hostState)) && c == null) {
            send(Proto.m("resync")); // ask the host to resend the current call
        }
    }

    void dispatch(JSONObject m) {
        main.post(() -> { for (Listener l : listeners) l.onEvent(m); });
    }

    void pushSettings() {
        send(Proto.m("settings", "method", prefs.getString("method", "auto"), "muteMic", prefs.getBoolean("muteMic", false)));
    }

    // ------------------------------------------------------------------ call actions (from UI)

    void dial(String number) {
        call = Proto.m("call", "number", number, "name", "", "state", "dialing", "incoming", false,
                "created", System.currentTimeMillis());
        engineInfo = null;
        engineStats = null;
        enterMicForeground();
        send(Proto.m("dial", "number", number));
        updateForeground();
        wakeUp(); // switch the heartbeat to call rate
    }

    void answer() {
        enterMicForeground();
        stopRinging();
        send(Proto.m("answer"));
    }

    void hangup() {
        stopRinging();
        JSONObject c = call;
        if (c != null && "lost".equals(c.optString("link"))) { endLocalCall(); return; } // can't reach host
        send(Proto.m("hangup"));
    }

    /** Must be called while our UI is visible: lets the call keep the mic if the user leaves the screen. */
    void enterMicForeground() {
        micForeground = true;
        updateForeground();
    }

    // ------------------------------------------------------------------ audio / ringing

    void startAudio() {
        if (audio != null) return;
        try {
            audio = new AudioLink(this, this::sendAudio);
            audio.start();
        } catch (Exception e) {
            Log.e(TAG, "audio start failed", e);
            audio = null;
        }
    }

    void stopAudio() { stopAudio(false); }

    void stopAudio(boolean keepMicForeground) {
        AudioLink a = audio;
        audio = null;
        if (a != null) a.stop();
        if (!keepMicForeground) micForeground = false;
    }

    void startRinging() {
        stopRinging();
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) {
                ringtone.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build());
                ringtone.setLooping(true);
                ringtone.play();
            }
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createWaveform(new long[]{0, 800, 800}, 0));
        } catch (Exception e) {
            Log.e(TAG, "ring", e);
        }
    }

    void stopRinging() {
        if (ringtone != null) { try { ringtone.stop(); } catch (Exception ignored) {} ringtone = null; }
        Vibrator v = getSystemService(Vibrator.class);
        if (v != null) v.cancel();
    }

    // ------------------------------------------------------------------ notifications

    void updateForeground() {
        JSONObject c = call;
        Notification n;
        boolean wantMic = false;
        if (c != null) {
            String who = c.optString("name").isEmpty() ? c.optString("number") : c.optString("name");
            Person caller = new Person.Builder().setName(who.isEmpty() ? "Unknown" : who).setImportant(true).build();
            PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent hang = PendingIntent.getService(this, 2, new Intent(this, LinkService.class).setAction("hangup"),
                    PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(this, "calls")
                    .setSmallIcon(android.R.drawable.sym_call_incoming)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_CALL);
            if ("lost".equals(c.optString("link"))) b.setContentText("Reconnecting to " + hostName + "…");
            if ("ringing".equals(c.optString("state"))) {
                PendingIntent ans = PendingIntent.getActivity(this, 3, new Intent(this, CallActivity.class)
                        .setAction("answer").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
                PendingIntent dec = PendingIntent.getService(this, 4, new Intent(this, LinkService.class).setAction("decline"),
                        PendingIntent.FLAG_IMMUTABLE);
                b.setStyle(Notification.CallStyle.forIncomingCall(caller, dec, ans)).setFullScreenIntent(open, true);
            } else {
                b.setStyle(Notification.CallStyle.forOngoingCall(caller, hang));
                if (c.has("since")) b.setUsesChronometer(true).setWhen(c.optLong("since"));
            }
            n = b.build();
            wantMic = micForeground && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 0;
        } else {
            boolean ok = "connected".equals(linkState);
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
            n = new Notification.Builder(this, "link")
                    .setSmallIcon(ok ? android.R.drawable.stat_sys_phone_call : android.R.drawable.stat_notify_error)
                    .setContentTitle(ok ? "Connected to " + hostName : "Not connected")
                    .setContentText(ok ? "Ready for calls and messages" : linkDetail)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .build();
        }
        startForegroundCompat(n, wantMic);
    }

    void startForegroundCompat(Notification n, boolean mic) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
                if (mic) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(1, n, type);
            } else if (mic) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception e) {
            // microphone type is not allowed from the background: fall back without it
            Log.w(TAG, "startForeground: " + e);
            try {
                if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(1, n);
            } catch (Exception ignored) {}
        }
    }

    void notifySms(JSONObject m) {
        String addr = m.optString("address");
        if (addr.equals(MainActivity.openConversation)) return; // user is looking at it
        String who = m.optString("name").isEmpty() ? addr : m.optString("name");
        PendingIntent open = PendingIntent.getActivity(this, addr.hashCode(), new Intent(this, MainActivity.class)
                .putExtra("conversation", addr).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "sms")
                .setSmallIcon(android.R.drawable.sym_action_email)
                .setContentTitle(who)
                .setContentText(m.optString("body"))
                .setStyle(new Notification.BigTextStyle().bigText(m.optString("body")))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .build();
        getSystemService(NotificationManager.class).notify(addr.hashCode(), n);
    }

    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
}
