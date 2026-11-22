package com.callbridge.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Receivers {
    private Receivers() {}

    /** Starts the bridge service after boot / app update. */
    public static class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            BridgeService.start(ctx);
        }
    }

    /** Backup call-state source (system PHONE_STATE broadcast). Also carries the caller's number. */
    public static class CallState extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            String st = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_STATE);
            int code = android.telephony.TelephonyManager.EXTRA_STATE_RINGING.equals(st) ? android.telephony.TelephonyManager.CALL_STATE_RINGING
                    : android.telephony.TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st) ? android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
                    : android.telephony.TelephonyManager.CALL_STATE_IDLE;
            @SuppressWarnings("deprecation")
            String number = intent.getStringExtra(android.telephony.TelephonyManager.EXTRA_INCOMING_NUMBER);
            BridgeService svc = BridgeService.instance;
            if (svc != null) svc.onCallState(code, number);
            else try {
                ctx.startForegroundService(new Intent(ctx, BridgeService.class).putExtra("phone_state", code).putExtra("number", number));
            } catch (Exception ignored) {}
        }
    }

    /** Forwards incoming SMS to phone B. */
    public static class Sms extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent) {
            SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
            if (msgs == null) return;
            Map<String, StringBuilder> bodies = new LinkedHashMap<>();
            long date = System.currentTimeMillis();
            for (SmsMessage m : msgs) {
                String from = m.getDisplayOriginatingAddress();
                bodies.computeIfAbsent(from, k -> new StringBuilder()).append(m.getDisplayMessageBody());
                date = m.getTimestampMillis();
            }
            BridgeService svc = BridgeService.instance;
            if (svc == null) { BridgeService.start(ctx); return; }
            for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) svc.onSms(e.getKey(), e.getValue().toString(), date);
        }
    }
}
