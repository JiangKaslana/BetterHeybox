package com.better.heybox.hooks;

import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;

/**
 * 帖子正文复制：解除 TextSelectHandler 长按拦截、恢复系统标准文本选择、拖动跨行选择修复。
 */
public final class TextSelectHook {

    private final MainModule module;

    public TextSelectHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookTextSelectHandler(cl);
        hookPostTextSelect(cl);
        hookScrollIntercept(cl);
    }

    /**
     * 解除小黑盒自定义 TextSelectHandler 的长按拦截（防复制机制的核心）。
     *
     * 小黑盒在 SDK>26 时给正文/标题 TextView 设置 TextSelectHandler 触摸拦截：
     * onTouch 消费长按，交给 TextSelectViewManager 自定义选择（复制被截断成「盒」）。
     * 这里 hook TextSelectHandler.onTouch 恒返回 false，让长按事件继续传递，
     * 配合 enablePostTextSelect 的 setTextIsSelectable(true)，长按正文即弹安卓系统
     * 「复制/全选」菜单，采用系统复制。全局生效且不惧小黑盒事后重设拦截。
     */
    private void hookTextSelectHandler(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName(
                    "com.max.common.common.selecthandler.TextSelectHandler", false, cl);
            Method onTouch = null;
            for (Method m : handler.getDeclaredMethods()) {
                if ("onTouch".equals(m.getName()) && m.getParameterCount() == 2) {
                    onTouch = m;
                    break;
                }
            }
            if (onTouch == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 TextSelectHandler.onTouch");
                return;
            }
            module.hook(onTouch).intercept(chain -> false); // 不消费任何触摸 → 长按回到 TextView 默认逻辑
            module.logd(Log.INFO, module.TAG, "✔ TextSelectHandler 防复制拦截已解除");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ TextSelectHandler 解除失败", t);
        }
    }

    /**
     * 让帖子详情页标题/正文 TextView 恢复安卓系统标准文本选择（textIsSelectable）。
     *
     * 小黑盒在 SDK>26 时只设置 TextSelectHandler 而不调用 setTextIsSelectable(true)
     * （标准分支仅在 SDK<=26 生效）。这里在 installViews 后强制开启，
     * 长按正文即弹出系统「复制/全选」菜单。
     */
    private void hookPostTextSelect(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName(
                    "com.max.xiaoheihe.module.bbs.post.ui.fragments.v2.PostPictureFragmentV2", false, cl);
            Method target = null;
            for (Method m : clazz.getDeclaredMethods()) {
                // Robust 可能重命名方法，按前缀 + 签名匹配
                if (m.getName().startsWith("installViews") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == View.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 PostPictureFragmentV2.installViews");
                return;
            }
            module.hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof View) {
                        scheduleEnableTextSelect((View) arg, 0);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "正文选择调度异常: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 帖子正文复制 Hook 已安装");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 帖子正文复制 Hook 失败", t);
        }
    }

    /**
     * 等布局稳定后恢复标准文本选择。
     *
     * 长文正文渲染较慢，固定延时可能打断渲染导致文字短暂消失；
     * 改为「布局就绪（已显示且有尺寸）才应用」，未就绪则短间隔重试，最长约 3 秒。
     */
    private void scheduleEnableTextSelect(final View content, final int attempt) {
        if (attempt > 15) {
            return; // 约 3 秒仍未就绪则放弃，避免无限重试
        }
        content.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (content.isShown() && content.getWidth() > 0 && content.getHeight() > 0) {
                        enablePostTextSelect(content);
                    } else {
                        scheduleEnableTextSelect(content, attempt + 1);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "正文选择调度异常: " + t);
                }
            }
        }, attempt == 0 ? 200 : 150);
    }

    /** 恢复标题/正文 TextView 的系统标准文本选择 */
    private void enablePostTextSelect(View root) {
        LogRecorder.setContext(root.getContext());
        if (!module.isEnabled(App.KEY_COPY_POST, true)) {
            return;
        }
        String[] idNames = {"tv_title", "tv_desc"};
        for (String idName : idNames) {
            try {
                int id = root.getResources().getIdentifier(idName, "id", MainModule.TARGET_PKG);
                if (id == 0) {
                    continue;
                }
                View v = root.findViewById(id);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    // 幂等：已开启且 movement method 已是 LinkMovementMethod 则跳过，
                    // 避免重复 setTextIsSelectable 触发长文重排闪烁
                    if (tv.isTextSelectable()
                            && tv.getMovementMethod() instanceof LinkMovementMethod) {
                        continue;
                    }
                    tv.setTextIsSelectable(true);
                    tv.setLinksClickable(true);
                    tv.setMovementMethod(LinkMovementMethod.getInstance());
                    try {
                        tv.setCustomSelectionActionModeCallback(null);
                    } catch (Throwable ignored) {
                    }
                    module.logd(Log.INFO, module.TAG, "✔ 已开启标准文本选择: " + idName);
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "设置文本选择失败 (" + idName + "): " + t);
            }
        }
    }

    /**
     * 修复「拖动选择只能拉一行」：正文 TextView 在滚动容器（NestedScrollView）里，
     * 拖动选择手柄时触摸被滚动容器拦截（被当成页面滚动），选择无法跨行扩展。
     * 这里 hook 滚动容器的 onInterceptTouchEvent：当子树内有处于选择模式的
     * 可选择 TextView（hasSelection）时放行触摸（返回 false），让选择手柄拖动直达
     * TextView；平时无选择时行为不变，滚动正常。
     */
    private void hookScrollIntercept(ClassLoader cl) {
        String[] classes = {
                "androidx.core.widget.NestedScrollView",
                "android.widget.NestedScrollView",
        };
        for (String name : classes) {
            try {
                Class<?> c = Class.forName(name, false, cl);
                Method m = null;
                for (Method mm : c.getDeclaredMethods()) {
                    if ("onInterceptTouchEvent".equals(mm.getName())
                            && mm.getParameterCount() == 1
                            && mm.getParameterTypes()[0] == android.view.MotionEvent.class) {
                        m = mm;
                        break;
                    }
                }
                if (m == null) {
                    continue;
                }
                module.hook(m).intercept(chain -> {
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof ViewGroup && hasSelectingTextView((ViewGroup) self)) {
                            return false;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                module.logd(Log.INFO, module.TAG, "✔ 滚动容器选择放行 Hook 已安装: " + name);
            } catch (Throwable ignored) {
            }
        }
    }
    private boolean hasSelectingTextView(ViewGroup root) {
        if (root == null) {
            return false;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof TextView) {
                TextView tv = (TextView) child;
                try {
                    if (tv.isTextSelectable() && tv.hasSelection()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            } else if (child instanceof ViewGroup) {
                if (hasSelectingTextView((ViewGroup) child)) {
                    return true;
                }
            }
        }
        return false;
    }
}
