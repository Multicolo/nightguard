package com.nightguard.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 盖在最上层、关不掉的守夜聊天框。
 * 竖屏是「头像 + 聊天流 + 输入条」；平板/横屏自动切成左边角色、右边聊天的双栏。
 */
public class LockActivity extends Activity {
    private static volatile boolean ALIVE = false;
    /**
     * 上锁回写心跳：onCreate/onResume 写入 SystemClock.elapsedRealtime()。
     * GuardService 发起后等 500ms 回读这个值，没变就说明这轮没打中。
     */
    public static volatile long lastAttachAt = 0L;
    private static LockActivity INSTANCE;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ImageView bg;
    private ScrollView scroll;
    private LinearLayout msgList;
    private EditText input;
    private TextView send;
    private LinearLayout inputRow;
    private LinearLayout hintPill;
    private TextView hintText;
    private TextView clock;
    private TextView nameView;
    private TextView statusChip;
    private View typing;
    private Runnable typingTick;

    private String persona = Cfg.DEFAULT_PERSONA;
    private boolean ended;
    private boolean busy;
    private boolean wide;
    private boolean photoShown;

    public static boolean alive() { return ALIVE; }

    public static void closeNow() {
        UI.post(new Runnable() {
            @Override public void run() {
                if (INSTANCE != null) INSTANCE.finish();
            }
        });
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        INSTANCE = this;
        ALIVE = true;
        lastAttachAt = SystemClock.elapsedRealtime();
        persona = Cfg.persona(this);
        ended = Cfg.endedTonight(this);
        wide = Ui.isWide(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        setContentView(build());
        loadHistory();
        loadBackground();
        hideBars();
        refreshState();
        applyPrefill(getIntent());
    }

    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        applyPrefill(it);
    }

    private void applyPrefill(Intent it) {
        if (it == null) return;
        String s = it.getStringExtra("nightsay");
        if (s != null && !s.isEmpty() && input != null) {
            input.setText(s);
            input.setSelection(s.length());
        }
    }

    @Override protected void onResume() {
        super.onResume();
        ALIVE = true;
        lastAttachAt = SystemClock.elapsedRealtime();
        hideBars();
        scrollDown();
    }

    /**
     * 只在真正不可见时才算「锁没了」。原实现在 onPause 里置 false，
     * 系统弹个权限框/通知栏下拉就会被服务当成锁已关闭，每 2 秒重新拉一次。
     */
    @Override protected void onStop() {
        ALIVE = false;
        super.onStop();
    }

    @Override protected void onDestroy() {
        ALIVE = false;
        if (typingTick != null) UI.removeCallbacks(typingTick);
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        Toast.makeText(this, "别想跑，去睡觉。", Toast.LENGTH_SHORT).show();
    }

    /** 竖屏 ⇄ 平板/横屏：宽度档位变了就重建，让布局换成单列或双栏。 */
    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (Ui.isWide(this) != wide) recreate();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ 布局
    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackground(Ui.gradient(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xB3070A10, 0x4D070A10, 0x80070A10, 0xF2070A10}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        if (Ui.isWide(this)) {
            View side = new View(this);
            side.setBackground(Ui.gradient(GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xE60A0D12, 0xB30A0D12, 0x000A0D12}));
            root.addView(side, new FrameLayout.LayoutParams(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.42f), -1));
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int pad = Ui.dp(this, wide ? 28 : 0);
        content.setPadding(pad, pad, pad, pad);
        root.addView(content, new FrameLayout.LayoutParams(-1, -1));

