package com.nightguard.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 后台拉回白名单：拨号/紧急呼叫/系统 UI 硬编码，其余由用户勾选。 */
public final class Whitelist {
    private static final String K = "whitelist_pkgs";

    /** 这三类永远放行，用户不能删；另外补上通话界面/电话进程。 */
    private static final String[] HARD = {
            "com.android.dialer",
            "com.android.emergency",
            "com.android.systemui",
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.phone",
    };

    private Whitelist() {}

    public static final class AppEntry {
        public final String pkg;
        public final String label;
        public final boolean hard;

        public AppEntry(String pkg, String label, boolean hard) {
            this.pkg = pkg;
            this.label = label;
            this.hard = hard;
        }
    }

    public static boolean isHard(String pkg) {
        if (pkg == null) return false;
        for (String h : HARD) if (h.equals(pkg)) return true;
        return false;
    }

    private static String hardLabel(String pkg) {
        if (pkg.contains("dialer")) return "电话 / 拨号";
        if (pkg.contains("emergency")) return "紧急呼叫";
        if (pkg.contains("systemui")) return "系统界面";
        if (pkg.contains("incallui")) return "通话界面";
        if (pkg.contains("phone")) return "电话服务";
        return "系统必需";
    }

    private static Set<String> userSet(Context c) {
        return new HashSet<>(Cfg.sp(c).getStringSet(K, Collections.<String>emptySet()));
    }

    public static boolean isWhitelisted(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if (pkg.equals(c.getPackageName())) return true;
        if (isHard(pkg)) return true;
        return userSet(c).contains(pkg);
    }

    public static void set(Context c, String pkg, boolean on) {
        if (pkg == null || pkg.isEmpty() || isHard(pkg)) return;
        Set<String> s = userSet(c);
        if (on) s.add(pkg);
        else s.remove(pkg);
        Cfg.sp(c).edit().putStringSet(K, s).apply();
        Log.i("nightguard", "whitelist " + (on ? "add " : "remove ") + pkg);
    }

    public static int userCount(Context c) {
        return userSet(c).size();
    }

    /** 硬编码项 + 带启动图标的已安装应用，按名称排序，避免重复。 */
    public static List<AppEntry> entries(Context c) {
        List<AppEntry> out = new ArrayList<>();
        for (String h : HARD) out.add(new AppEntry(h, hardLabel(h), true));

        PackageManager pm = c.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = new ArrayList<>();
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            Log.e("nightguard", "query launcher apps failed", t);
        }
        Map<String, String> apps = new LinkedHashMap<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(c.getPackageName()) || isHard(pkg)) continue;
            String label;
            try {
                CharSequence cs = ri.loadLabel(pm);
                label = cs == null ? pkg : cs.toString();
            } catch (Throwable t) {
                label = pkg;
            }
            apps.put(pkg, label);
        }
        List<AppEntry> list = new ArrayList<>();
        for (Map.Entry<String, String> e : apps.entrySet()) {
            list.add(new AppEntry(e.getKey(), e.getValue(), false));
        }
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new java.util.Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                int c1 = collator.compare(a.label, b.label);
                return c1 != 0 ? c1 : a.pkg.compareTo(b.pkg);
            }
        });
        out.addAll(list);
        return out;
    }
}
