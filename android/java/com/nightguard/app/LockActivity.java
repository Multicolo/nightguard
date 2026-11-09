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
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
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

/** 盖在最上层、关不掉的守夜聊天框。 */
public class LockActivity extends Activity {
    private static volatile boolean ALIVE = false;
    private static LockActivity INSTANCE;
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ImageView bg;
    private LinearLayout msgList;
    private ScrollView scroll;
    private EditText input;
    private Button send;
    private TextView hint;
    private TextView clock;
    private String persona = Cfg.DEFAULT_PERSONA;
    private boolean ended;
    private boolean busy;

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
        persona = Cfg.persona(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        ended = Cfg.endedTonight(this);
        setContentView(build());
        loadHistory();
        loadBackground();
        hideBars();
        refreshHint();
        applyPrefill(getIntent());
    }

    @Override protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        applyPrefill(it);
    }

    /** 调试用：am start ... --es nightsay "中文" 可直接填进输入框。 */
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
        hideBars();
        scrollDown();
    }

    @Override protected void onPause() {
        super.onPause();
        ALIVE = false;
    }

    @Override protected void onDestroy() {
        ALIVE = false;
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        Toast.makeText(this, "别想跑，去睡觉。", Toast.LENGTH_SHORT).show();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ UI
    private View build() {
        int pad = dp(16);
        root = new FrameLayout(this);
        GradientDrawable fallback = new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{0xFF10131A, 0xFF1A2030, 0xFF0B0F14});
        root.setBackground(fallback);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x55060A10, 0x99080C12, 0xE6070A0F});
        scrim.setBackground(g);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // 顶部：头像 + 标题 + 时间
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.mipmap.ic_launcher);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(0x33FFFFFF);
        avatar.setBackground(ring);
        avatar.setClipToOutline(true);
        head.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(10), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(persona);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        TextView sub = new TextView(this);
        sub.setText("守夜中 · " + Cfg.formatMin(Cfg.startMin(this)) + "–" + Cfg.formatMin(Cfg.endMin(this)));
        sub.setTextColor(0xFFFF9EC4);
        sub.setTextSize(12);
        titles.addView(name);
        titles.addView(sub);
        // 隐藏后门：长按头像/标题进设置页（不然会话结束后手机没法退出守夜）
        View.OnLongClickListener backdoor = new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                Toast.makeText(LockActivity.this, "别告诉她……", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(LockActivity.this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            }
        };
        avatar.setOnLongClickListener(backdoor);
        titles.setOnLongClickListener(backdoor);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        clock = new TextView(this);
        clock.setTextColor(0xCCFFFFFF);
        clock.setTextSize(14);
        head.addView(clock);
        col.addView(head);

        hint = new TextView(this);
        hint.setTextColor(0xAAFFFFFF);
        hint.setTextSize(12);
        hint.setPadding(0, dp(8), 0, dp(6));
        col.addView(hint);

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        msgList = new LinearLayout(this);
        msgList.setOrientation(LinearLayout.VERTICAL);
        msgList.setPadding(0, dp(6), 0, dp(6));
        scroll.addView(msgList, new ScrollView.LayoutParams(-1, -2));
        col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        input = new EditText(this);
        input.setHint("跟" + persona + "说点什么…");
        input.setHintTextColor(0x88FFFFFF);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(15);
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        GradientDrawable inBg = new GradientDrawable();
        inBg.setColor(0x33FFFFFF);
        inBg.setCornerRadius(dp(22));
        input.setBackground(inBg);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send();
                    return true;
                }
                return false;
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));

        send = new Button(this);
        send.setText("发送");
        send.setAllCaps(false);
        send.setTextColor(0xFFFFFFFF);
        send.setTextSize(14);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFFF6FA5);
        btnBg.setCornerRadius(dp(22));
        send.setBackground(btnBg);
        send.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.leftMargin = dp(8);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { send(); }
        });
        row.addView(send, blp);
        col.addView(row);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) { return false; }
        });
        return root;
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

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ 消息渲染
    private void addMsg(String role, String text) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity("user".equals(role) ? Gravity.END : Gravity.START);
        wrap.setPadding(0, dp(4), 0, dp(4));

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor("user".equals(role) ? 0xFFFFFFFF : 0xFFEDEFF5);
        tv.setLineSpacing(dp(2), 1f);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(18));
        b.setColor("user".equals(role) ? 0xFF3A6DF0 : 0xE6272E3C);
        tv.setBackground(b);
        int max = (int) (getResources().getDisplayMetrics().widthPixels * 0.76);
        tv.setMaxWidth(max);
        wrap.addView(tv);
        msgList.addView(wrap);
        scrollDown();
    }

    private void addPhoto(byte[] bytes) {
        Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bm == null) return;
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.START);
        wrap.setPadding(0, dp(4), 0, dp(4));
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(0xFF1A2029);
        iv.setBackground(g);
        iv.setClipToOutline(true);
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.62);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.46);
        float scale = Math.min((float) maxW / bm.getWidth(), (float) maxH / bm.getHeight());
        int iw = Math.max(1, (int) (bm.getWidth() * scale));
        int ih = Math.max(1, (int) (bm.getHeight() * scale));
        iv.setImageBitmap(bm);
        wrap.addView(iv, new LinearLayout.LayoutParams(iw, ih));
        msgList.addView(wrap);
        scrollDown();
    }

    private View addTyping() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.START);
        wrap.setPadding(0, dp(4), 0, dp(4));
        TextView tv = new TextView(this);
        tv.setText(persona + "正在打字…");
        tv.setTextSize(13);
        tv.setTextColor(0x99FFFFFF);
        tv.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(18));
        b.setColor(0x55272E3C);
        tv.setBackground(b);
        wrap.addView(tv);
        msgList.addView(wrap);
        scrollDown();
        return wrap;
    }

    private void scrollDown() {
        scroll.post(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
        scroll.postDelayed(new Runnable() {
            @Override public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        }, 120);
    }

    private void refreshHint() {
        long until = Cfg.releasedUntil(this);
        if (ended) {
            hint.setText("她不想理你了。");
            input.setEnabled(false);
            send.setEnabled(false);
            input.setHint("今晚的对话结束了");
        } else if (until > System.currentTimeMillis()) {
            long s = Math.max(0, (until - System.currentTimeMillis()) / 1000);
            hint.setText("放行中… 还剩 " + (s / 60) + " 分 " + (s % 60) + " 秒");
        } else if (Cfg.grantedTonight(this)) {
            hint.setText("今晚已经放过你一次了，再求也没用。");
        } else {
            hint.setText("想继续玩？好好求她，说不定会放你几分钟。");
        }
        String t = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new java.util.Date());
        if (clock != null) clock.setText(t);
    }

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
        // 后端 /bg 没有图就保持渐变背景，不报错
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] d = Api.getBytes(LockActivity.this, "/bg");
                    if (d == null || d.length < 100) return;
                    FileOutputStream fo = new FileOutputStream(cache);
                    fo.write(d); fo.close();
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

        final View typing = addTyping();
        busy = true;
        send.setEnabled(false);
        hideKeyboard();

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
                    @Override public void run() { onReply(r, e, typing); }
                });
            }
        }).start();
    }

    private void onReply(JSONObject r, String err, View typing) {
        busy = false;
        if (typing != null && typing.getParent() == msgList) msgList.removeView(typing);
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
        String shown = Cfg.sp(this).getString("shownPhoto", "");
        if (!photo.isEmpty() && !photo.equals(shown)) {
            Cfg.sp(this).edit().putString("shownPhoto", photo).apply();
            pollPhoto(photo, 0);
        }

        int minutes = r.optInt("allow_minutes", 0);
        if (minutes > 0) {
            long until = System.currentTimeMillis() + minutes * 60_000L;
            Cfg.sp(this).edit()
                    .putLong("releasedUntil", until)
                    .putString("releasedNight", Cfg.nightKey())
                    .apply();
            hint.setText("好吧，再给你 " + minutes + " 分钟…到点我还会锁的。");
            input.setEnabled(false);
            send.setEnabled(false);
            Toast.makeText(this, persona + " 放行 " + minutes + " 分钟", Toast.LENGTH_SHORT).show();
            UI.postDelayed(new Runnable() {
                @Override public void run() { finish(); }
            }, 1600);
            return;
        }
        if (r.optBoolean("ended", false)) {
            ended = true;
            Cfg.sp(this).edit().putString("endedNight", Cfg.nightKey()).apply();
        }
        refreshHint();
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
                        if (got != null && got.length > 200) addPhoto(got);
                        else UI.postDelayed(new Runnable() {
                            @Override public void run() { pollPhoto(path, tries + 1); }
                        }, 3000);
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
