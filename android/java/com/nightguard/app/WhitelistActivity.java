package com.nightguard.app;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** 白名单：硬编码的通话/紧急/系统 UI 不可取消，其余启动器应用可勾选。 */
public class WhitelistActivity extends Activity {
    private final List<Whitelist.AppEntry> apps = new ArrayList<>();
    private AppAdapter adapter;
    private TextView status;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF101725);
        setContentView(build());
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        GuardService.setSettingsOpen(true);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override protected void onPause() {
        GuardService.setSettingsOpen(false);
        super.onPause();
    }

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Ui.BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(10), dp(16), dp(10));
        bar.setBackgroundColor(0xFF101725);
        TextView back = text("‹ 返回", 16, Ui.ACCENT);
        back.setPadding(dp(10), dp(8), dp(12), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);
        TextView title = text("后台拉回白名单", 18, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title);
        page.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        TextView hint = text("守夜时段里，这些 App 不会被立刻拉回锁屏。"
                + "电话、紧急呼叫、系统界面永远放行，不能取消。", 12, Ui.TEXT_DIM);
        hint.setLineSpacing(dp(4), 1f);
        hint.setPadding(dp(18), dp(10), dp(18), dp(8));
        page.addView(hint);

        status = text("正在读取已安装应用…", 13, Ui.TEXT_FAINT);
        status.setPadding(dp(18), dp(8), dp(18), dp(8));
        page.addView(status);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setCacheColorHint(0);
        list.setBackgroundColor(Ui.BG);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        return page;
    }

    private void load() {
        new Thread(new Runnable() {
            @Override public void run() {
                final List<Whitelist.AppEntry> got = Whitelist.entries(WhitelistActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        apps.clear();
                        apps.addAll(got);
                        adapter.notifyDataSetChanged();
                        status.setText(got.size() <= 6
                                ? "没有读到可启动的应用"
                                : "已勾选 " + Whitelist.userCount(WhitelistActivity.this)
                                + " 个；改动立即生效");
                        status.setTextColor(Ui.TEXT_FAINT);
                    }
                });
            }
        }).start();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convert, ViewGroup parent) {
            final Whitelist.AppEntry e = apps.get(i);
            LinearLayout row = new LinearLayout(WhitelistActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(10), dp(16), dp(10));
            row.setBackgroundColor(i % 2 == 0 ? 0x00000000 : 0x08FFFFFF);

            CheckBox cb = new CheckBox(WhitelistActivity.this);
            cb.setChecked(Whitelist.isWhitelisted(WhitelistActivity.this, e.pkg));
            cb.setEnabled(!e.hard);
            cb.setClickable(false);
            cb.setFocusable(false);
            cb.setButtonTintList(ColorStateList.valueOf(Ui.ACCENT));
            row.addView(cb, new LinearLayout.LayoutParams(dp(40), dp(40)));

            LinearLayout texts = new LinearLayout(WhitelistActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(4), 0, 0, 0);
            TextView name = text(e.label, 15, Ui.TEXT);
            TextView pkg = text(e.pkg + (e.hard ? " · 系统必需" : ""), 11, Ui.TEXT_FAINT);
            pkg.setPadding(0, dp(2), 0, 0);
            texts.addView(name);
            texts.addView(pkg);
            row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (e.hard) {
                        Toast.makeText(WhitelistActivity.this, "系统必需，不能取消", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean on = !Whitelist.isWhitelisted(WhitelistActivity.this, e.pkg);
                    Whitelist.set(WhitelistActivity.this, e.pkg, on);
                    notifyDataSetChanged();
                    status.setText("已勾选 " + Whitelist.userCount(WhitelistActivity.this)
                            + " 个；改动立即生效");
                }
            });
            return row;
        }
    }
}
