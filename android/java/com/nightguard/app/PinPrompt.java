package com.nightguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

/** PIN 的设置与校验对话框。 */
public final class PinPrompt {
    private PinPrompt() {}

    public static void showSetup(final Activity a, final boolean cancelable, final Runnable onDone) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 22);
        box.setPadding(pad, Ui.dp(a, 6), pad, 0);

        final EditText first = pinField(a, "输入 4 位数字");
        final EditText second = pinField(a, "再输一次");
        box.addView(first, fieldParams(a, 0));
        box.addView(second, fieldParams(a, 10));

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("设置紧急退出 PIN")
                .setMessage("锁屏里长按标题或头像 10 秒会要求输入它，只停今晚守夜。请记牢，忘了只能清除应用数据。")
                .setView(box)
                .setPositiveButton("保存", null);
        if (cancelable) b.setNegativeButton("取消", null);
        final AlertDialog d = b.create();
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String p1 = first.getText().toString();
                        String p2 = second.getText().toString();
                        if (!p1.matches("\\d{4}")) {
                            first.setError("要 4 位数字");
                            return;
                        }
                        if (!p1.equals(p2)) {
                            second.setError("两次输入不一致");
                            return;
                        }
                        Pin.set(a, p1);
                        d.dismiss();
                        Toast.makeText(a, "紧急退出 PIN 已保存", Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });
        d.show();
    }

    public static void showVerify(final Activity a, final Runnable onSuccess) {
        if (Pin.locked(a)) {
            long min = (Pin.lockRemainingMs(a) + 59_999L) / 60_000L;
            Toast.makeText(a, "PIN 错误次数过多，" + min + " 分钟后再试", Toast.LENGTH_LONG).show();
            return;
        }
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(a, 22);
        box.setPadding(pad, Ui.dp(a, 6), pad, 0);
        final EditText field = pinField(a, "4 位 PIN");
        box.addView(field, fieldParams(a, 0));

        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("紧急退出")
                .setMessage("输入 4 位 PIN，只停今晚的守夜；时间窗和历史都不会改。")
                .setView(box)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null);
        final AlertDialog d = b.create();
        d.setCancelable(true);
        d.setCanceledOnTouchOutside(true);
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String pin = field.getText().toString();
                        if (!pin.matches("\\d{4}")) {
                            field.setError("要 4 位数字");
                            return;
                        }
                        if (!Pin.verify(a, pin)) {
                            Pin.fail(a);
                            if (Pin.locked(a)) {
                                long min = (Pin.lockRemainingMs(a) + 59_999L) / 60_000L;
                                Toast.makeText(a, "PIN 错误 3 次，" + min + " 分钟后再试", Toast.LENGTH_LONG).show();
                                d.dismiss();
                            } else {
                                field.setError("PIN 不对，还可试 " + (3 - Pin.fails(a)) + " 次");
                                field.setText("");
                            }
                            return;
                        }
                        Pin.resetFails(a);
                        d.dismiss();
                        if (onSuccess != null) onSuccess.run();
                    }
                });
            }
        });
        d.show();
    }

    private static EditText pinField(Activity a, String hint) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setHintTextColor(0x59FFFFFF);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(22);
        e.setTypeface(Typeface.MONOSPACE);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        return e;
    }

    private static LinearLayout.LayoutParams fieldParams(Activity a, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, topMarginDp);
        return lp;
    }
}
