package com.callbridge.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconnect to the host phone automatically after phone B restarts or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (!ctx.getSharedPreferences("remote", Context.MODE_PRIVATE).getString("code", "").isEmpty())
            LinkService.start(ctx);
    }
}
