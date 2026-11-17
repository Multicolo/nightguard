package com.nightguard.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * M2 的快速拉回：只监听窗口变化并读取前台包名，
 * 不读取屏幕文字、不读取输入框内容（config 里 canRetrieveWindowContent=false）。
 *
 * 同时充当看门狗：GuardService 掉了就把它拉起来；用户用 PIN 停了今晚则不拉。
 */
public class NightGuardAccessibilityService extends AccessibilityService {
    public static volatile boolean connected = false;
    public static volatile String foregroundPackage = "";

    private static final long PULL_GAP_MS = 800L;
    private static final long WATCHDOG_MS = 5000L;

    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastPullAt;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            try {
                if (Cfg.enabled(NightGuardAccessibilityService.this)
                        && !Cfg.stoppedTonight(NightGuardAccessibilityService.this)
                        && !GuardService.running) {
                    Log.e("nightguard", "watchdog: GuardService not running, restarting");
                    Intent i = new Intent(NightGuardAccessibilityService.this, GuardService.class);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                    else startService(i);
                }
            } catch (Throwable t) {
                Log.e("nightguard", "watchdog restart failed", t);
            }
            h.postDelayed(this, WATCHDOG_MS);
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        AccessibilityConfig.markEverEnabled(this);
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            info.notificationTimeout = 100;
            setServiceInfo(info);
        }
        h.removeCallbacks(watchdog);
        h.post(watchdog);
        Log.i("nightguard", "accessibility service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence cs = event.getPackageName();
        if (cs == null) return;
        String pkg = cs.toString();
        foregroundPackage = pkg;
        if (pkg.equals(getPackageName())) return;
        if (!shouldPull()) return;
        if (Whitelist.isWhitelisted(this, pkg)) return;
        if (LockActivity.alive()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPullAt < PULL_GAP_MS) return;
        lastPullAt = now;
        Log.i("nightguard", "accessibility pull from " + pkg);
        pull();
    }

    private boolean shouldPull() {
        return Cfg.enabled(this)
                && !Cfg.stoppedTonight(this)
                && Cfg.inWindow(this)
                && Cfg.releasedUntil(this) <= Clock.now()
                && !Maintenance.active(this);
    }

    private void pull() {
        if (GuardService.requestLockNow()) return;
        try {
            Intent i = new Intent(this, LockActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Log.e("nightguard", "accessibility startActivity failed", t);
        }
        try {
            Intent s = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
            else startService(s);
        } catch (Throwable t) {
            Log.e("nightguard", "accessibility restart service failed", t);
        }
    }

    @Override public void onInterrupt() {
        Log.e("nightguard", "accessibility service interrupted");
    }

    @Override public boolean onUnbind(Intent intent) {
        connected = false;
        Log.e("nightguard", "accessibility service unbound/disabled");
        Notifier.degraded(this, "无障碍服务被关闭：快速拉回已失效，请回设置重新打开。");
        h.removeCallbacks(watchdog);
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        connected = false;
        h.removeCallbacks(watchdog);
        super.onDestroy();
    }
}
