package com.nightguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * 前台服务：每 2 秒 tick 一次，到点把守夜聊天框顶上去，并回读「真的顶上去了没有」。
 *
 * 上锁闭环：发射 → LockActivity 回写 lastAttachAt → 500ms 后回读 → 未命中重试 3 次（间隔 1s）
 * → 仍失败发高优先级通知，并把原因写进 SharedPreferences 供自检页展示。
 */
public class GuardService extends Service {
    public static volatile boolean running = false;
    /** 设置页开着的时候不弹锁，否则用户永远进不去设置；超过 10 分钟无条件复位，避免静态变量泄漏把锁永久关掉。 */
    public static volatile boolean settingsOpen = false;
    public static volatile long settingsOpenAt = 0L;

    /** 自检页读的观测值。 */
    public static volatile long tickCount = 0L;
    public static volatile long lastLockAt = 0L;
    public static volatile String lastFailReason = "";
    public static volatile long lastFailAt = 0L;

    private static final String CH = "guard";
    private static final String CH_READY = "guard_ready";
    private static final String CH_FAIL = "guard_fail";
    private static final int ID_FOREGROUND = 1;
    private static final int ID_READY = 2;
    private static final int ID_FAIL = 3;

    private static final int MAX_LOCK_ATTEMPTS = 3;
    private static final long ATTACH_WAIT_MS = 500L;
    private static final long RETRY_GAP_MS = 1000L;
    /** 一整轮失败后的冷却，避免每 2 秒重开一轮、整晚刷通知。 */
    private static final long ROUND_COOLDOWN_MS = 60_000L;
    /** 同一个失败原因最多 5 分钟提醒一次；第一次失败一定立刻提醒。 */
    private static final long FAIL_NOTIFY_GAP_MS = 5L * 60_000L;
    private static final long SETTINGS_STALE_MS = 10L * 60_000L;
    /** 22:00 之后发「今晚开始守夜」。不用 AlarmManager：服务死了就没有这条通知，本身就是信号。 */
    private static final int READY_FROM_MIN = 22 * 60;
    private static final String K_READY_NIGHT = "readyNotifiedNight";
    private static final String K_FAIL_REASON = "lastFailReason";
    private static final String K_FAIL_AT = "lastFailAt";
    private static final String K_LAST_LOCK = "lastLockAt";

    private final Handler h = new Handler(Looper.getMainLooper());

    private boolean roundActive;
    private int roundAttempt;
    private String roundReason;
    private long nextRoundAt;
    private long lastFailNotifyAt;
    private String lastNotifiedReason = "";

