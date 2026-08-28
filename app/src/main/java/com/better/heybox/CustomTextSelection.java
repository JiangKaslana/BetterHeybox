package com.better.heybox;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Layout;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 自绘制文本选择（开关「自绘制文本选择」开启后启用）。
 *
 * <p>与现有复制功能解耦：仅当 {@link TextSelectHook} 在自定义模式下挂载到具体 TextView
 * 时才生效，其余逻辑完全不受影响。开启后：</p>
 * <ul>
 *   <li>不调用系统文本选择 / 选区 / 高亮 UI，不触发 Selection Action Mode；</li>
 *   <li>不触发小黑盒 TextSelectHandler 的自绘选择 UI（该拦截由 TextSelectHook 统一负责）；</li>
 *   <li>长按选中（按词扩展）、拖动调整范围、选区高亮、两侧手柄、全选 / 复制 / 分享、
 *       取消全部由本类实现，视觉与交互对齐 Android 原生文本选择。</li>
 * </ul>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>取色：优先解析系统动态取色（Monet，API 31+ systemAccentColor），回退主题
 *       colorAccent，最后回退默认品牌蓝；菜单为原生风格的浅/深色中性浮层；</li>
 *   <li>高亮：{@link HighlightOverlay} 按 Layout 逐行绘制圆角矩形并做 UNION 合并，
 *       多行选区无生硬直角、无接缝，不依赖文本是否为可变 Spannable；</li>
 *   <li>手柄：{@link SelectionHandle} 首尾两个水滴形可拖动手柄（带阴影、拖动微放大），
 *       视觉尺寸克制、触摸区域放大；</li>
 *   <li>菜单：透明遮罩 + 原生风格圆角浮层（全选 / 分享 / 复制）叠加在窗口 Decor 上，
 *       点击遮罩/返回键取消；出现缩放淡入，位置变化平滑移动；</li>
 *   <li>全选：完全由模块自身实现（选区设为 0..length 并同步高亮/手柄/菜单），
 *       不调用任何系统原生文本选择 API；文本为空或已全选时不提供无效的「全选」；</li>
 *   <li>分享：标准 ACTION_SEND + createChooser 唤起系统分享面板，内容为当前实际选区，
 *       分享不破坏选择状态，失败时 Toast 提示；</li>
 *   <li>触摸：长按用 OnLongClickListener 触发（返回 true 阻止系统长按行为），
 *       拖动用 OnTouchListener 消费 MOVE/UP 并请求父容器不拦截；</li>
 *   <li>挂载时通过反射读取并链式调用 TextView 原有的 OnTouchListener / OnLongClickListener，
 *       普通点击、滚动等交互不受影响；布局/滚动变化时同步高亮、手柄与菜单位置。</li>
 * </ul>
 */
public final class CustomTextSelection {

    private static final String TAG = "BetterHeybox";

    /** 兜底强调色（解析不到 Monet / colorAccent 时使用） */
    private static final int DEFAULT_ACCENT = 0xFF1677FF;

    /** 每个 TextView 对应的选择控制器（WeakHashMap，View 回收后自动清理） */
    private static final Map<TextView, Controller> CONTROLLERS =
            Collections.synchronizedMap(new WeakHashMap<TextView, Controller>());

    private CustomTextSelection() {
    }

    /** 为 TextView 挂载自绘制选择（幂等）。 */
    public static void attach(TextView tv) {
        if (tv == null) {
            return;
        }
        synchronized (CONTROLLERS) {
            if (CONTROLLERS.containsKey(tv)) {
                return;
            }
            Controller controller = new Controller(tv);
            CONTROLLERS.put(tv, controller);
            controller.attach();
        }
    }

    /** 卸载自绘制选择并清理活动选区（幂等）。 */
    public static void detach(TextView tv) {
        if (tv == null) {
            return;
        }
        Controller controller;
        synchronized (CONTROLLERS) {
            controller = CONTROLLERS.remove(tv);
        }
        if (controller != null) {
            controller.detach();
        }
    }

