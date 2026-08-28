package com.better.heybox;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/**
 * 让独立设置页和小黑盒内嵌设置页共享宿主主题与动态取色逻辑。
 *
 * <p>设计系统约定（视频下载 UI 与自绘文本选择共用）：</p>
 * <ul>
 *   <li>圆角：面板 28dp / 按钮与条目 16dp / 小件 12dp；</li>
 *   <li>动画：抽屉入场 220ms、scrim 150ms、按压反馈 80ms、状态切换 150ms，统一 Decelerate；</li>
 *   <li>取色：全部派生自系统 Monet 色板（accent1/2、neutral1/2），不支持 Monet 的旧版本
 *       回退宿主 colorAccent，再回退模块品牌蓝；深浅色自动适配。</li>
 * </ul>
 */
public final class ThemeUtils {

    /** 模块品牌蓝（Monet 与宿主 colorAccent 均不可用时的最终兜底） */
    public static final int FALLBACK_ACCENT = 0xFF1677FF;

    /* ---- 设计 token：圆角（dp） ---- */
    public static final int RADIUS_SHEET_DP = 28;
    public static final int RADIUS_BUTTON_DP = 16;
    public static final int RADIUS_ITEM_DP = 16;
    public static final int RADIUS_SMALL_DP = 12;

    /* ---- 设计 token：动画（毫秒） ---- */
    public static final long ANIM_SHEET_IN_MS = 220;
    public static final long ANIM_SCRIM_IN_MS = 150;
    public static final long ANIM_PRESS_MS = 80;
    public static final long ANIM_STATE_MS = 150;

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

    /**
     * 强调色（accent1）：优先 Monet 动态取色（API 31+ 公开的 system_accent1_*，
     * 深色取 600、浅色取 200），失败回退宿主 colorAccent，再回退模块品牌蓝。
     */
    public static int resolveAccent(Context context) {
        return systemShade(context, "system_accent1_", FALLBACK_ACCENT);
    }

    /** 次强调色（accent2）：渐变辅助色、次级强调场景，回退链同 {@link #resolveAccent}。 */
    public static int resolveAccent2(Context context) {
        return systemShade(context, "system_accent2_", resolveAccent(context));
    }

    /**
     * 强档位强调色（悬浮按钮/进度条专用）：浅色取 600（饱和）、深色取 200（亮泽），
     * 保证叠在任意亮度的视频画面上都有足够对比度；{@link #resolveAccent} 则相反
     * （深色 600/浅色 200，适合文字与小控件）。
     */
    public static int resolveAccentStrong(Context context) {
        return systemShadeReversed(context, "system_accent1_", resolveAccent(context));
    }

    /** 强档位次强调色：与 {@link #resolveAccentStrong} 配对用于渐变。 */
    public static int resolveAccent2Strong(Context context) {
        return systemShadeReversed(context, "system_accent2_", resolveAccentStrong(context));
    }

    private static int systemShadeReversed(Context context, String family, int fallback) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String shade = isDarkMode(context) ? "200" : "600";
                int resId = context.getResources().getIdentifier(
                        family + shade, "color", "android");
                if (resId != 0) {
                    int color = context.getColor(resId);
                    if (Color.alpha(color) == 255) {
                        return color;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 面板/页面背景（surface，neutral1）：深色取 900、浅色取 50。 */
    public static int surfaceColor(Context context) {
        return isDarkMode(context)
                ? systemShade(context, "system_neutral1_900", 0xFF1C1C1E)
                : systemShade(context, "system_neutral1_50", 0xFFFFFFFF);
    }

    /** 次级表面（surfaceVariant，neutral2）：进度条轨道、chip、次级卡片底色。 */
    public static int surfaceVariantColor(Context context) {
        return isDarkMode(context)
                ? systemShade(context, "system_neutral2_800", 0xFF2C2C2E)
                : systemShade(context, "system_neutral2_100", 0xFFF2F2F7);
    }

    /** 描边/分隔线颜色：Monet neutral2 派生，不可用时用 12% 前景近似。 */
    public static int outlineColor(Context context) {
        if (isDarkMode(context)) {
            return withAlpha(systemShade(context, "system_neutral2_700", 0xFFFFFFFF), 0x2E);
        }
        return withAlpha(systemShade(context, "system_neutral2_200", 0xFF000000), 0x1F);
    }

    /** 主文本色（onSurface）。 */
    public static int textPrimaryColor(Context context) {
        return isDarkMode(context)
                ? systemShade(context, "system_neutral1_50", 0xDEFFFFFF)
                : systemShade(context, "system_neutral1_900", 0xDD000000);
    }

    /** 次级文本色（onSurfaceVariant，约 60% 不透明度）。 */
    public static int textSecondaryColor(Context context) {
        return withAlpha(textPrimaryColor(context), 0x99);
    }

    /** 读取系统 Monet 色板：深色 600 档 / 浅色 200 档，不可用时回退 fallback。 */
    private static int systemShade(Context context, String family, int fallback) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String shade = isDarkMode(context) ? "600" : "200";
                int resId = context.getResources().getIdentifier(
                        family + shade, "color", "android");
                if (resId != 0) {
                    int color = context.getColor(resId);
                    if (Color.alpha(color) == 255) {
                        return color;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 非 Monet 环境：尝试宿主主题的 colorAccent（仅强调色族），其余直接用 fallback
        if (family.startsWith("system_accent")) {
            try {
                TypedValue value = new TypedValue();
                if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)
                        && value.data != 0) {
                    return value.data;
                }
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    public static boolean isDarkMode(Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 使用系统 Monet accent 绘制按钮，并按实际背景亮度选择可读的前景文字色。 */
    public static void applyFilledButton(View button, Context context, float radiusDp) {
        int backgroundColor = resolveAccent(context);
        int foregroundColor = readableForegroundOn(backgroundColor);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setColor(backgroundColor);
        shape.setCornerRadius(radiusDp * context.getResources().getDisplayMetrics().density);

        int rippleColor = withAlpha(foregroundColor, 0x33);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleColor), shape, null));
        if (button instanceof TextView) {
            ((TextView) button).setTextColor(foregroundColor);
        }
    }

    /** 按背景亮度选择可读的前景文字色（白/黑），供自绘强调色按钮使用。 */
    public static int readableForegroundOn(int backgroundColor) {
        double luminance = (0.2126 * linear(Color.red(backgroundColor))
                + 0.7152 * linear(Color.green(backgroundColor))
                + 0.0722 * linear(Color.blue(backgroundColor)));
        return luminance > 0.45 ? Color.BLACK : Color.WHITE;
    }

    /** 给颜色替换 alpha 通道（其余通道保留）。 */
    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
