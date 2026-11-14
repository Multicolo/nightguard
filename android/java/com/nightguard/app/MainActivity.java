package com.nightguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
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

/**
 * 设置页：连接、时间段、权限、开始。
 * 手机竖屏是单列卡片流；平板/横屏自动切成左右两栏。
 */
public class MainActivity extends Activity {
    private EditText etServer, etToken, etStart, etEnd;
    private CheckBox cbInsecure;
    private TextView statusTitle, statusSub, statusDot;
    private LinearLayout statusCard;
    private View permOverlay, permNotif, permBatt, permAuto;
    private TextView permOverlayMark, permNotifMark, permBattMark, permAutoMark;
    private TextView bgNote;
    private TextView selfBody;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean wide;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        wide = Ui.isWide(this);
        setContentView(build());
        fill();
        askNotification();
    }

    @Override protected void onResume() {
        super.onResume();
        GuardService.setSettingsOpen(true);
        refreshStatus();
        refreshPerms();
        refreshSelfCheck();
        ui.removeCallbacks(selfTick);
        ui.post(selfTick);
    }

    @Override protected void onPause() {
        GuardService.setSettingsOpen(false);
        ui.removeCallbacks(selfTick);
        super.onPause();
    }

    // ------------------------------------------------------------------ 基础控件
    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 15, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.roundStroke(this, 0xFF131922, 20, 0x14FFFFFF, 0.8f));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    private TextView small(String s) {
        TextView t = text(s, 12, Ui.TEXT_FAINT);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(0x59FFFFFF);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setBackground(Ui.round(this, 0xFF0B0F16, 12));
        e.setPadding(dp(12), dp(11), dp(12), dp(11));
        return e;
    }

    private TextView primary(String label) {
        TextView t = text(label, 16, 0xFFFFFFFF);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rippleGradient(this, 0xFFFF8DB4, Ui.ACCENT_DARK, 14));
        t.setPadding(0, dp(14), 0, dp(14));
        return t;
    }

    private TextView secondary(String label) {
        TextView t = text(label, 14, Ui.TEXT);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.rippleRoundStroke(this, 0x00000000, 14, 0x33FFFFFF));
        t.setPadding(0, dp(12), 0, dp(12));
        return t;
    }

    private View gap(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x14FFFFFF);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f)));
        p.topMargin = dp(12);
        p.bottomMargin = dp(4);
        v.setLayoutParams(p);
        return v;
    }

    // ------------------------------------------------------------------ 整页
    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF101725, 0xFF070A10}));
        page.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(0, 0, 0, insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));

        View fade = new View(this);
        fade.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0C1119, 0x000C1119}));
        page.addView(fade, new LinearLayout.LayoutParams(-1, dp(14)));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int pad = dp(wide ? 24 : 16);
        int side = pad;
        if (wide) {
            // 平板/折叠屏上把内容收在中间，避免一行字拉得太长。
            int extra = (getResources().getDisplayMetrics().widthPixels - dp(1180)) / 2;
            if (extra > pad) side = extra;
        }
        body.setPadding(side, dp(4), side, dp(8));
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (wide) {
            ScrollView left = scrollColumn();
            ScrollView right = scrollColumn();
            LinearLayout l = (LinearLayout) left.getChildAt(0);
            LinearLayout r = (LinearLayout) right.getChildAt(0);
            l.addView(connectionCard());
            l.addView(gap(14));
            l.addView(windowCard());
            l.addView(gap(14));
            l.addView(statusCardView());
            l.addView(gap(14));
            l.addView(selfCheckCard());
            r.addView(permissionCard());
            r.addView(gap(14));
            r.addView(actionCard());
            r.addView(gap(14));
            r.addView(aboutCard());
            body.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, -1, 1f);
            rp.leftMargin = dp(18);
            body.addView(right, rp);
        } else {
            ScrollView sc = scrollColumn();
            LinearLayout col = (LinearLayout) sc.getChildAt(0);
            col.addView(connectionCard());
            col.addView(gap(14));
            col.addView(windowCard());
            col.addView(gap(14));
            col.addView(statusCardView());
            col.addView(gap(14));
            col.addView(selfCheckCard());
            col.addView(gap(14));
            col.addView(permissionCard());
            col.addView(gap(14));
            col.addView(actionCard());
            col.addView(gap(14));
            col.addView(aboutCard());
            body.addView(sc, new LinearLayout.LayoutParams(-1, -1));
        }
        return page;
    }

    private ScrollView scrollColumn() {
        ScrollView sc = new ScrollView(this);
        sc.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(32));
        sc.addView(col, new ScrollView.LayoutParams(-1, -2));
        return sc;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(wide ? 28 : 18), dp(20), dp(wide ? 28 : 18), dp(12));
        // 不透明底色，滚动内容才不会从标题下面透出来。
        bar.setBackgroundColor(0xFF101725);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView t = text("守夜鱼", 26, Ui.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        titles.addView(t);

        TextView sub = text("到点盖一层关不掉的聊天框 · 一晚只放一次", 12, Ui.TEXT_DIM);
        sub.setPadding(0, dp(4), 0, 0);
        titles.addView(sub);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView badge = text("v1.2", 11, Ui.ACCENT);
        badge.setBackground(Ui.roundStroke(this, 0x1AFF6FA5, 999, 0x33FF6FA5, 0.8f));
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        bar.addView(badge);
        return bar;
    }

    // ------------------------------------------------------------------ 卡片
    private View connectionCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("连接"));

        TextView hint = small("你的 AstrBot 守夜后端地址。自建服务器没证书就勾下面的选项。");
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);

        c.addView(fieldLabel("服务器地址"));
        etServer = field("https://example.com/night");
        c.addView(etServer);

        c.addView(fieldLabel("口令"));
        etToken = field("后端插件配置里的 api_token");
        c.addView(etToken);

        cbInsecure = new CheckBox(this);
        cbInsecure.setText("信任自签证书 / 直连 IP");
        cbInsecure.setTextColor(Ui.TEXT_DIM);
        cbInsecure.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        cbInsecure.setPadding(0, dp(10), 0, 0);
        if (Build.VERSION.SDK_INT >= 21) {
            cbInsecure.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        }
        c.addView(cbInsecure);
        return c;
    }

    private TextView fieldLabel(String s) {
        TextView t = text(s, 12, Ui.TEXT_FAINT);
        t.setPadding(0, dp(10), 0, dp(6));
        return t;
    }

    private View windowCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("守夜时间"));

        TextView hint = small("只在时间段内盖锁屏；跨零点会自动识别（比如 23:30 → 06:30）。单晚最多 8 小时。");
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout c1 = new LinearLayout(this);
        c1.setOrientation(LinearLayout.VERTICAL);
        c1.addView(fieldLabel("开始"));
        etStart = field("23:30");
        etStart.setFocusable(false);
        etStart.setGravity(Gravity.CENTER);
        etStart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(etStart); }
        });
        c1.addView(etStart);

        LinearLayout c2 = new LinearLayout(this);
        c2.setOrientation(LinearLayout.VERTICAL);
        c2.addView(fieldLabel("结束"));
        etEnd = field("06:30");
        etEnd.setFocusable(false);
        etEnd.setGravity(Gravity.CENTER);
        etEnd.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pick(etEnd); }
        });
        c2.addView(etEnd);

        row.addView(c1, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
        mp.leftMargin = dp(12);
        row.addView(c2, mp);
        c.addView(row);
        return c;
    }

    private View statusCardView() {
        statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.HORIZONTAL);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setBackground(Ui.roundStroke(this, 0x14FF6FA5, 18, 0x33FF6FA5, 0.8f));
        statusCard.setPadding(dp(16), dp(14), dp(16), dp(14));

        statusDot = text("●", 12, Ui.ACCENT);
        statusCard.addView(statusDot);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        statusTitle = text("守夜未开启", 14, Ui.TEXT);
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusSub = text("", 12, Ui.TEXT_DIM);
        statusSub.setPadding(0, dp(2), 0, 0);
        texts.addView(statusTitle);
        texts.addView(statusSub);
        statusCard.addView(texts);
        return statusCard;
    }

    /** 自检区块：到点没锁时先看这里，区分「服务死了」和「发射了但没打中」。 */
    private View selfCheckCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("自检"));
        TextView hint = small("tick 在涨说明服务活着；「上次成功上锁」不动，就是界面没起来。");
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);

        selfBody = text("", 12, Ui.TEXT_DIM);
        selfBody.setTypeface(Typeface.MONOSPACE);
        selfBody.setLineSpacing(dp(5), 1f);
        c.addView(selfBody);
        return c;
    }

    private View permissionCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("强控制需要的权限"));
        TextView hint = small("点任意一行直接跳到对应的系统页面。锁不住、到点不弹，基本都出在这里。");
        hint.setPadding(0, dp(6), 0, dp(8));
        c.addView(hint);

        permOverlay = permRow("悬浮窗", "必需 · 后台才能把全屏界面顶上来");
        permOverlayMark = (TextView) permOverlay.getTag();
        c.addView(permOverlay);
        c.addView(permSep());

        permNotif = permRow("通知", "Android 13+ 必需 · 前台服务要挂常驻通知");
        permNotifMark = (TextView) permNotif.getTag();
        c.addView(permNotif);
        c.addView(permSep());

        permBatt = permRow("电池优化白名单", "推荐 · 防止息屏后服务被冻结");
        permBattMark = (TextView) permBatt.getTag();
        c.addView(permBatt);
        c.addView(permSep());

        permAuto = permRow("自启动 / 后台弹出", "国产 ROM 推荐 · 按 Home 或清后台后仍能拉回锁屏");
        permAutoMark = (TextView) permAuto.getTag();
        c.addView(permAuto);

        permOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openOverlay(); }
        });
        permNotif.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openNotif(); }
        });
        permBatt.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openBattery(); }
        });
        permAuto.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openAutoStart(); }
        });
        return c;
    }

    private View permSep() {
        View v = new View(this);
        v.setBackgroundColor(0x0FFFFFFF);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.7f))));
        return v;
    }

    /** 一行权限：状态点 + 名称/说明 + 箭头；状态点放在 tag 里。 */
    private View permRow(String title, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.rippleRound(this, 0x00000000, 12));
        row.setPadding(dp(6), dp(12), dp(6), dp(12));

        TextView mark = text("○", 15, 0x80FFFFFF);
        mark.setGravity(Gravity.CENTER);
        mark.setIncludeFontPadding(false);
        row.addView(mark, new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = text(title, 14, Ui.TEXT);
        TextView t2 = text(desc, 11, Ui.TEXT_FAINT);
        t2.setPadding(0, dp(3), 0, 0);
        texts.addView(t1);
        texts.addView(t2);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView a = text("›", 20, Ui.TEXT_FAINT);
        a.setPadding(dp(8), 0, dp(4), 0);
        row.addView(a);

        row.setTag(mark);
        return row;
    }

    private View actionCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("开始守夜"));

        TextView hint = small("开启后到点自动上锁；「立即锁一次」用来当场验收效果。");
        hint.setPadding(0, dp(6), 0, dp(12));
        c.addView(hint);

        TextView start = primary("保存并启动守夜");
        start.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(true); }
        });
        c.addView(start);

        c.addView(gap(10));

        TextView stop = secondary("停止守夜");
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Cfg.sp(MainActivity.this).edit().putBoolean("enabled", false).apply();
                stopService(new Intent(MainActivity.this, GuardService.class));
                LockActivity.closeNow();
                refreshStatus();
                Toast.makeText(MainActivity.this, "守夜已停止", Toast.LENGTH_SHORT).show();
            }
        });
        c.addView(stop);

        c.addView(gap(10));

        LinearLayout pair = new LinearLayout(this);
        pair.setOrientation(LinearLayout.HORIZONTAL);

        TextView test = secondary("测试连接");
        test.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(false); testConn(); }
        });
        pair.addView(test, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView lockNow = secondary("立即锁一次");
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = dp(10);
        pair.addView(lockNow, lp);
        c.addView(pair);
        return c;
    }

    private View aboutCard() {
        LinearLayout c = card();
        c.addView(sectionTitle("聊天框背景"));
        TextView hint = small("不选就用内置的深色渐变。选一张你喜欢的图会成为锁屏背景。");
        hint.setPadding(0, dp(6), 0, dp(10));
        c.addView(hint);

        bgNote = text(Cfg.bgPath(this).isEmpty() ? "当前：内置渐变" : "当前：自定义图片", 12, Ui.ACCENT);
        c.addView(bgNote);
        c.addView(gap(10));

        TextView pick = secondary("选择图片");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickBg(); }
        });
        c.addView(pick);

        c.addView(divider());
        c.addView(small("守夜期间打电话、解锁都正常。要停只能回这个 App 点「停止守夜」，"
                + "或在锁屏左上角长按头像。"));
        return c;
    }

    // ------------------------------------------------------------------ 数据
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
        // 时间窗硬上限 8 小时：服务器时区误配时，这是 PIN 之外的第二道保险。
        if (Cfg.windowMinutes(s, e) > Cfg.MAX_WINDOW_MIN) {
            Toast.makeText(this, "单晚最多 8 小时", Toast.LENGTH_LONG).show();
            return;
        }
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

    // ------------------------------------------------------------------ 权限
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
        if (permOverlayMark == null) return;
        setMark(permOverlayMark, hasOverlay(), true);
        setMark(permNotifMark, hasNotif(), true);
        setMark(permBattMark, batteryOk(), false);
        setMark(permAutoMark, false, false);
    }

    private void setMark(TextView mark, boolean ok, boolean required) {
        if (ok) {
            mark.setText("✓");
            mark.setTextColor(0xFF3DDC97);
        } else {
            mark.setText(required ? "!" : "○");
            mark.setTextColor(required ? Ui.ACCENT : Ui.TEXT_FAINT);
        }
    }

    // ------------------------------------------------------------------ 自检
    private void refreshSelfCheck() {
        if (selfBody == null) return;
        String reason = GuardService.lastFailReason;
        long failAt = GuardService.lastFailAt;
        String savedReason = Cfg.sp(this).getString("lastFailReason", "");
        long savedAt = Cfg.sp(this).getLong("lastFailAt", 0L);
        if (savedAt > failAt) {
            reason = savedReason;
            failAt = savedAt;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("服务：").append(GuardService.running ? "运行中" : "未运行")
                .append(" · tick ").append(GuardService.tickCount);
        long lockAt = GuardService.lastLockAt;
        if (lockAt <= 0) lockAt = Cfg.sp(this).getLong("lastLockAt", 0L);
        sb.append('\n').append("上次成功上锁：")
                .append(lockAt > 0 ? formatTs(lockAt) : "还没有");
        sb.append('\n').append("最近失败：").append(reason == null || reason.isEmpty() ? "无" : reason);
        if (failAt > 0) sb.append(" · ").append(formatTs(failAt));
        sb.append('\n').append("悬浮窗 ").append(ok(hasOverlay()))
                .append("   通知 ").append(ok(hasNotif()))
                .append("   电池 ").append(ok(batteryOk()))
                .append("   无障碍 ").append(ok(accessibilityOk()));
        selfBody.setText(sb.toString());
        selfBody.setTextColor(GuardService.running ? Ui.TEXT_DIM : Ui.ACCENT);
    }

    private String ok(boolean b) {
        return b ? "✓" : "✗";
    }

    private String formatTs(long epochMs) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date(epochMs));
    }

    /** M1 只读状态，不接无障碍；M2 装上 AccessibilityService 后这里会自动变 ✓。 */
    private boolean accessibilityOk() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (s == null) return false;
            String pkg = getPackageName().toLowerCase(java.util.Locale.US);
            return s.toLowerCase(java.util.Locale.US).contains(pkg);
        } catch (Throwable t) {
            Log.e("nightguard", "read accessibility services failed", t);
            return false;
        }
    }

    private final Runnable selfTick = new Runnable() {
        @Override public void run() {
            refreshSelfCheck();
            ui.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ 背景 / 连接
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
            if (bgNote != null) {
                bgNote.setText("当前：自定义图片");
                bgNote.setTextColor(Ui.ACCENT);
            }
            Toast.makeText(this, "背景已换（下次进锁屏生效）", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "读取图片失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void testConn() {
        statusTitle.setText("正在连接…");
        statusSub.setText(Cfg.server(this));
        statusDot.setTextColor(Ui.TEXT_FAINT);
        final String server = Cfg.server(this);
        if (server.isEmpty() || Cfg.token(this).isEmpty()) {
            statusTitle.setText("先去填服务器和口令");
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
                        refreshStatus();
                        new AlertDialog.Builder(MainActivity.this).setTitle("测试连接")
                                .setMessage(m).setPositiveButton("好", null).show();
                    }
                });
            }
        }).start();
    }

    private void refreshStatus() {
        if (statusTitle == null) return;
        boolean enabled = Cfg.enabled(this);
        boolean win = Cfg.inWindow(this);
        boolean granted = Cfg.grantedTonight(this);
        boolean ended = Cfg.endedTonight(this);

        statusTitle.setText(enabled ? "守夜进行中" : "守夜未开启");
        StringBuilder sb = new StringBuilder();
        sb.append(win ? "现在在时段内" : "现在不在时段内");
        if (!Cfg.configured(this)) sb.append(" · 还没配置服务器");
        sb.append(" · 今晚").append(granted ? "已放行过一次" : "还没放行");
        if (ended) sb.append(" · 会话已结束");
        statusSub.setText(sb.toString());

        if (statusCard != null) {
            statusCard.setBackground(Ui.roundStroke(this,
                    enabled ? 0x14FF6FA5 : 0x0FFFFFFF,
                    18,
                    enabled ? 0x33FF6FA5 : 0x1FFFFFFF, 0.8f));
            statusDot.setTextColor(enabled ? Ui.ACCENT : Ui.TEXT_FAINT);
        }
    }
}
