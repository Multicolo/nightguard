package com.nightguard.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机 / 更新后自动把守夜服务拉回来。 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Cfg.enabled(c)) return;
        Intent s = new Intent(c, GuardService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        } catch (Throwable ignored) {}
    }
}
