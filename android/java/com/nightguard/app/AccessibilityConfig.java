package com.nightguard.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/** 只读无障碍服务状态；不读屏幕内容。 */
public final class AccessibilityConfig {
    private AccessibilityConfig() {}

    public static ComponentName component(Context c) {
        return new ComponentName(c, NightGuardAccessibilityService.class);
    }

    /** 系统「已启用的无障碍服务」里是否有本 App 的 NightGuardAccessibilityService。 */
    public static boolean isEnabled(Context c) {
        try {
            String enabled = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) return false;
            String pkg = c.getPackageName();
            String cls = NightGuardAccessibilityService.class.getName();
            for (String raw : enabled.split(":")) {
                String s = raw == null ? "" : raw.trim();
                if (s.isEmpty()) continue;
                ComponentName cn = ComponentName.unflattenFromString(s);
                if (cn != null) {
                    String cp = cn.getPackageName();
                    String cc = cn.getClassName();
                    if (cc.startsWith(".")) cc = cp + cc;
                    if (pkg.equals(cp) && cls.equals(cc)) return true;
                }
                if (s.equals(pkg + "/" + cls)
                        || s.equals(pkg + "/." + cls.substring(pkg.length() + 1))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e("nightguard", "read accessibility services failed", t);
        }
        return false;
    }

    public static Intent settingsIntent(Context c) {
        return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
