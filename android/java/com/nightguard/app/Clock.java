package com.nightguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 统一时间源。M1 只做「接口与调用点就位」：
 * now() = 本机墙钟 + 服务器偏移，偏移默认 0，M5 再把 /health 的 time 字段接进来。
 *
 * 约定：
 *  - 判断时间窗、判断「今晚」、判断放行是否到期，都必须走这里，不直接读 System.currentTimeMillis()。
 *  - applyServerTime() 只保存「服务器时间 − 本机时间」，调用点固定后，M5 不需要再改业务代码。
 */
public final class Clock {
    private static final String P = "night_clock";
    private static final String K_OFFSET = "offset_ms";
    private static final String K_SERVER_AT = "server_at_ms";

    /** 与后端一致的换日点：凌晨 5 点前算前一天。 */
    private static final long NIGHT_ROLLOVER_MS = 5L * 3600_000L;

    private static volatile long offsetMs = 0L;
    private static volatile long serverAtMs = 0L;
    /** applyServerTime 时的服务器时刻与单调时钟读数；只在本次进程内有效，进程重启后退回墙钟+偏移。 */
    private static volatile long anchorServerMs = 0L;
    private static volatile long anchorElapsedMs = 0L;
    private static volatile boolean loaded = false;
    private static volatile Context appCtx;

    private Clock() {}

    /** 由 App.onCreate 调用一次。没有它也能用，只是偏移不落盘。 */
    public static void init(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        load();
    }

    private static void load() {
        if (loaded) return;
        Context c = appCtx;
        if (c == null) return;
        try {
            SharedPreferences sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
            offsetMs = sp.getLong(K_OFFSET, 0L);
            serverAtMs = sp.getLong(K_SERVER_AT, 0L);
            loaded = true;
        } catch (Throwable t) {
            Log.e("nightguard", "Clock.load failed", t);
        }
    }

    /**
     * 服务器时间（epoch ms）到手后调用：offset = 服务器 − 本机，并落盘。
     * M1 不联网，offset 保持 0；M5 由 /health 的 time 字段调用这里。
     */
    public static void applyServerTime(long serverEpochMs) {
        if (serverEpochMs <= 0) {
            Log.e("nightguard", "applyServerTime ignored invalid time: " + serverEpochMs);
            return;
        }
        long off = serverEpochMs - System.currentTimeMillis();
        offsetMs = off;
        serverAtMs = serverEpochMs;
        // 单调时钟锚点：拿到服务器时间后，即使本机墙钟被改，now() 也按 elapsedRealtime 推进。
        anchorServerMs = serverEpochMs;
        anchorElapsedMs = SystemClock.elapsedRealtime();
        loaded = true;
        Context c = appCtx;
        if (c != null) {
            try {
                c.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
                        .putLong(K_OFFSET, off)
                        .putLong(K_SERVER_AT, serverEpochMs)
                        .apply();
            } catch (Throwable t) {
                Log.e("nightguard", "Clock persist failed", t);
            }
        } else {
            Log.e("nightguard", "Clock.applyServerTime before init: offset kept in memory only");
        }
        Log.i("nightguard", "server clock applied: offset=" + off + "ms serverAt=" + serverEpochMs);
    }

    public static long offsetMs() {
        load();
        return offsetMs;
    }

    public static long serverAtMs() {
        load();
        return serverAtMs;
    }

    /**
     * 当前时间（epoch ms）。
     * M1 没有联网：offset = 0，等价于本机墙钟。
     * applyServerTime() 之后改用「服务器锚点 + 单调时钟」推算，改手机时间不再影响时间窗。
     */
    public static long now() {
        load();
        long server = anchorServerMs;
        long anchorElapsed = anchorElapsedMs;
        if (server > 0L && anchorElapsed > 0L) {
            long elapsed = SystemClock.elapsedRealtime();
            if (elapsed >= anchorElapsed) return server + (elapsed - anchorElapsed);
        }
        return System.currentTimeMillis() + offsetMs;
    }

    /** 当前本地分钟数（0..1439），用于比较守夜时间窗。 */
    public static int nowMin() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now());
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    /** 与后端一致的「今晚」标识：凌晨 5 点前算前一天。 */
    public static String nightKey() {
        long t = now() - NIGHT_ROLLOVER_MS;
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }
}
