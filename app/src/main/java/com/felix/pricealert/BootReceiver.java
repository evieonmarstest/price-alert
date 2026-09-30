package com.felix.pricealert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts price monitoring after the phone reboots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Store.init(c);
        if (!Store.isEnabled()) return;
        try {
            c.startForegroundService(new Intent(c, PriceService.class));
        } catch (Exception ignored) {
        }
    }
}
