package com.nightguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/** 降级 / 维护模式的通知。GuardService 死亡时无障碍服务也能直接报警。 */
public final class Notifier {
    public static final String CH_GUARD = "guard";
    public static final String CH_READY = "guard_ready";
    public static final String CH_FAIL = "guard_fail";
    public static final String CH_DEGRADED = "guard_degraded";

    private static final int ID_DEGRADED = 4;
    private static final int ID_MAINT = 5;
    private static final long GAP_MS = 5L * 60_000L;

    private static long lastDegradedAt;
    private static long lastMaintAt;

    private Notifier() {}

    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel guard = new NotificationChannel(CH_GUARD, "守夜", NotificationManager.IMPORTANCE_LOW);
        guard.setDescription("守夜进行中");
        NotificationChannel ready = new NotificationChannel(CH_READY, "守夜提醒", NotificationManager.IMPORTANCE_DEFAULT);
        ready.setDescription("今晚开始守夜的提醒");
        NotificationChannel fail = new NotificationChannel(CH_FAIL, "守夜失败", NotificationManager.IMPORTANCE_HIGH);
        fail.setDescription("到点没锁住、无障碍被关、维护模式报警");
        fail.setShowBadge(true);
        NotificationChannel degraded = new NotificationChannel(CH_DEGRADED, "守夜降级", NotificationManager.IMPORTANCE_HIGH);
        degraded.setDescription("无障碍服务被关闭时的报警");
        degraded.setShowBadge(true);
        nm.createNotificationChannel(guard);
        nm.createNotificationChannel(ready);
        nm.createNotificationChannel(fail);
        nm.createNotificationChannel(degraded);
    }

    public static void degraded(Context c, String text) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastDegradedAt < GAP_MS) {
            Log.e("nightguard", "degraded alarm throttled: " + text);
            return;
        }
        lastDegradedAt = now;
        show(c, CH_DEGRADED, ID_DEGRADED, "守夜已降级", text, accessibilityPending(c));
    }

    public static void maintenance(Context c, String text) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastMaintAt < GAP_MS) {
            Log.e("nightguard", "maintenance alarm throttled: " + text);
            return;
        }
        lastMaintAt = now;
        show(c, CH_FAIL, ID_MAINT, "守夜维护模式", text, accessibilityPending(c));
    }

    private static void show(Context c, String channel, int id, String title, String text,
                             PendingIntent intent) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                Log.e("nightguard", "notify skipped, no NotificationManager: " + title);
                return;
            }
            if (!nm.areNotificationsEnabled()) {
                Log.e("nightguard", "notify skipped, notifications disabled: " + title);
            }
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(c, channel);
            } else {
                b = new Notification.Builder(c);
                b.setPriority(Notification.PRIORITY_HIGH);
            }
            b.setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ERROR);
            nm.notify(id, b.build());
            Log.e("nightguard", "notification: " + title + " / " + text);
        } catch (Throwable t) {
            Log.e("nightguard", "notification threw: " + title, t);
        }
    }

    private static PendingIntent accessibilityPending(Context c) {
        Intent i = AccessibilityConfig.settingsIntent(c);
        return PendingIntent.getActivity(c, 21, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
