package com.nightguard.app;

import android.content.Context;
import android.util.Log;

/**
 * 维护模式：无障碍被关 / 连续上锁失败时暂停拉回 5 分钟，让用户能进设置修；
 * 每晚上限 2 次，第 3 次不再放行，改为持续报警。
 */
public final class Maintenance {
    private static final String K_UNTIL = "maint_until";
    private static final String K_COUNT = "maint_count";
    private static final String K_NIGHT = "maint_night";
    private static final String K_REASON = "maint_reason";

    public static final long WINDOW_MS = 5L * 60_000L;
    public static final int MAX_PER_NIGHT = 2;

    private Maintenance() {}

    /** 换晚自动清零。 */
    private static void sync(Context c) {
        String night = Cfg.nightKey();
        if (!night.equals(Cfg.sp(c).getString(K_NIGHT, ""))) {
            Cfg.sp(c).edit()
                    .putString(K_NIGHT, night)
                    .putInt(K_COUNT, 0)
                    .putLong(K_UNTIL, 0L)
                    .remove(K_REASON)
                    .apply();
            Log.i("nightguard", "maintenance counters reset for " + night);
        }
    }

    public static int count(Context c) {
        sync(c);
        return Cfg.sp(c).getInt(K_COUNT, 0);
    }

    public static boolean active(Context c) {
        sync(c);
        return Cfg.sp(c).getLong(K_UNTIL, 0L) > Clock.now();
    }

    public static long remainingMs(Context c) {
        sync(c);
        return Math.max(0L, Cfg.sp(c).getLong(K_UNTIL, 0L) - Clock.now());
    }

    public static String reason(Context c) {
        sync(c);
        return Cfg.sp(c).getString(K_REASON, "");
    }

    /** 成功进入维护模式返回 true；今晚次数已用完返回 false（调用方改为持续报警）。 */
    public static boolean enter(Context c, String reason) {
        sync(c);
        int n = Cfg.sp(c).getInt(K_COUNT, 0);
        if (n >= MAX_PER_NIGHT) {
            Log.e("nightguard", "maintenance limit reached tonight: " + n);
            return false;
        }
        long until = Clock.now() + WINDOW_MS;
        Cfg.sp(c).edit()
                .putLong(K_UNTIL, until)
                .putInt(K_COUNT, n + 1)
                .putString(K_NIGHT, Cfg.nightKey())
                .putString(K_REASON, reason)
                .apply();
        Log.e("nightguard", "maintenance entered: " + reason + " until=" + until);
        return true;
    }

    /** 只提前结束当前这次 5 分钟窗口，不清今晚次数。 */
    public static void clear(Context c) {
        Cfg.sp(c).edit().putLong(K_UNTIL, 0L).apply();
    }
}
