package com.nightguard.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** 所有本地配置与今夜状态。分享版不含任何服务器/口令默认值，全部由用户自己填。 */
public final class Cfg {
    private static final String P = "night";

    /** 没有默认服务器：第一次打开必须自己填（例如 http://192.168.1.10:6200 或 https://你的域名/night）。 */
    public static final String DEFAULT_SERVER = "";
    public static final String DEFAULT_TOKEN = "";
    /** 后端 /config 拿不到角色名时的占位。 */
    public static final String DEFAULT_PERSONA = "守夜人";

    private Cfg() {}

    public static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    public static String server(Context c) {
        String s = sp(c).getString("server", DEFAULT_SERVER);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    public static String token(Context c) {
        return sp(c).getString("token", DEFAULT_TOKEN);
    }

    /** 服务器和口令都填了才算配置完成。 */
    public static boolean configured(Context c) {
        return !server(c).isEmpty() && !token(c).isEmpty();
    }

    public static String persona(Context c) {
        String s = sp(c).getString("persona", DEFAULT_PERSONA);
        return s == null || s.trim().isEmpty() ? DEFAULT_PERSONA : s.trim();
    }

    public static void setPersona(Context c, String name) {
        if (name == null || name.trim().isEmpty()) return;
        sp(c).edit().putString("persona", name.trim()).apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    /** 勾了才放行自签/域名不匹配的 HTTPS 证书。 */
    public static boolean insecureTls(Context c) {
        return sp(c).getBoolean("insecure_tls", false);
    }

    public static int startMin(Context c) { return sp(c).getInt("start", 23 * 60 + 30); }
    public static int endMin(Context c)   { return sp(c).getInt("end", 6 * 60 + 30); }

    public static String formatMin(int m) {
        m = ((m % 1440) + 1440) % 1440;
        return String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
    }

    public static int parseMin(String s, int def) {
        try {
            String[] p = s.trim().split(":");
            int h = Integer.parseInt(p[0].trim());
            int mi = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            if (h < 0 || h > 23 || mi < 0 || mi > 59) return def;
            return h * 60 + mi;
        } catch (Exception e) {
            return def;
        }
    }

    /** 当前分钟数。 */
    public static int nowMin() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    public static boolean inWindow(Context c) {
        int n = nowMin(), s = startMin(c), e = endMin(c);
        if (s == e) return false;
        if (s < e) return n >= s && n < e;
        return n >= s || n < e; // 跨零点
    }

    /** 与后端一致的「今晚」标识：凌晨 5 点前算前一天。 */
    public static String nightKey() {
        long t = System.currentTimeMillis() - 5L * 3600_000L;
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(t));
    }

    public static long releasedUntil(Context c) { return sp(c).getLong("releasedUntil", 0L); }
    public static String releasedNight(Context c) { return sp(c).getString("releasedNight", ""); }
    public static boolean grantedTonight(Context c) { return nightKey().equals(releasedNight(c)); }
    public static boolean endedTonight(Context c) { return nightKey().equals(sp(c).getString("endedNight", "")); }

    public static String bgPath(Context c) { return sp(c).getString("bg", ""); }
    public static String history(Context c) { return sp(c).getString("history", "[]"); }
}
