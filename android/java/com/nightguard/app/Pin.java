package com.nightguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 锁屏里的紧急退出 PIN：4 位数字，只停「今晚」，不改时间窗、不删历史。
 * 本地只存加盐 SHA-256，不存明文；连续错 3 次冷却 5 分钟。
 */
public final class Pin {
    private static final String K_HASH = "pin_hash";
    private static final String K_SALT = "pin_salt";
    private static final String K_FAILS = "pin_fails";
    private static final String K_LOCK = "pin_lock_until";

    private static final int MAX_FAILS = 3;
    private static final long LOCK_MS = 5L * 60_000L;

    private Pin() {}

    public static boolean isSet(Context c) {
        return !Cfg.sp(c).getString(K_HASH, "").isEmpty();
    }

    public static void set(Context c, String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            Log.e("nightguard", "Pin.set rejected invalid pin");
            return;
        }
        byte[] salt = new byte[8];
        new SecureRandom().nextBytes(salt);
        String saltHex = hex(salt);
        Cfg.sp(c).edit()
                .putString(K_SALT, saltHex)
                .putString(K_HASH, hash(saltHex, pin))
                .putInt(K_FAILS, 0)
                .putLong(K_LOCK, 0L)
                .apply();
    }

    public static boolean verify(Context c, String pin) {
        SharedPreferences sp = Cfg.sp(c);
        String salt = sp.getString(K_SALT, "");
        String want = sp.getString(K_HASH, "");
        if (want.isEmpty() || pin == null) return false;
        String got = hash(salt, pin);
        return MessageDigest.isEqual(want.getBytes(), got.getBytes());
    }

    public static int fails(Context c) {
        return Cfg.sp(c).getInt(K_FAILS, 0);
    }

    public static long lockUntil(Context c) {
        return Cfg.sp(c).getLong(K_LOCK, 0L);
    }

    public static boolean locked(Context c) {
        return lockUntil(c) > Clock.now();
    }

    public static long lockRemainingMs(Context c) {
        return Math.max(0L, lockUntil(c) - Clock.now());
    }

    public static void fail(Context c) {
        SharedPreferences sp = Cfg.sp(c);
        int n = sp.getInt(K_FAILS, 0) + 1;
        SharedPreferences.Editor e = sp.edit();
        if (n >= MAX_FAILS) {
            e.putInt(K_FAILS, 0).putLong(K_LOCK, Clock.now() + LOCK_MS);
            Log.e("nightguard", "PIN failed 3 times; locked for 5 minutes");
        } else {
            e.putInt(K_FAILS, n);
        }
        e.apply();
    }

    public static void resetFails(Context c) {
        Cfg.sp(c).edit().putInt(K_FAILS, 0).putLong(K_LOCK, 0L).apply();
    }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((salt + ":" + pin).getBytes("UTF-8"));
            return hex(md.digest());
        } catch (Throwable t) {
            Log.e("nightguard", "PIN hash failed", t);
            return "";
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(java.util.Locale.US, "%02x", b));
        return sb.toString();
    }
}
