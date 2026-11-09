package com.nightguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

/** 前台服务：每 2 秒检查一次，到点就把守夜聊天框顶上去，且不让它被关掉。 */
public class GuardService extends Service {
    public static volatile boolean running = false;
    /** 设置页开着的时候不弹锁，否则用户永远进不去设置。 */
    public static volatile boolean settingsOpen = false;
    private static final String CH = "guard";
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try { step(); } catch (Throwable ignored) {}
            h.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startGuardForeground();
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    private void startGuardForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "守夜", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("守夜进行中");
            nm.createNotificationChannel(ch);
        }
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = new Notification.Builder(this, Build.VERSION.SDK_INT >= 26 ? CH : null)
                .setContentTitle(Cfg.persona(this) + "在守夜")
                .setContentText(Cfg.formatMin(Cfg.startMin(this)) + " – " + Cfg.formatMin(Cfg.endMin(this))
                        + " · 不许熬夜")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, 0);
        } else {
            startForeground(1, n);
        }
    }

    private void step() {
        if (!Cfg.enabled(this)) {
            stopSelf();
            return;
        }
        boolean in = Cfg.inWindow(this);
        long until = Cfg.releasedUntil(this);
        boolean released = until > System.currentTimeMillis();

        if (settingsOpen) return; // 设置页/图库里挑选背景时，别把人锁在外面

        if (!in || released) {
            if (LockActivity.alive()) LockActivity.closeNow();
            return;
        }
        if (Cfg.endedTonight(this) && LockActivity.alive()) return; // 已结束会话，保持锁住

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) return; // 息屏时不打扰，亮屏立刻锁

        if (!LockActivity.alive()) launch();
    }

    private void launch() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { startActivity(i); } catch (Throwable ignored) {}
    }

    @Override public void onDestroy() {
        running = false;
        h.removeCallbacks(tick);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
