package com.nightguard.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;

/** 一点画图/尺寸的小工具，避免到处重复 dp 和圆角。 */
public final class Ui {
    public static final int BG = 0xFF0A0D12;
    public static final int CARD = 0xF2141A24;
    public static final int STROKE = 0x24FFFFFF;
    public static final int TEXT = 0xFFF2F4F8;
    public static final int TEXT_DIM = 0x99FFFFFF;
    public static final int TEXT_FAINT = 0x66FFFFFF;
    public static final int ACCENT = 0xFFFF6FA5;
    public static final int ACCENT_DARK = 0xFFE0508C;
    public static final int BUBBLE_ME = 0xFF3B62E0;
    public static final int BUBBLE_HER = 0xE61E2431;

    private Ui() {}

    public static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int sp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, c.getResources().getDisplayMetrics());
    }

    public static boolean isWide(Context c) {
        return c.getResources().getConfiguration().screenWidthDp >= 600;
    }

    public static GradientDrawable round(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable roundStroke(Context c, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable g = round(c, color, radiusDp);
        g.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return g;
    }

    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{from, to});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 整页背景那种多段竖向渐变，不带圆角。 */
    public static GradientDrawable gradient(GradientDrawable.Orientation o, int[] colors) {
        return new GradientDrawable(o, colors);
    }

    /** 气泡：左上/右上角按说话人做「尖角」，其余圆角。 */
    public static GradientDrawable bubble(Context c, boolean me) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(me ? BUBBLE_ME : BUBBLE_HER);
        float big = dp(c, 18);
        float small = dp(c, 6);
        g.setCornerRadii(new float[]{
                big, big,
                me ? big : small, me ? big : small,
                me ? small : big, me ? small : big,
                big, big
        });
        if (!me) g.setStroke(Math.max(1, dp(c, 0.8f)), STROKE);
        return g;
    }

    public static Drawable ripple(Context c, int color, float radiusDp) {
        GradientDrawable content = round(c, color, radiusDp);
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
        }
        return content;
    }

    public static Drawable rippleStroke(Context c, int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable content = roundStroke(c, color, radiusDp, stroke, strokeDp);
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
        }
        return content;
    }

    public static Drawable rippleRound(Context c, int color, float radiusDp) {
        return ripple(c, color, radiusDp);
    }

    public static Drawable rippleRoundStroke(Context c, int color, float radiusDp, int stroke) {
        return rippleStroke(c, color, radiusDp, stroke, 0.8f);
    }

    public static Drawable rippleGradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable content = gradient(c, from, to, radiusDp);
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
        }
        return content;
    }

    public static void fadeIn(View v) {
        v.setAlpha(0f);
        v.setTranslationY(dp(v.getContext(), 10));
        v.animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    public static int mix(int a, int b, float t) {
        int ar = Color.red(a), ag = Color.green(a), ab = Color.blue(a), aa = Color.alpha(a);
        int br = Color.red(b), bg = Color.green(b), bb = Color.blue(b), ba = Color.alpha(b);
        return Color.argb(
                (int) (aa + (ba - aa) * t),
                (int) (ar + (br - ar) * t),
                (int) (ag + (bg - ag) * t),
                (int) (ab + (bb - ab) * t));
    }
}