    private boolean lastInteractive = true;
    private boolean interactiveKnown = false;
    private long screenOffAt = 0L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                step();
            } catch (Throwable t) {
                Log.e("nightguard", "tick failed", t);
            }
            h.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        running = true;
        loadFailState();
        createChannels();
        try {
            IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
            screen.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenReceiver, screen);
        } catch (Throwable t) {
            Log.e("nightguard", "register screen receiver failed", t);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        running = true;
        try {
            startGuardForeground();
        } catch (Throwable t) {
            // 没有 try/catch 时这里会反复崩；现在记录原因、报一次警，然后主动停掉，别进崩溃循环。
            Log.e("nightguard", "startForeground threw", t);
            String reason = "START_FOREGROUND_THREW:" + brief(t);
            recordFail(reason);
            alarmFail(reason);
            stopSelf();
            return START_NOT_STICKY;
        }
        h.removeCallbacks(tick);
        h.post(tick);
        return START_STICKY;
    }

    /** 设置页开/关都走这里，顺便记下时间，防止 onPause 没跑到导致 settingsOpen 永久为 true。 */
    public static void setSettingsOpen(boolean open) {
        settingsOpen = open;
        settingsOpenAt = open ? SystemClock.elapsedRealtime() : 0L;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.e("nightguard", "NotificationManager unavailable; channels not created");
            return;
        }
        NotificationChannel guard = new NotificationChannel(CH, "守夜", NotificationManager.IMPORTANCE_LOW);
        guard.setDescription("守夜进行中");
        NotificationChannel ready = new NotificationChannel(CH_READY, "守夜提醒", NotificationManager.IMPORTANCE_DEFAULT);
        ready.setDescription("今晚开始守夜的提醒");
        NotificationChannel fail = new NotificationChannel(CH_FAIL, "守夜失败", NotificationManager.IMPORTANCE_HIGH);
        fail.setDescription("到点没能把锁屏顶上来时的报警");
        fail.setShowBadge(true);
        nm.createNotificationChannel(guard);
        nm.createNotificationChannel(ready);
        nm.createNotificationChannel(fail);
    }

    private Notification.Builder newNotification(String channel) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, channel);
        } else {
            // 两个参数的构造函数是 API 26 才有的，低版本必须走这里，否则就是 NoSuchMethodError。
            b = new Notification.Builder(this);
            b.setPriority(CH_FAIL.equals(channel)
                    ? Notification.PRIORITY_HIGH
                    : (CH_READY.equals(channel) ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_LOW));
            if (CH_FAIL.equals(channel)) b.setDefaults(Notification.DEFAULT_ALL);
        }
        return b;
    }

    private PendingIntent pendingMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildForeground() {
        return newNotification(CH)
                .setContentTitle(Cfg.persona(this) + "在守夜")
                .setContentText(Cfg.formatMin(Cfg.startMin(this)) + " – " + Cfg.formatMin(Cfg.endMin(this))
                        + " · 不许熬夜")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentIntent(pendingMain())
                .setOngoing(true)
                .build();
    }

    private void startGuardForeground() {
        Notification n = buildForeground();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_FOREGROUND, n, 0);
        } else {
            startForeground(ID_FOREGROUND, n);
        }
    }

    private void step() {
        tickCount++;
        if (tickCount % 30 == 0L) {
            // 每分钟一条心跳日志，方便 adb logcat 证明「服务活着、tick 在涨」。
            Log.i("nightguard", "tick " + tickCount);
        }
        if (!Cfg.enabled(this)) {
            cancelLockRound();
            stopSelf();
            return;
        }

        maybeNotifyTonight();

        if (settingsOpen) {
            long since = settingsOpenAt;
            if (since <= 0) {
                // 兜底：静态变量被别处置 true 却没记时间，从这里开始计时。
                since = SystemClock.elapsedRealtime();
                settingsOpenAt = since;
            }
            long held = SystemClock.elapsedRealtime() - since;
            if (held < SETTINGS_STALE_MS) return;
            Log.e("nightguard", "settingsOpen stuck for " + (held / 1000) + "s; force reset");
            settingsOpen = false;
            settingsOpenAt = 0L;
        }

        boolean in = Cfg.inWindow(this);
        boolean released = Cfg.releasedUntil(this) > Clock.now();
        if (!in || released) {
            cancelLockRound();
            if (LockActivity.alive()) LockActivity.closeNow();
            return;
        }
        if (Cfg.endedTonight(this) && LockActivity.alive()) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean interactive = pm == null || pm.isInteractive();
        if (!interactive) {
            if (screenOffAt == 0L) screenOffAt = SystemClock.elapsedRealtime();
            lastInteractive = false;
            interactiveKnown = true;
            return;
        }
        boolean justWoke = interactiveKnown && !lastInteractive;
        lastInteractive = true;
        interactiveKnown = true;
        if (justWoke) {
            // 息屏回来立刻锁，不等下一个 tick。
            long offMs = screenOffAt == 0L ? 0L : SystemClock.elapsedRealtime() - screenOffAt;
            screenOffAt = 0L;
            nextRoundAt = 0L;
            Log.i("nightguard", "screen on after " + (offMs / 1000) + "s off; lock immediately");
            if (!LockActivity.alive()) startLockRound();
            return;
        }

        if (!LockActivity.alive()) startLockRound();
    }

    /** 22:00 之后、今晚还没发过 → 一条普通通知。只依赖服务自己活着，服务死了就没有这条通知。 */
    private void maybeNotifyTonight() {
        if (Clock.nowMin() < READY_FROM_MIN) return;
        String night = Cfg.nightKey();
        if (night.equals(Cfg.sp(this).getString(K_READY_NIGHT, ""))) return;
        String text = "今晚 " + Cfg.formatMin(Cfg.startMin(this)) + " 开始守夜";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.e("nightguard", "ready notification skipped: no NotificationManager");
            return;
        }
        try {
            Notification n = newNotification(CH_READY)
                    .setContentTitle("守夜已就绪")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentIntent(pendingMain())
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID_READY, n);
            Cfg.sp(this).edit().putString(K_READY_NIGHT, night).apply();
            Log.i("nightguard", "ready notification sent for " + night + ": " + text);
        } catch (Throwable t) {
            Log.e("nightguard", "ready notification failed", t);
        }
    }

    private void startLockRound() {
        if (roundActive) return;
        if (!Cfg.enabled(this) || !Cfg.inWindow(this)) return;
        if (Cfg.releasedUntil(this) > Clock.now()) return;
        if (LockActivity.alive()) {
            lastLockAt = Clock.now();
            return;
        }
        if (SystemClock.elapsedRealtime() < nextRoundAt) return;
        roundActive = true;
        roundAttempt = 0;
        roundReason = null;
        Log.i("nightguard", "lock round start");
        runLockAttempt();
    }

    private void runLockAttempt() {
        if (!roundActive) return;
        roundAttempt++;
        final long startedAt = SystemClock.elapsedRealtime();
        String why = launch();
        // 每次都覆盖成「本次」的原因：上一次的失败不该冒充这一次的。
        roundReason = why;
        if (why != null) {
            Log.e("nightguard", "lock attempt " + roundAttempt + "/" + MAX_LOCK_ATTEMPTS + " dispatch failed: " + why);
        } else {
            Log.i("nightguard", "lock attempt " + roundAttempt + "/" + MAX_LOCK_ATTEMPTS + " dispatched");
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                checkAttach(startedAt);
            }
        }, ATTACH_WAIT_MS);
    }

    /** 500ms 后回读：Activity 有没有把 lastAttachAt 推到本次发起时刻之后（活着也算打中）。 */
    private void checkAttach(long startedAt) {
        if (!roundActive) return;
        boolean heartbeat = LockActivity.lastAttachAt >= startedAt;
        if (heartbeat || LockActivity.alive()) {
            roundActive = false;
            nextRoundAt = 0L;
            lastLockAt = Clock.now();
            Cfg.sp(this).edit().putLong(K_LAST_LOCK, lastLockAt).apply();
            Log.i("nightguard", "lock attached on attempt " + roundAttempt
                    + (heartbeat ? " (heartbeat)" : " (activity alive)"));
            return;
        }
        if (roundReason == null) roundReason = "ATTACH_TIMEOUT";
        if (roundAttempt < MAX_LOCK_ATTEMPTS) {
            Log.e("nightguard", "lock attempt " + roundAttempt + " not attached (" + roundReason
                    + "); retry in " + RETRY_GAP_MS + "ms");
            h.postDelayed(retryRunnable, RETRY_GAP_MS);
            return;
        }
        roundActive = false;
        nextRoundAt = SystemClock.elapsedRealtime() + ROUND_COOLDOWN_MS;
        String reason = roundReason != null ? roundReason : "ATTACH_TIMEOUT";
        recordFail(reason);
        alarmFail(reason);
    }

    private final Runnable retryRunnable = new Runnable() {
        @Override public void run() {
            if (!roundActive) return;
            if (!Cfg.enabled(GuardService.this) || !Cfg.inWindow(GuardService.this)
                    || Cfg.releasedUntil(GuardService.this) > Clock.now()) {
                Log.e("nightguard", "lock retry aborted: guard off, window closed or released");
                cancelLockRound();
                return;
            }
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                // 息屏时不打扰：不靠重试把屏幕点亮，等亮屏事件再锁。
                Log.i("nightguard", "lock retry deferred: screen off");
                screenOffAt = SystemClock.elapsedRealtime();
                lastInteractive = false;
                interactiveKnown = true;
                cancelLockRound();
                return;
            }
            runLockAttempt();
        }
    };

    private void cancelLockRound() {
        if (!roundActive) return;
        roundActive = false;
        h.removeCallbacks(retryRunnable);
        Log.i("nightguard", "lock round cancelled");
    }

    /** 失败原因：NO_OVERLAY / START_ACTIVITY_THREW:<msg>；null 表示已经发出去了。 */
    private String launch() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Log.e("nightguard", "launch failed: SYSTEM_ALERT_WINDOW not granted");
            return "NO_OVERLAY";
        }
        Intent i = new Intent(this, LockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Throwable t) {
            Log.e("nightguard", "startActivity(LockActivity) threw", t);
            return "START_ACTIVITY_THREW:" + brief(t);
        }
        return null;
    }

    private void recordFail(String reason) {
        lastFailReason = reason;
        lastFailAt = Clock.now();
        Cfg.sp(this).edit()
                .putString(K_FAIL_REASON, reason)
                .putLong(K_FAIL_AT, lastFailAt)
                .apply();
        Log.e("nightguard", "lock FAILED: " + reason);
    }

    private void alarmFail(String reason) {
        long nowElapsed = SystemClock.elapsedRealtime();
        if (reason.equals(lastNotifiedReason) && nowElapsed - lastFailNotifyAt < FAIL_NOTIFY_GAP_MS) {
            Log.e("nightguard", "fail alarm throttled (same reason within 5min): " + reason);
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            Log.e("nightguard", "cannot alarm lock failure: no NotificationManager");
            return;
        }
        String human = humanReason(reason);
        String title = "今晚守夜失败：" + shortReason(reason);
        if (!nm.areNotificationsEnabled()) {
            Log.e("nightguard", "fail alarm cannot be seen: notifications disabled in system settings");
        }
        try {
            Notification n = newNotification(CH_FAIL)
                    .setContentTitle(title)
                    .setContentText(human)
                    .setStyle(new Notification.BigTextStyle().bigText("今晚守夜失败：" + human))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(pendingMain())
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_ERROR)
                    .build();
            nm.notify(ID_FAIL, n);
            lastFailNotifyAt = nowElapsed;
            lastNotifiedReason = reason;
            Log.e("nightguard", "fail alarm shown: " + reason);
        } catch (Throwable t) {
            Log.e("nightguard", "fail alarm notify threw", t);
        }
    }

    private String humanReason(String reason) {
        if (reason == null || reason.isEmpty()) return "未知原因";
        if (reason.equals("NO_OVERLAY")) return "NO_OVERLAY（没有悬浮窗权限）";
        if (reason.equals("ATTACH_TIMEOUT")) return "ATTACH_TIMEOUT（界面没有回写心跳）";
        if (reason.startsWith("START_ACTIVITY_THREW")) return reason + "（无法拉起锁屏界面）";
        if (reason.startsWith("START_FOREGROUND_THREW")) return reason + "（前台服务启动失败）";
        return reason;
    }

    private String shortReason(String reason) {
        if (reason == null) return "未知原因";
        return reason.length() > 60 ? reason.substring(0, 60) : reason;
    }

    private void loadFailState() {
        try {
            lastFailReason = Cfg.sp(this).getString(K_FAIL_REASON, "");
            lastFailAt = Cfg.sp(this).getLong(K_FAIL_AT, 0L);
            lastLockAt = Cfg.sp(this).getLong(K_LAST_LOCK, 0L);
        } catch (Throwable t) {
            Log.e("nightguard", "loadFailState failed", t);
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ');
        if (m.length() > 120) m = m.substring(0, 120);
        return m;
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // 精确记下息屏时刻；Doze 冻结 tick 也不影响这里。
                screenOffAt = SystemClock.elapsedRealtime();
                lastInteractive = false;
                interactiveKnown = true;
                Log.i("nightguard", "ACTION_SCREEN_OFF");
                return;
            }
            long offMs = screenOffAt == 0L ? 0L : SystemClock.elapsedRealtime() - screenOffAt;
            screenOffAt = 0L;
            Log.i("nightguard", "ACTION_SCREEN_ON after " + (offMs / 1000) + "s off; lock now");
            lastInteractive = true;
            interactiveKnown = true;
            // 用户主动亮屏是一次合理的重试时机，清掉失败冷却。
            nextRoundAt = 0L;
            h.removeCallbacks(onScreenOn);
            h.post(onScreenOn);
        }
    };

    private final Runnable onScreenOn = new Runnable() {
        @Override public void run() {
            if (!Cfg.enabled(GuardService.this)) return;
            if (!Cfg.inWindow(GuardService.this)) return;
            if (Cfg.releasedUntil(GuardService.this) > Clock.now()) return;
            if (LockActivity.alive()) return;
            startLockRound();
        }
    };

    @Override public void onDestroy() {
        running = false;
        roundActive = false;
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Throwable t) {
            Log.e("nightguard", "unregister screen receiver failed", t);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