    /** 取消所有活动选区与菜单（例如开关切换刷新、新长按开始前）。 */
    public static void cancelAll() {
        synchronized (CONTROLLERS) {
            for (Controller controller : CONTROLLERS.values()) {
                try {
                    controller.cancel();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * 解析当前主题的强调色：优先 Monet 动态取色（API 31+ 公开的 system_accent1_*，
     * 浅色取 600、深色取 200），其次主题 colorAccent，最后回退品牌蓝。
     */
    private static int resolveAccent(Context context) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                String shade = isDarkTheme(context) ? "600" : "200";
                int resId = context.getResources().getIdentifier(
                        "system_accent1_" + shade, "color", "android");
                if (resId != 0) {
                    int color = context.getColor(resId);
                    if (Color.alpha(color) == 255) {
                        return color;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true)
                    && value.data != 0) {
                return value.data;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_ACCENT;
    }

    private static boolean isDarkTheme(Context context) {
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 单个 TextView 的选择控制器。 */
    private static final class Controller implements View.OnTouchListener, View.OnLongClickListener {

        private final TextView tv;
        private final View.OnTouchListener prevTouch;
        private final View.OnLongClickListener prevLongClick;

        private final int accentColor;
        private final float density;

        private boolean selecting;
        private int anchor = -1;
        private int selStart = -1;
        private int selEnd = -1;
        private float downX;
        private float downY;

        private HighlightOverlay overlay;
        private SelectionHandle startHandle;
        private SelectionHandle endHandle;
        private View menuScrim;
        private LinearLayout menuBubble;
        private TextView selectAllItem;
        private View selectAllDivider;
        /** 菜单是否位于选区上方（决定展开动画的轴点） */
        private boolean menuAbove;
        /** 正在拖动的手柄：0=无，1=起点手柄，2=终点手柄 */
        private int draggingHandle;

        private final ViewTreeObserver.OnScrollChangedListener scrollListener =
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        syncOverlays(false);
                    }
                };

        private final View.OnLayoutChangeListener layoutListener = new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (l != ol || t != ot || r != or || b != ob) {
                    syncOverlays(false);
                }
            }
        };

        Controller(TextView tv) {
            this.tv = tv;
            // getOnTouchListener / getOnLongClickListener 为非公开 API，改用反射读取
            // （读取失败视为无原监听器；挂载/卸载都只是链式调用，不影响其他交互）
            this.prevTouch = readListener(tv, "mOnTouchListener");
            this.prevLongClick = readListener(tv, "mOnLongClickListener");
            this.accentColor = resolveAccent(tv.getContext());
            this.density = tv.getResources().getDisplayMetrics().density;
        }

        @SuppressWarnings("unchecked")
        private static <T> T readListener(View view, String fieldName) {
            try {
                java.lang.reflect.Field field = View.class.getDeclaredField(fieldName);
                field.setAccessible(true);
                return (T) field.get(view);
            } catch (Throwable ignored) {
                return null;
            }
        }

        void attach() {
            tv.setOnTouchListener(this);
            tv.setOnLongClickListener(this);
            tv.addOnLayoutChangeListener(layoutListener);
            // 确保不残留系统选择能力（防止系统选择 UI 与自绘制选择同时出现）
            if (tv.isTextSelectable()) {
                tv.setTextIsSelectable(false);
            }
            tv.setMovementMethod(null);
        }

        void detach() {
            cancel();
            tv.removeOnLayoutChangeListener(layoutListener);
            tv.setOnTouchListener(prevTouch);
            tv.setOnLongClickListener(prevLongClick);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = event.getX();
                    downY = event.getY();
                    if (selecting || menuScrim != null) {
                        cancel();
                    }
                    // 不消费 DOWN：允许列表滚动 / 普通点击；后续长按/拖动由本控制器接管
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                case MotionEvent.ACTION_MOVE: {
                    if (selecting) {
                        updateSelection(event.getX(), event.getY());
                        return true;
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                case MotionEvent.ACTION_UP: {
                    if (selecting) {
                        finishSelection();
                        return true;
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (selecting) {
                        cancel();
                        return true;
                    }
                    return prevTouch != null && prevTouch.onTouch(v, event);
                }
                default:
                    return prevTouch != null && prevTouch.onTouch(v, event);
            }
        }

        @Override
        public boolean onLongClick(View v) {
            if (selecting) {
                return true;
            }
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null || text.length() == 0) {
                return prevLongClick != null && prevLongClick.onLongClick(v);
            }
            // 新长按开始前，取消其他 TextView 上可能仍在活动的选区/菜单
            cancelAll();
            int offset = tv.getOffsetForPosition(downX, downY);
            if (offset < 0) {
                offset = 0;
            }
            int[] word = wordBoundary(offset);
            startSelection(word[0], word[1]);
            return true;
        }

        private void startSelection(int start, int end) {
            selecting = true;
            anchor = start;
            selStart = start;
            selEnd = end;
            if (tv.getParent() != null) {
                tv.getParent().requestDisallowInterceptTouchEvent(true);
            }
            try {
                tv.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            } catch (Throwable ignored) {
            }
            updateHighlight();
            showHandles();
        }

        private void updateSelection(float x, float y) {
            Layout layout = tv.getLayout();
            if (layout == null) {
                return;
            }
            int offset = tv.getOffsetForPosition(x, y);
            int len = tv.getText() == null ? 0 : tv.getText().length();
            if (offset < 0) {
                offset = 0;
            }
            if (offset > len) {
                offset = len;
            }
            selStart = Math.min(anchor, offset);
            selEnd = Math.max(anchor, offset);
            updateHighlight();
            updateHandlePositions();
        }

        private void finishSelection() {
            if (tv.getParent() != null) {
                tv.getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (!selecting) {
                return;
            }
            selecting = false;
            if (selStart < 0 || selEnd <= selStart) {
                cancel();
                return;
            }
            showMenu();
        }

        /** 布局 / 滚动变化后同步高亮、手柄与菜单位置。 */
        private void syncOverlays(boolean animateMenu) {
            if (!selecting && menuBubble == null) {
                return;
            }
            if (tv.getWindowToken() == null || !tv.isShown()) {
                return;
            }
            if (selecting && selStart >= 0 && selEnd > selStart) {
                ensureOverlay();
                positionOverlay();
                updateHandlePositions();
            }
            if (menuBubble != null && menuBubble.getVisibility() == View.VISIBLE) {
                positionMenuBubble(animateMenu);
            }
        }

        /* ================= 高亮 ================= */

        private void updateHighlight() {
            if (selStart >= 0 && selEnd > selStart) {
                ensureOverlay();
                if (overlay != null) {
                    overlay.setSelection(selStart, selEnd);
                    positionOverlay();
                }
            } else {
                removeOverlay();
            }
        }

        private void ensureOverlay() {
            if (overlay != null) {
                return;
            }
            ViewGroup decor = findDecor(tv);
            if (decor == null) {
                return;
            }
            HighlightOverlay o = new HighlightOverlay(tv.getContext(), tv, accentColor);
            decor.addView(o, new FrameLayout.LayoutParams(1, 1));
            overlay = o;
        }

        private void positionOverlay() {
            if (overlay == null) {
                return;
            }
            if (tv.getWindowToken() == null || !tv.isShown() || tv.getWidth() <= 0) {
                return;
            }
            int[] loc = new int[2];
            tv.getLocationInWindow(loc);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlay.getLayoutParams();
            ViewGroup parent = (ViewGroup) overlay.getParent();
            int padLeft = parent != null ? parent.getPaddingLeft() : 0;
            int padTop = parent != null ? parent.getPaddingTop() : 0;
            if (lp.width != tv.getWidth() || lp.height != tv.getHeight()
                    || lp.leftMargin != loc[0] - padLeft || lp.topMargin != loc[1] - padTop) {
                lp.width = tv.getWidth();
                lp.height = tv.getHeight();
                lp.leftMargin = loc[0] - padLeft;
                lp.topMargin = loc[1] - padTop;
                overlay.setLayoutParams(lp);
            }
            overlay.invalidate();
        }

        private void removeOverlay() {
            if (overlay != null) {
                ViewParent parent = overlay.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(overlay);
                }
                overlay = null;
            }
        }

        /* ================= 手柄 ================= */

        private final View.OnTouchListener handleTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                boolean isStart = v == startHandle;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        draggingHandle = isStart ? 1 : 2;
                        hideMenuBubble();
                        // 拖动时手柄轻微放大，提升「被抓住」的体感
                        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(70L)
                                .setInterpolator(new DecelerateInterpolator()).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (draggingHandle != 0) {
                            adjustByHandle(draggingHandle == 1, event.getRawX(), event.getRawY());
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (draggingHandle != 0) {
                            draggingHandle = 0;
                            v.animate().scaleX(1f).scaleY(1f).setDuration(90L)
                                    .setInterpolator(new DecelerateInterpolator()).start();
                            showMenuBubble();
                            return true;
                        }
                        return false;
                    default:
                        return false;
                }
            }
        };

        private void showHandles() {
            if (tv.getWindowToken() == null) {
                return;
            }
            if (startHandle == null || endHandle == null) {
                ViewGroup decor = findDecor(tv);
                if (decor == null) {
                    return;
                }
                // 视觉为约 21dp 水滴，整体 View 放大以扩大触摸区域
                int size = dp(tv.getContext(), 38);
                startHandle = new SelectionHandle(tv.getContext(), tv, accentColor);
                endHandle = new SelectionHandle(tv.getContext(), tv, accentColor);
                startHandle.setOnTouchListener(handleTouch);
                endHandle.setOnTouchListener(handleTouch);
                decor.addView(startHandle, new FrameLayout.LayoutParams(size, size));
                decor.addView(endHandle, new FrameLayout.LayoutParams(size, size));
            }
            updateHandlePositions();
        }

        private void updateHandlePositions() {
            if (startHandle == null || endHandle == null) {
                return;
            }
            Layout layout = tv.getLayout();
            if (layout == null || tv.getWindowToken() == null || !tv.isShown()) {
                return;
            }
            int[] loc = new int[2];
            tv.getLocationInWindow(loc);
            positionHandle(startHandle, loc, layout, selStart, true);
            positionHandle(endHandle, loc, layout, selEnd, false);
        }

        /** 计算选区终点（含行尾换行 / 下一行行首）的视觉 x 与所在行。 */
        private float endAnchorX(Layout layout, CharSequence text, int end, int[] outLine) {
            int line = layout.getLineForOffset(end);
            float x = layout.getPrimaryHorizontal(end);
            if (line > 0 && end == layout.getLineStart(line)) {
                // 选区结束在下一行行首：视觉终点为上一行行尾
                line = line - 1;
                x = layout.getLineRight(line);
            } else if (end > 0 && end <= text.length()
                    && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
                // 选区包含行尾换行：视觉终点为该行行尾
                x = layout.getLineRight(line);
            }
            outLine[0] = line;
            return x;
        }

        private void positionHandle(SelectionHandle handle, int[] tvLoc, Layout layout,
                                    int offset, boolean isStart) {
            if (offset < 0) {
                offset = 0;
            }
            CharSequence text = tv.getText();
            if (text == null) {
                return;
            }
            int line = layout.getLineForOffset(offset);
            float x;
            if (isStart) {
                x = layout.getPrimaryHorizontal(offset);
            } else {
                int[] out = new int[1];
                x = endAnchorX(layout, text, offset, out);
                line = out[0];
            }
            // 两个手柄都挂在选区端点所在行的行底，水滴尖端朝上指向文字
            float baseY = layout.getLineBottom(line);
            float winX = tvLoc[0] + tv.getTotalPaddingLeft() - tv.getScrollX() + x;
            float winY = tvLoc[1] + tv.getTotalPaddingTop() - tv.getScrollY() + baseY;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) handle.getLayoutParams();
            int size = lp.width;
            ViewGroup parent = (ViewGroup) handle.getParent();
            int padLeft = parent != null ? parent.getPaddingLeft() : 0;
            int padTop = parent != null ? parent.getPaddingTop() : 0;
            // 水滴尖端（View 顶部约 1dp 处）对准选区端点，主体垂在文字下方
            int left = (int) (winX - padLeft - size / 2f);
            int top = (int) (winY - padTop - density);
            if (lp.leftMargin != left || lp.topMargin != top) {
                lp.leftMargin = left;
                lp.topMargin = top;
                handle.setLayoutParams(lp);
            }
            handle.invalidate();
        }