        if (wide) {
            content.addView(buildProfilePanel(), new LinearLayout.LayoutParams(0, -1, 0.38f));
            View div = new View(this);
            div.setBackgroundColor(0x1AFFFFFF);
            content.addView(div, new LinearLayout.LayoutParams(Ui.dp(this, 1), -1));
            content.addView(buildChatColumn(true), new LinearLayout.LayoutParams(0, -1, 0.62f));
        } else {
            content.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));
            content.addView(buildChatColumn(false), new LinearLayout.LayoutParams(-1, 0, 1f));
        }
        return root;
    }

    private View avatarView(int sizeDp) {
        FrameLayout holder = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFFF9EC4, 0xFF8E6CF0});
        ring.setShape(GradientDrawable.OVAL);
        holder.setBackground(ring);
        holder.setClipToOutline(true);

        ImageView iv = new ImageView(this);
        iv.setImageResource(R.mipmap.ic_launcher);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        holder.addView(iv, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        int inset = Ui.dp(this, 3);
        holder.setPadding(inset, inset, inset, inset);
        holder.setOnLongClickListener(backdoor());
        holder.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp)));
        return holder;
    }

    private View buildHeader() {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 10));

        head.addView(avatarView(44));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titles.setOnLongClickListener(backdoor());

        nameView = new TextView(this);
        nameView.setText(persona);
        nameView.setTextColor(Ui.TEXT);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setOnLongClickListener(backdoor());

        statusChip = statusText(11);
        statusChip.setPadding(0, Ui.dp(this, 3), 0, 0);

        titles.addView(nameView);
        titles.addView(statusChip);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        clock = new TextView(this);
        clock.setTextColor(Ui.TEXT_DIM);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        clock.setTypeface(Typeface.MONOSPACE);
        head.addView(clock);
        return head;
    }

    private View buildProfilePanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(Ui.dp(this, 10), Ui.dp(this, 40), Ui.dp(this, 10), Ui.dp(this, 28));

        panel.addView(avatarView(112));

        nameView = new TextView(this);
        nameView.setText(persona);
        nameView.setTextColor(Ui.TEXT);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        nameView.setTypeface(Typeface.DEFAULT_BOLD);
        nameView.setGravity(Gravity.CENTER);
        nameView.setPadding(0, Ui.dp(this, 18), 0, 0);
        nameView.setOnLongClickListener(backdoor());
        panel.addView(nameView);

        TextView sub = new TextView(this);
        sub.setText("守夜中 · " + Cfg.formatMin(Cfg.startMin(this)) + " – " + Cfg.formatMin(Cfg.endMin(this)));
        sub.setTextColor(Ui.ACCENT);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 6), 0, 0);
        panel.addView(sub);

        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 1));
        llp.topMargin = Ui.dp(this, 26);
        panel.addView(line, llp);

        statusChip = statusText(13);
        statusChip.setGravity(Gravity.CENTER);
        statusChip.setPadding(0, Ui.dp(this, 22), 0, 0);
        panel.addView(statusChip);

        TextView tip = new TextView(this);
        tip.setText("再求也没用的时候，就真的该睡了。");
        tip.setTextColor(Ui.TEXT_FAINT);
        tip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tip.setGravity(Gravity.CENTER);
        tip.setPadding(0, Ui.dp(this, 14), 0, 0);
        panel.addView(tip);

        clock = new TextView(this);
        clock.setTextColor(0x4DFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 22);
        panel.addView(clock, clp);
        return panel;
    }

    private View buildChatColumn(boolean inWide) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, inWide ? 24 : 14),
                Ui.dp(this, inWide ? 10 : 0),
                Ui.dp(this, inWide ? 24 : 14),
                Ui.dp(this, inWide ? 14 : 8));

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        msgList = new LinearLayout(this);
        msgList.setOrientation(LinearLayout.VERTICAL);
        msgList.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        scroll.addView(msgList, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        hintPill = new LinearLayout(this);
        hintPill.setOrientation(LinearLayout.HORIZONTAL);
        hintPill.setGravity(Gravity.CENTER);
        hintPill.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6));
        hintPill.setBackground(Ui.roundStroke(this, 0x66101620, 20, 0x1FFFFFFF, 0.8f));

        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Ui.ACCENT);
        dot.setBackground(d);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6));
        dlp.rightMargin = Ui.dp(this, 8);
        hintPill.addView(dot, dlp);

        hintText = new TextView(this);
        hintText.setTextColor(Ui.TEXT_DIM);
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        hintPill.addView(hintText);

        LinearLayout hintWrap = new LinearLayout(this);
        hintWrap.setGravity(Gravity.CENTER);
        hintWrap.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        hintWrap.addView(hintPill);
        col.addView(hintWrap, new LinearLayout.LayoutParams(-1, -2));

        inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setBackground(Ui.roundStroke(this, 0xEE141A24, 26, 0x1FFFFFFF, 0.8f));
        inputRow.setPadding(Ui.dp(this, 6), Ui.dp(this, 5), Ui.dp(this, 6), Ui.dp(this, 5));

        input = new EditText(this);
        input.setHint("跟" + persona + "说点什么…");
        input.setHintTextColor(0x5FFFFFFF);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackgroundColor(0x00000000);
        input.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send();
                    return true;
                }
                return false;
            }
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        send = new TextView(this);
        send.setText("↑");
        send.setTextColor(0xFFFFFFFF);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        send.setTypeface(Typeface.DEFAULT_BOLD);
        send.setGravity(Gravity.CENTER);
        send.setBackground(Ui.rippleGradient(this, 0xFFFF8DB4, Ui.ACCENT_DARK, 22));
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        blp.leftMargin = Ui.dp(this, 4);
        inputRow.addView(send, blp);

        col.addView(inputRow, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private TextView statusText(int sp) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Ui.TEXT_DIM);
        return t;
    }

    private View.OnLongClickListener backdoor() {
        return new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Toast.makeText(LockActivity.this, "别告诉她……", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(LockActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
        };
    }

    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // ------------------------------------------------------------------ 消息
    private void addMsg(String role, String text) {
        boolean me = "user".equals(role);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(me ? Gravity.END : Gravity.START);
        wrap.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTextColor(me ? 0xFFFFFFFF : 0xFFEDEFF5);
        tv.setLineSpacing(Ui.dp(this, 3), 1f);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        tv.setBackground(Ui.bubble(this, me));
        int max = (int) (getResources().getDisplayMetrics().widthPixels * (wide ? 0.4 : 0.76));
        tv.setMaxWidth(max);
        wrap.addView(tv);
        msgList.addView(wrap);
        Ui.fadeIn(wrap);
        scrollDown();
    }

    private void addPhoto(byte[] bytes) {
        if (photoShown) return;
        Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bm == null) return;
        photoShown = true;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.START);
        wrap.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

        FrameLayout card = new FrameLayout(this);
        card.setBackground(Ui.round(this, 0xFF1A2029, 20));
        card.setClipToOutline(true);
        if (Build.VERSION.SDK_INT >= 21) card.setElevation(Ui.dp(this, 6));

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageBitmap(bm);
        card.addView(iv, new FrameLayout.LayoutParams(-1, -1));

        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * (wide ? 0.27 : 0.66));
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * (wide ? 0.46 : 0.44));
        float scale = Math.min((float) maxW / bm.getWidth(), (float) maxH / bm.getHeight());
        int iw = Math.max(1, (int) (bm.getWidth() * scale));
        int ih = Math.max(1, (int) (bm.getHeight() * scale));
        wrap.addView(card, new LinearLayout.LayoutParams(iw, ih));
        msgList.addView(wrap);
        Ui.fadeIn(wrap);
        scrollDown();
    }

    private void showTyping() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.START);
        wrap.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
        final TextView tv = new TextView(this);
        tv.setTextColor(0xCCFFFFFF);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        tv.setBackground(Ui.bubble(this, false));
        wrap.addView(tv);
        msgList.addView(wrap);
        typing = wrap;
        Ui.fadeIn(wrap);
        typingTick = new Runnable() {
            private int n;
            @Override public void run() {
                if (typing == null) return;
                n = (n + 1) % 4;
                StringBuilder sb = new StringBuilder(persona).append(" 正在打字");
                for (int i = 0; i < n; i++) sb.append("·");
                tv.setText(sb.toString());
                UI.postDelayed(this, 420);
            }
        };
        UI.post(typingTick);
        scrollDown();
    }

    private void hideTyping() {
        if (typingTick != null) {
            UI.removeCallbacks(typingTick);
            typingTick = null;
        }
        if (typing != null && typing.getParent() == msgList) msgList.removeView(typing);
        typing = null;
    }

    private void scrollDown() {
        if (scroll == null) return;
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
        scroll.postDelayed(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        }, 140);
    }

    // ------------------------------------------------------------------ 状态
    private void refreshState() {
        long until = Cfg.releasedUntil(this);
        boolean released = until > Clock.now() && !ended;
        if (hintText != null) {
            if (ended) {
                hintText.setText("今晚的对话结束了");
                input.setEnabled(false);
                if (send != null) { send.setEnabled(false); send.setAlpha(0.4f); }
                input.setHint("她已经不理你了");
            } else if (released) {
                long s = Math.max(0, (until - Clock.now()) / 1000);
                hintText.setText("放行中 · 还剩 " + (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60));
            } else if (Cfg.grantedTonight(this)) {
                hintText.setText("今晚已经放过你一次了，再求也没用。");
            } else {
                hintText.setText("想继续玩？好好求她，说不定会放你几分钟。");
            }
        }

        String chip;
        if (released) chip = "放行中 · 到点继续锁";
        else if (ended) chip = "今晚的会话已结束";
        else if (Cfg.grantedTonight(this)) chip = "今晚已放过一次";
        else chip = "今晚还没求过我";
        if (statusChip != null) {
            // 竖屏里这一行是标题下的副标题，固定显示守夜时段；状态由底部药丸说明。
            statusChip.setText(wide ? chip : "守夜中 · "
                    + Cfg.formatMin(Cfg.startMin(this)) + " – " + Cfg.formatMin(Cfg.endMin(this)));
        }

        if (clock != null) {
            clock.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(Clock.now())));
        }
        UI.removeCallbacks(clockTick);
        UI.postDelayed(clockTick, 15000);
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() { refreshState(); }
    };

    // ------------------------------------------------------------------ 背景 / 历史
    private void loadBackground() {
        String local = Cfg.bgPath(this);
        if (!local.isEmpty()) {
            Bitmap bm = BitmapFactory.decodeFile(local);
            if (bm != null) { bg.setImageBitmap(bm); return; }
        }
        final File cache = new File(getFilesDir(), "bg_default.jpg");
        if (cache.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(cache.getAbsolutePath());
            if (bm != null) { bg.setImageBitmap(bm); return; }
        }
        if (!Cfg.configured(this)) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] d = Api.getBytes(LockActivity.this, "/bg");
                    if (d == null || d.length < 100) return;
                    FileOutputStream fo = new FileOutputStream(cache);
                    fo.write(d);
                    fo.close();
                    final Bitmap bm = BitmapFactory.decodeFile(cache.getAbsolutePath());
                    if (bm != null) UI.post(new Runnable() {
                        @Override public void run() { bg.setImageBitmap(bm); }
                    });
                } catch (Throwable e) {
                    Log.i("nightguard", "no background from server, keep gradient");
                }
            }
        }).start();
    }

    private void loadHistory() {
        try {
            JSONArray arr = new JSONArray(Cfg.history(this));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                addMsg(o.optString("role", "assistant"), o.optString("text", ""));
            }
        } catch (Exception ignored) {}
        if (msgList.getChildCount() == 0) {
            addMsg("assistant", "这么晚了还亮着屏幕……手机放下，去睡觉。");
        }
        // 重建界面（旋转、被系统回收）后把今晚那张睡照贴回来。
        java.io.File photo = new java.io.File(getFilesDir(), "photo_last.jpg");
        if (photo.exists() && photo.length() > 200) {
            try {
                byte[] d = new byte[(int) photo.length()];
                java.io.FileInputStream fi = new java.io.FileInputStream(photo);
                int n = fi.read(d);
                fi.close();
                if (n > 200) addPhoto(d);
            } catch (Throwable ignored) {}
        }
    }

    private JSONArray historyForApi() {
        JSONArray out = new JSONArray();
        try {
            JSONArray arr = new JSONArray(Cfg.history(this));
            int from = Math.max(0, arr.length() - 12);
            for (int i = from; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONObject n = new JSONObject();
                n.put("role", o.optString("role", "assistant"));
                n.put("text", o.optString("text", ""));
                out.put(n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private void saveHistory(String role, String text) {
        try {
            JSONArray arr = new JSONArray(Cfg.history(this));
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("text", text);
            arr.put(o);
            JSONArray keep = new JSONArray();
            for (int i = Math.max(0, arr.length() - 40); i < arr.length(); i++) keep.put(arr.get(i));
            Cfg.sp(this).edit().putString("history", keep.toString()).apply();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ 发送
    private void send() {
        if (ended || busy) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!Cfg.configured(this)) {
            Toast.makeText(this, "还没配置服务器，长按头像去设置", Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        addMsg("user", text);
        final JSONArray history = historyForApi();
        saveHistory("user", text);

        busy = true;
        if (send != null) send.setEnabled(false);
        hideKeyboard();
        showTyping();

        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject resp = null;
                String err = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("text", text);
                    body.put("history", history);
                    resp = Api.post(LockActivity.this, "/chat", body);
                } catch (Throwable e) {
                    err = e.getMessage();
                    Log.e("nightguard", "chat failed: " + e, e);
                }
                final JSONObject r = resp;
                final String e = err;
                UI.post(new Runnable() {
                    @Override public void run() { onReply(r, e); }
                });
            }
        }).start();
    }

    private void onReply(JSONObject r, String err) {
        busy = false;
        hideTyping();
        if (send != null && !ended) send.setEnabled(true);

        if (r == null) {
            addMsg("assistant", "（信号好像断了…再发一次试试）");
            if (err != null) Log.e("nightguard", "api: " + err);
            return;
        }
        String say = r.optString("say", "");
        if (say.isEmpty()) say = "哼。";
        addMsg("assistant", say);
        saveHistory("assistant", say);

        final String photo = r.optString("photo", "");
        if (!photo.isEmpty()) pollPhoto(photo, 0);

        int minutes = r.optInt("allow_minutes", 0);
        if (minutes > 0) {
            long until = Clock.now() + minutes * 60_000L;
            Cfg.sp(this).edit()
                    .putLong("releasedUntil", until)
                    .putString("releasedNight", Cfg.nightKey())
                    .apply();
            refreshState();
            Toast.makeText(this, persona + " 放行 " + minutes + " 分钟", Toast.LENGTH_SHORT).show();
            UI.postDelayed(new Runnable() {
                @Override public void run() { finish(); }
            }, 1400);
            return;
        }
        if (r.optBoolean("ended", false)) {
            ended = true;
            Cfg.sp(this).edit().putString("endedNight", Cfg.nightKey()).apply();
        }
        refreshState();
    }

    private void pollPhoto(final String path, final int tries) {
        if (tries > 24) return;
        new Thread(new Runnable() {
            @Override public void run() {
                byte[] d = null;
                try { d = Api.getBytes(LockActivity.this, path); } catch (Throwable ignored) {}
                final byte[] got = d;
                UI.post(new Runnable() {
                    @Override public void run() {
                        if (got != null && got.length > 200) {
                            try {
                                java.io.FileOutputStream fo = new java.io.FileOutputStream(
                                        new java.io.File(getFilesDir(), "photo_last.jpg"));
                                fo.write(got);
                                fo.close();
                            } catch (Throwable ignored) {}
                            addPhoto(got);
                        } else {
                            UI.postDelayed(new Runnable() {
                                @Override public void run() { pollPhoto(path, tries + 1); }
                            }, 3000);
                        }
                    }
                });
            }
        }).start();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }
}
