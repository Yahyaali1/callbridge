package com.callbridge.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconnect to the host phone automatically after phone B restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        android.content.SharedPreferences p = ctx.getSharedPreferences("remote", Context.MODE_PRIVATE);
        if (!p.getString("code", "").isEmpty() || !p.getString("key", "").isEmpty())
            LinkService.start(ctx);
    }
}