        private void adjustByHandle(boolean isStart, float rawX, float rawY) {
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null) {
                return;
            }
            int[] loc = new int[2];
            tv.getLocationOnScreen(loc);
            int offset = tv.getOffsetForPosition(rawX - loc[0], rawY - loc[1]);
            int len = text.length();
            if (offset < 0) {
                offset = 0;
            }
            if (offset > len) {
                offset = len;
            }
            // 钳制不越过对侧手柄：交叉时保持至少 1 个字符的选区，方向由钳制自然处理
            if (isStart) {
                int maxStart = Math.max(0, selEnd - 1);
                selStart = Math.min(offset, maxStart);
            } else {
                int minEnd = Math.min(len, selStart + 1);
                selEnd = Math.max(offset, minEnd);
            }
            updateHighlight();
            updateHandlePositions();
        }

        private void removeHandles() {
            removeView(startHandle);
            removeView(endHandle);
            startHandle = null;
            endHandle = null;
        }

        /* ================= 操作菜单（遮罩 + 原生风格浮层） ================= */

        private void showMenu() {
            Context context = tv.getContext();
            if (context == null) {
                cancel();
                return;
            }
            removeMenu();
            ViewGroup decor = findDecor(tv);
            if (decor == null) {
                cancel();
                return;
            }

            // 透明遮罩：拦截所有触摸，点击/返回键即取消选区
            FrameLayout scrim = new FrameLayout(context);
            scrim.setClickable(true);
            scrim.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cancel();
                }
            });
            scrim.setFocusableInTouchMode(true);
            scrim.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                        cancel();
                        return true;
                    }
                    return false;
                }
            });
            decor.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.requestFocus();
            menuScrim = scrim;

            menuBubble = buildMenuBar(context);
            decor.addView(menuBubble, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            updateMenuItemsState();
            positionMenuBubble(false);
            animateMenuIn();

            // 遮罩/气泡后添加会盖住先添加的手柄：把手柄提到最上层，保证可拖动
            if (startHandle != null) {
                decor.bringChildToFront(startHandle);
            }
            if (endHandle != null) {
                decor.bringChildToFront(endHandle);
            }
        }

        /**
         * 定位操作菜单：优先放选区首行上方，空间不足放末行下方，仍不足则钳制在窗口内。
         * 水平方向以选区中线为中心，钳制不超出屏幕；animate=true 时平滑移动到新位置。
         */
        private void positionMenuBubble(boolean animate) {
            if (menuBubble == null) {
                return;
            }
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null) {
                return;
            }
            Context context = tv.getContext();
            ViewGroup parent = (ViewGroup) menuBubble.getParent();
            if (parent == null) {
                return;
            }
            menuBubble.measure(
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int w = menuBubble.getMeasuredWidth();
            int h = menuBubble.getMeasuredHeight();

            int padLeft = parent.getPaddingLeft();
            int padTop = parent.getPaddingTop();
            int availRight = padLeft + parent.getWidth() - parent.getPaddingRight();
            int availBottom = padTop + parent.getHeight() - parent.getPaddingBottom();
            int margin = dp(context, 4);
            int topLimit = padTop + margin;
            int leftLimit = padLeft + margin;

            int[] loc = new int[2];
            tv.getLocationInWindow(loc);
            float textLeft = loc[0] + tv.getTotalPaddingLeft() - tv.getScrollX();
            float textTop = loc[1] + tv.getTotalPaddingTop() - tv.getScrollY();

            int startLine = layout.getLineForOffset(selStart);
            int[] endLineOut = new int[1];
            float endX = endAnchorX(layout, text, selEnd, endLineOut);
            float selCenterX = textLeft
                    + (layout.getPrimaryHorizontal(selStart) + endX) / 2f;
            int selTop = (int) (textTop + layout.getLineTop(startLine));
            int selBottom = (int) (textTop + layout.getLineBottom(endLineOut[0]));

            int px = (int) (selCenterX - w / 2f);
            int py;
            int above = selTop - h - dp(context, 6);
            int below = selBottom + dp(context, 6);
            if (above >= topLimit) {
                py = above;
                menuAbove = true;
            } else if (below + h <= availBottom - margin) {
                py = below;
                menuAbove = false;
            } else {
                // 上下都放不下：钳制在窗口内（允许遮住部分文本，与原生行为一致）
                py = Math.max(topLimit, Math.min(above, availBottom - margin - h));
                menuAbove = py <= selTop;
            }
            if (px < leftLimit) {
                px = leftLimit;
            }
            if (px + w > availRight - margin) {
                px = availRight - margin - w;
            }

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuBubble.getLayoutParams();
            int oldLeft = lp.leftMargin;
            int oldTop = lp.topMargin;
            boolean moved = lp.leftMargin != px || lp.topMargin != py
                    || lp.width != w || lp.height != h;
            lp.width = w;
            lp.height = h;
            lp.leftMargin = px;
            lp.topMargin = py;
            if (moved) {
                menuBubble.setLayoutParams(lp);
            }
            if (animate && moved) {
                // 先瞬移到新位置再用 translation 补偿回旧位置，动画归零实现平滑移动
                menuBubble.setTranslationX(oldLeft - px);
                menuBubble.setTranslationY(oldTop - py);
                menuBubble.animate().translationX(0f).translationY(0f).setDuration(140L)
                        .setInterpolator(new DecelerateInterpolator(1.5f)).start();
            } else {
                menuBubble.setTranslationX(0f);
                menuBubble.setTranslationY(0f);
            }
        }

        /** 菜单出现动画：从选区方向缩放淡入（约 130ms，克制不喧宾夺主）。 */
        private void animateMenuIn() {
            if (menuBubble == null) {
                return;
            }
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuBubble.getLayoutParams();
            menuBubble.setPivotX(lp.width / 2f);
            menuBubble.setPivotY(menuAbove ? lp.height : 0f);
            menuBubble.setScaleX(0.86f);
            menuBubble.setScaleY(0.86f);
            menuBubble.setAlpha(0f);
            menuBubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130L)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        }

        private void hideMenuBubble() {
            if (menuBubble != null) {
                menuBubble.animate().cancel();
                menuBubble.setVisibility(View.GONE);
            }
        }

        private void showMenuBubble() {
            if (menuBubble != null) {
                updateMenuItemsState();
                menuBubble.setVisibility(View.VISIBLE);
                positionMenuBubble(true);
            }
        }

        /** 根据当前选区状态刷新「全选」等条目的可见性。 */
        private void updateMenuItemsState() {
            if (selectAllItem == null) {
                return;
            }
            CharSequence text = tv.getText();
            boolean canSelectAll = text != null && text.length() > 0
                    && (selStart > 0 || selEnd < text.length());
            if (selectAllItem.getVisibility() != (canSelectAll ? View.VISIBLE : View.GONE)) {
                selectAllItem.setVisibility(canSelectAll ? View.VISIBLE : View.GONE);
                if (selectAllDivider != null) {
                    selectAllDivider.setVisibility(canSelectAll ? View.VISIBLE : View.GONE);
                }
            }
        }

        private void removeMenu() {
            removeView(menuScrim);
            removeView(menuBubble);
            menuScrim = null;
            menuBubble = null;
            selectAllItem = null;
            selectAllDivider = null;
        }

        private void removeView(View view) {
            if (view != null) {
                ViewParent parent = view.getParent();
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(view);
                }
            }
        }

        /** 构建原生风格的操作菜单：全选 · 分享 · 复制（全选不可用时自动隐藏）。 */
        private LinearLayout buildMenuBar(Context context) {
            boolean dark = isDarkTheme(context);
            int bgColor = dark ? 0xFF2A2A2E : 0xFFFFFFFF;
            int textColor = dark ? 0xDEFFFFFF : 0xDD000000;
            int dividerColor = dark ? 0x24FFFFFF : 0x1F000000;
            int rippleColor = dark ? 0x2EFFFFFF : 0x1A000000;

            LinearLayout bar = new LinearLayout(context);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setClickable(true); // 点击菜单空白处不触发遮罩取消
            bar.setElevation(dp(context, 8));

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bgColor);
            bg.setCornerRadius(dp(context, 16));
            bar.setBackground(bg);

            // 全选（仅当文本非空且尚未全选时可见）
            TextView selectAll = menuItem(context, "全选", textColor, rippleColor, dark);
            selectAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doSelectAll();
                }
            });
            bar.addView(selectAll);
            selectAllItem = selectAll;
            selectAllDivider = addDivider(bar, context, dividerColor);

            // 分享
            TextView share = menuItem(context, "分享", textColor, rippleColor, dark);
            share.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doShare();
                }
            });
            bar.addView(share);
            addDivider(bar, context, dividerColor);

            // 复制
            TextView copy = menuItem(context, "复制", textColor, rippleColor, dark);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    doCopy();
                }
            });
            bar.addView(copy);
            return bar;
        }

        /** 菜单项之间的原生风格短竖线分割。 */
        private View addDivider(LinearLayout bar, Context context, int color) {
            View divider = new View(context);
            divider.setBackgroundColor(color);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(context, 1), dp(context, 20));
            lp.gravity = Gravity.CENTER_VERTICAL;
            divider.setLayoutParams(lp);
            bar.addView(divider);
            return divider;
        }

        private TextView menuItem(Context context, String label, int textColor,
                                  int rippleColor, boolean dark) {
            TextView item = new TextView(context);
            item.setText(label);
            item.setTextSize(14);
            item.setTextColor(textColor);
            item.setGravity(Gravity.CENTER);
            item.setMinWidth(dp(context, 56));
            item.setMinHeight(dp(context, 44));
            item.setPadding(dp(context, 14), 0, dp(context, 14), 0);
            item.setClickable(true);
            item.setFocusable(true);
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(dp(context, 12));
            item.setForeground(new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask));
            return item;
        }

        /* ================= 全选 / 复制 / 分享 ================= */

        /** 全选：完全由模块自身实现，不调用系统原生文本选择 API。 */
        private void doSelectAll() {
            CharSequence text = tv.getText();
            if (text == null || text.length() == 0) {
                return;
            }
            selStart = 0;
            selEnd = text.length();
            updateHighlight();
            updateHandlePositions();
            // 菜单保持显示：刷新条目可用状态（全选后隐藏「全选」），并平滑移到新选区附近
            updateMenuItemsState();
            if (menuBubble != null && menuBubble.getVisibility() == View.VISIBLE) {
                positionMenuBubble(true);
            }
        }

        private void doCopy() {
            CharSequence text = tv.getText();
            Context context = tv.getContext();
            if (text == null || selStart < 0 || selEnd <= selStart || selEnd > text.length()) {
                cancel();
                return;
            }
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("BetterHeybox", text.subSequence(selStart, selEnd)));
                }
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Log.w(TAG, "自绘制选择复制失败: " + t);
            }
            cancel();
        }

        /** 通过系统标准分享机制（ACTION_SEND + createChooser）分享当前选中文本。 */
        private void doShare() {
            CharSequence text = tv.getText();
            Context context = tv.getContext();
            if (text == null || selStart < 0 || selEnd <= selStart || selEnd > text.length()) {
                return;
            }
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text.subSequence(selStart, selEnd).toString());
                Intent chooser = Intent.createChooser(send, null);
                // 保持选择状态：不取消选区/菜单，用户从分享面板返回后可继续操作
                Activity activity = findActivity(context);
                if (activity != null) {
                    activity.startActivity(chooser);
                } else {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(chooser);
                }
            } catch (Throwable t) {
                Log.w(TAG, "自绘制选择分享失败: " + t);
                Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show();
            }
        }

        private static Activity findActivity(Context context) {
            while (context instanceof ContextWrapper) {
                if (context instanceof Activity) {
                    return (Activity) context;
                }
                context = ((ContextWrapper) context).getBaseContext();
            }
            return null;
        }

        /* ================= 清理 ================= */

        /** 取消选区并关闭菜单/手柄/高亮（保持控制器可用）。 */
        void cancel() {
            if (tv.getParent() != null) {
                tv.getParent().requestDisallowInterceptTouchEvent(false);
            }
            selecting = false;
            draggingHandle = 0;
            anchor = -1;
            selStart = -1;
            selEnd = -1;
            try {
                tv.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
            } catch (Throwable ignored) {
            }
            removeOverlay();
            removeHandles();
            removeMenu();
        }

        private static ViewGroup findDecor(View v) {
            Context context = v.getContext();
            while (context instanceof ContextWrapper) {
                if (context instanceof Activity) {
                    View decor = ((Activity) context).getWindow().getDecorView();
                    if (decor instanceof ViewGroup) {
                        return (ViewGroup) decor;
                    }
                }
                context = ((ContextWrapper) context).getBaseContext();
            }
            return null;
        }

        /** 把偏移量扩展为「词」范围：拉丁按空白/标点分词，中文按连续汉字成词。 */
        private int[] wordBoundary(int offset) {
            CharSequence text = tv.getText();
            int len = text == null ? 0 : text.length();
            if (len == 0) {
                return new int[]{0, 0};
            }
            int o = offset;
            if (o < 0) {
                o = 0;
            }
            if (o > len) {
                o = len;
            }
            char c = o < len ? text.charAt(o) : text.charAt(len - 1);
            if (Character.isWhitespace(c)) {
                // 停在空白/换行上：优先取前方最近的字符并扩展其所在词（不选中空白）
                int probe = o;
                while (probe > 0 && Character.isWhitespace(text.charAt(probe - 1))) {
                    probe--;
                }
                if (probe > 0) {
                    return wordBoundary(probe - 1);
                }
                // 前方全是空白：取后方第一个字符
                probe = o;
                while (probe < len && Character.isWhitespace(text.charAt(probe))) {
                    probe++;
                }
                if (probe < len) {
                    return wordBoundary(probe);
                }
                return new int[]{Math.max(0, o - 1), Math.min(len, o + 1)};
            }
            boolean cjk = isCjkIdeograph(c);
            int s = o;
            int e = o;
            while (s > 0 && isWordChar(text.charAt(s - 1), cjk)) {
                s--;
            }
            while (e < len && isWordChar(text.charAt(e), cjk)) {
                e++;
            }
            if (s == e) {
                e = Math.min(len, o + 1);
            }
            return new int[]{s, e};
        }

        private static boolean isWordChar(char ch, boolean cjk) {
            if (Character.isWhitespace(ch)) {
                return false;
            }
            if (cjk) {
                return isCjkIdeograph(ch);
            }
            return Character.isLetterOrDigit(ch) || ch == '_' || ch == 0x27;
        }

        private static boolean isCjkIdeograph(char ch) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                    || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                    || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
        }
    }

    /**
     * 选区高亮叠加 View：覆盖在被选 TextView 上方，按 Layout 逐行绘制圆角矩形，
     * 并对相邻行做 UNION 合并，多行选区外侧圆角自然、行间无生硬接缝。
     *
     * <p>不消费触摸事件（不可点击、无监听器），触摸会穿透到下层 View。</p>
     */
    private static final class HighlightOverlay extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path linePath = new Path();
        private final RectF lineRect = new RectF();
        private final WeakReference<TextView> tvRef;
        private final float radius;
        private int start = -1;
        private int end = -1;

        HighlightOverlay(Context context, TextView tv, int accentColor) {
            super(context);
            this.tvRef = new WeakReference<TextView>(tv);
            // 半透明强调色高亮，深/浅色主题下均自然柔和
            this.paint.setColor(0x59000000 | (accentColor & 0x00FFFFFF));
            this.paint.setStyle(Paint.Style.FILL);
            this.radius = 3 * context.getResources().getDisplayMetrics().density;
        }

        void setSelection(int start, int end) {
            if (this.start == start && this.end == end) {
                return;
            }
            this.start = start;
            this.end = end;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            TextView tv = tvRef.get();
            if (tv == null || start < 0 || end <= start) {
                return;
            }
            Layout layout = tv.getLayout();
            CharSequence text = tv.getText();
            if (layout == null || text == null) {
                return;
            }
            int first = layout.getLineForOffset(start);
            int last = layout.getLineForOffset(end);
            float lastRight = layout.getPrimaryHorizontal(end);
            if (last > 0 && end == layout.getLineStart(last)) {
                // 选区结束在下一行行首：视觉上到上一行行尾
                last--;
                lastRight = layout.getLineRight(last);
            } else if (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
                // 选区包含行尾换行：该行高亮到行尾
                lastRight = layout.getLineRight(last);
            }
            path.rewind();
            for (int i = first; i <= last; i++) {
                float left = (i == first) ? layout.getPrimaryHorizontal(start) : layout.getLineLeft(i);
                float right = (i == last) ? lastRight : layout.getLineRight(i);
                if (right < left) {
                    float t = left;
                    left = right;
                    right = t;
                }
                if (right - left < radius * 2f) {
                    // 空行/换行行：保留极窄高亮条，避免完全消失
                    right = Math.min(layout.getLineRight(i), left + radius * 2f);
                }
                lineRect.set(left, layout.getLineTop(i), right, layout.getLineBottom(i));
                linePath.rewind();
                linePath.addRoundRect(lineRect, radius, radius, Path.Direction.CW);
                if (path.isEmpty()) {
                    path.set(linePath);
                } else {
                    path.op(linePath, Path.Op.UNION);
                }
            }
            if (path.isEmpty()) {
                return;
            }
            canvas.save();
            // Layout 选区路径以文本区原点（padding 左上角 - scroll）为基准，平移后与叠加 View 对齐
            canvas.translate(
                    tv.getTotalPaddingLeft() - tv.getScrollX(),
                    tv.getTotalPaddingTop() - tv.getScrollY());
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    /**
     * 选区调节手柄：原生风格水滴形（圆形主体 + 朝上指向文字的尖端，带柔和阴影），
     * 首尾手柄统一挂在选区端点所在行的行底（文字下方），可拖动调整选区范围。
     *
     * <p>手柄是 Decor 叠加层上的独立 View，触摸事件由控制器（handleTouch）消费；
     * View 尺寸大于视觉水滴，以放大实际触摸区域。</p>
     */
    private static final class SelectionHandle extends View {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final WeakReference<TextView> tvRef;
        private final float density;
        private final Path dropPath = new Path();

        SelectionHandle(Context context, TextView tv, int accentColor) {
            super(context);
            this.tvRef = new WeakReference<TextView>(tv);
            this.density = context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accentColor);
            // 阴影需软件层（View 很小，开销可忽略）
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            paint.setShadowLayer(1.5f * density, 0f, density, 0x30000000);
            setPivotX(getWidth() / 2f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            rebuildDrop(w, h);
            // 拖动放大动画的轴点在水滴尖端（View 顶部）
            setPivotX(w / 2f);
            setPivotY(0f);
        }

        /** 生成水滴形路径：尖朝上的圆 + 尖端三角 UNION，避免两形状叠加出现抗锯齿接缝。 */
        private void rebuildDrop(int w, int h) {
            if (w <= 0 || h <= 0) {
                return;
            }
            float r = w * 0.30f;
            float cx = w / 2f;
            float tipGap = 1f * density;
            float neckGap = 3f * density;
            float tipY = tipGap;
            float cy = tipY + neckGap + r;
            dropPath.reset();
            dropPath.addCircle(cx, cy, r, Path.Direction.CW);
            Path tip = new Path();
            tip.moveTo(cx, tipY);
            float baseY = cy - r * 0.30f;
            tip.lineTo(cx - r * 0.62f, baseY);
            tip.lineTo(cx + r * 0.62f, baseY);
            tip.close();
            dropPath.op(tip, Path.Op.UNION);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            TextView tv = tvRef.get();
            if (tv == null || dropPath.isEmpty()) {
                return;
            }
            canvas.drawPath(dropPath, paint);
        }
    }
}
