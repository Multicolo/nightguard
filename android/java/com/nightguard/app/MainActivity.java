package com.nightguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/** 设置页：服务器、口令、时间段、背景（可选）、权限、启停。 */
public class MainActivity extends Activity {
    private EditText etServer, etToken, etStart, etEnd;
    private CheckBox cbInsecure;
    private TextView status;
    private TextView permOverlay, permNotif, permBatt, permAuto;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(build());
        fill();
        askNotification();
    }

    @Override protected void onResume() {
        super.onResume();
        GuardService.settingsOpen = true;
        refreshStatus();
        refreshPerms();
    }

    @Override protected void onPause() {
        GuardService.settingsOpen = false;
        super.onPause();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xCCFFFFFF);
        t.setTextSize(13);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x66FFFFFF);
        e.setTextColor(0xFFFFFFFF);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private TextView permLine() {
        TextView t = new TextView(this);
        t.setTextSize(14);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(0, dp(8), 0, dp(8));
        return t;
    }

    private View build() {
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(0xFF0B0F14);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(24), dp(20), dp(32));
        sc.addView(col);

        TextView title = new TextView(this);
        title.setText("守夜人 · NightGuard");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("到点盖上一层关不掉的聊天框，把当前 App 压到后台（不杀进程）。\n"
                + "跟你的 AstrBot 角色求情，也许能求来几分钟——一晚只放一次。");
        sub.setTextColor(0x99FFFFFF);
        sub.setTextSize(13);
        sub.setPadding(0, dp(8), 0, 0);
        col.addView(sub);

        col.addView(label("服务器地址（你的 AstrBot 守夜后端）"));
        etServer = field("https://你的域名/night 或 http://192.168.1.10:6200");
        col.addView(etServer);

        col.addView(label("口令（后端插件里的 api_token）"));
        etToken = field("在 AstrBot 插件配置里查看/设置");
        col.addView(etToken);

        cbInsecure = new CheckBox(this);
        cbInsecure.setText("信任自签证书（自建服务器 / 直接连 IP 时勾上）");
        cbInsecure.setTextColor(0xCCFFFFFF);
        cbInsecure.setTextSize(13);
        cbInsecure.setPadding(0, dp(8), 0, 0);
        col.addView(cbInsecure);

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout c1 = new LinearLayout(this);
        c1.setOrientation(LinearLayout.VERTICAL);
        c1.addView(label("开始时间"));
        etStart = field("23:30");
        etStart.setFocusable(false);
        etStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(etStart); }
        });
        c1.addView(etStart);

        LinearLayout c2 = new LinearLayout(this);
        c2.setOrientation(LinearLayout.VERTICAL);
        c2.addView(label("结束时间"));
        etEnd = field("06:30");
        etEnd.setFocusable(false);
        etEnd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(etEnd); }
        });
        c2.addView(etEnd);

        times.addView(c1, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.leftMargin = dp(12);
        times.addView(c2, mp);
        col.addView(times);

        status = new TextView(this);
        status.setTextColor(0xFFFF9EC4);
        status.setTextSize(13);
        status.setPadding(0, dp(16), 0, 0);
        col.addView(status);

        Button save = button("保存并启动守夜");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(true); }
        });
        col.addView(save);

        Button stop = button("停止守夜");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cfg.sp(MainActivity.this).edit().putBoolean("enabled", false).apply();
                stopService(new Intent(MainActivity.this, GuardService.class));
                LockActivity.closeNow();
                refreshStatus();
            }
        });
        col.addView(stop);

        // ---------------------------------------------------------- 权限区
        TextView permTitle = new TextView(this);
        permTitle.setText("\n要「强控制」得开这些权限");
        permTitle.setTextColor(0xFFFFFFFF);
        permTitle.setTextSize(17);
        permTitle.setPadding(0, dp(22), 0, dp(4));
        col.addView(permTitle);

        TextView permWhy = new TextView(this);
        permWhy.setText("• 悬浮窗：必需。Android 只允许有这项权限的 App 在后台把全屏界面顶上来。\n"
                + "• 通知：Android 13+ 必需。前台服务要挂一条常驻通知，否则守夜会被系统掐掉。\n"
                + "• 电池优化白名单：推荐。不加的话息屏/省电模式可能把守夜服务冻结。\n"
                + "• 自启动 / 后台弹出：国产 ROM 专属，推荐。允许后，按 Home 或清理后台也不会被拦。");
        permWhy.setTextColor(0x99FFFFFF);
        permWhy.setTextSize(12);
        permWhy.setLineSpacing(dp(4), 1f);
        permWhy.setPadding(0, dp(4), 0, dp(6));
        col.addView(permWhy);

        permOverlay = permLine();
        permOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlay(); }
        });
        col.addView(permOverlay);

        permNotif = permLine();
        permNotif.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNotif(); }
        });
        col.addView(permNotif);

        permBatt = permLine();
        permBatt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBattery(); }
        });
        col.addView(permBatt);

        permAuto = permLine();
        permAuto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAutoStart(); }
        });
        col.addView(permAuto);

        Button bgBtn = button("选择聊天框背景图（可选）");
        bgBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickBg(); }
        });
        col.addView(bgBtn);

        Button test = button("测试连接");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(false); testConn(); }
        });
        col.addView(test);

        Button lockNow = button("立即锁一次（测试用）");
        lockNow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!Cfg.configured(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "先把服务器地址和口令填好", Toast.LENGTH_LONG).show();
                    return;
                }
                save(true);
                Cfg.sp(MainActivity.this).edit()
                        .putLong("releasedUntil", 0L)
                        .remove("endedNight")
                        .apply();
                startActivity(new Intent(MainActivity.this, LockActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        });
        col.addView(lockNow);

        TextView tip = new TextView(this);
        tip.setText("\n提示：守夜期间打电话、解锁都正常；要停只能回这个 App 点「停止守夜」，"
                + "或在锁屏界面长按左上角头像。");
        tip.setTextColor(0x77FFFFFF);
        tip.setTextSize(12);
        col.addView(tip);
        return sc;
    }

    private void fill() {
        etServer.setText(Cfg.server(this));
        etToken.setText(Cfg.token(this));
        etStart.setText(Cfg.formatMin(Cfg.startMin(this)));
        etEnd.setText(Cfg.formatMin(Cfg.endMin(this)));
        cbInsecure.setChecked(Cfg.insecureTls(this));
    }

    private void pick(final EditText target) {
        int cur = Cfg.parseMin(target.getText().toString(), 23 * 60 + 30);
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            @Override public void onTimeSet(TimePicker view, int hour, int minute) {
                target.setText(String.format(java.util.Locale.US, "%02d:%02d", hour, minute));
            }
        }, cur / 60, cur % 60, true).show();
    }

    private void save(boolean start) {
        String server = etServer.getText().toString().trim();
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        int s = Cfg.parseMin(etStart.getText().toString(), 23 * 60 + 30);
        int e = Cfg.parseMin(etEnd.getText().toString(), 6 * 60 + 30);
        Cfg.sp(this).edit()
                .putString("server", server)
                .putString("token", etToken.getText().toString().trim())
                .putBoolean("insecure_tls", cbInsecure.isChecked())
                .putInt("start", s)
                .putInt("end", e)
                .putBoolean("enabled", start)
                .apply();
        if (start) {
            if (server.isEmpty() || etToken.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "服务器地址或口令是空的，守夜连不上后端", Toast.LENGTH_LONG).show();
            }
            if (!hasOverlay()) {
                Toast.makeText(this, "还没给「悬浮窗」权限，到点会锁不住", Toast.LENGTH_LONG).show();
            }
            Intent i = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        }
        refreshStatus();
        Toast.makeText(this, start ? "守夜已开启" : "已保存", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 权限
    private boolean hasOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    private boolean hasNotif() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void askNotification() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotif()) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    private void openOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            Toast.makeText(this, "打不开权限页：设置 → 应用 → 特殊权限 → 显示在其他应用上层", Toast.LENGTH_LONG).show();
        }
    }

    private void openNotif() {
        if (Build.VERSION.SDK_INT >= 33) {
            askNotification();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } catch (Throwable t) {
            Toast.makeText(this, "设置 → 通知 → 找到「守夜人」打开", Toast.LENGTH_LONG).show();
        }
    }

    private void openBattery() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                Toast.makeText(this, "设置 → 电池 → 电池优化 → 找到「守夜人」选「不优化」", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** 国产 ROM 的自启动/后台管理页，没有就退回应用详情页。 */
    private void openAutoStart() {
        String[][] pages = new String[][]{
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        };
        for (String[] p : pages) {
            try {
                Intent i = new Intent();
                i.setClassName(p[0], p[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "这台手机没有统一入口：在「应用详情 → 自启动/后台管理」里手动允许", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "请到系统设置的「自启动管理」里允许「守夜人」", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshPerms() {
        if (permOverlay == null) return;
        permOverlay.setText((hasOverlay() ? "✅ " : "⚠️ ") + "悬浮窗（必需）—— " + (hasOverlay() ? "已开启" : "点这里开启"));
        permNotif.setText((hasNotif() ? "✅ " : "⚠️ ") + "通知（前台服务）—— " + (hasNotif() ? "已开启" : "点这里开启"));
        permBatt.setText((batteryOk() ? "✅ " : "○ ") + "电池优化白名单 —— " + (batteryOk() ? "已加入" : "点这里加入"));
        permAuto.setText("○ 自启动 / 后台弹出（国产 ROM）—— 点这里打开系统页面");
    }

    // ------------------------------------------------------------ 背景 / 连接
    private void pickBg() {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(i, 7);
        } catch (Throwable t) {
            Toast.makeText(this, "没有图库可用", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != 7 || res != RESULT_OK || data == null || data.getData() == null) return;
        try {
            InputStream is = getContentResolver().openInputStream(data.getData());
            File out = new File(getFilesDir(), "bg_custom.jpg");
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while (is != null && (n = is.read(buf)) > 0) fo.write(buf, 0, n);
            if (is != null) is.close();
            fo.close();
            Cfg.sp(this).edit().putString("bg", out.getAbsolutePath()).apply();
            Toast.makeText(this, "背景已换（下次进锁屏生效）", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void testConn() {
        status.setText("正在连接…");
        final String server = Cfg.server(this);
        if (server.isEmpty() || Cfg.token(this).isEmpty()) {
            status.setText("先把服务器地址和口令填好");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String msg;
                try {
                    Api.getJson(MainActivity.this, "/health");
                    JSONObject c = Api.getJson(MainActivity.this, "/config");
                    String persona = c.optString("persona_name", "");
                    if (!persona.isEmpty()) Cfg.setPersona(MainActivity.this, persona);
                    msg = "连接成功 · " + server + "\n角色：" + Cfg.persona(MainActivity.this)
                            + " · 今晚" + (c.optBoolean("granted") ? "已放行过" : "还没放行")
                            + (c.optBoolean("ended") ? " · 会话已结束" : "");
                } catch (Throwable t) {
                    msg = "连接失败：" + t.getMessage();
                }
                final String m = msg;
                ui.post(new Runnable() {
                    @Override public void run() {
                        status.setText(m);
                        new AlertDialog.Builder(MainActivity.this).setTitle("测试连接")
                                .setMessage(m).setPositiveButton("好", null).show();
                    }
                });
            }
        }).start();
    }

    private void refreshStatus() {
        boolean enabled = Cfg.enabled(this);
        boolean win = Cfg.inWindow(this);
        String s = (enabled ? "守夜进行中" : "守夜未开启")
                + " · " + (win ? "现在在时段内" : "现在不在时段内")
                + (Cfg.configured(this) ? "" : " · 还没配置服务器")
                + "\n今晚：" + (Cfg.grantedTonight(this) ? "已放行过一次" : "还没放行")
                + (Cfg.endedTonight(this) ? " · 会话已结束" : "");
        status.setText(s);
    }
}
