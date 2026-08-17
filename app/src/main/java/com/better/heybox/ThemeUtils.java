package com.better.heybox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/** 让独立设置页和小黑盒内嵌设置页共享宿主主题与动态取色逻辑。 */
public final class ThemeUtils {

    private static final int FALLBACK_ACCENT = 0xFF1565C0;

    private ThemeUtils() {
    }

    public static int resolveColor(Context context, int attr, int fallback) {
        try {
            TypedValue value = new TypedValue();
            if (!context.getTheme().resolveAttribute(attr, value, true)) {
                return fallback;
            }
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
            if (value.resourceId != 0) {
                return context.getResources().getColor(value.resourceId, context.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    public static int resolveTextColor(Context context, int attr, int fallback) {
        try (TypedArray values = context.obtainStyledAttributes(new int[]{attr})) {
            ColorStateList colors = values.getColorStateList(0);
            return colors != null ? colors.getDefaultColor() : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    /** 只使用 Android 系统 Monet 色板，不读取宿主自定义 colorAccent。 */
    public static int resolveAccent(Context context) {
        try {
            int id = context.getResources().getIdentifier(
                    "system_accent1_600", "color", "android");
            if (id != 0 && Build.VERSION.SDK_INT >= 23) {
                return context.getResources().getColor(id, context.getTheme());
            }
        } catch (Throwable ignored) {
        }
        return FALLBACK_ACCENT;
    }

    public static boolean isDarkMode(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** 使用系统 Monet accent 绘制按钮，并按实际背景亮度选择可读的前景文字色。 */
    public static void applyFilledButton(View button, Context context, float radiusDp) {
        int backgroundColor = resolveAccent(context);
        int foregroundColor = readableForeground(backgroundColor);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(backgroundColor);
        shape.setCornerRadius(radiusDp * context.getResources().getDisplayMetrics().density);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int rippleColor = ColorUtils.withAlpha(foregroundColor, 0x33);
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(rippleColor), shape, null));
        } else {
            button.setBackground(shape);
        }
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(foregroundColor);
        }
    }

    private static int readableForeground(int backgroundColor) {
        double luminance = (0.2126 * linear(Color.red(backgroundColor))
                + 0.7152 * linear(Color.green(backgroundColor))
                + 0.0722 * linear(Color.blue(backgroundColor)));
        return luminance > 0.45 ? Color.BLACK : Color.WHITE;
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static final class ColorUtils {
        private ColorUtils() {
        }

        static int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
