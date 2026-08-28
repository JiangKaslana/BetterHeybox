package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import com.better.heybox.App;
import com.better.heybox.CustomTextSelection;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;
import com.better.heybox.SelectionSafeLinkMovementMethod;

/**
 * 帖子正文/标题复制。
 *
 * 设计原则（默认「自绘制文本选择」关闭）：
 * 1. 只解除小黑盒 TextSelectHandler 对触摸事件的自定义拦截（防复制）。
 * 2. 正文 TextView 使用 Android 原生 textIsSelectable 文本选择。
 * 3. 正文挂透明 LinkMovementMethod（SelectionSafeLinkMovementMethod），
 *    保留正文内 @提及 / ClickableSpan 的点击跳转。
 * 4. 头部用户名 TextView（bbs_name 等）仅开启原生长按选择（textIsSelectable），
 *    不设 movement method，不修改点击行为。
 * 5. 不 Hook Selection.setSelection / removeSelection。
 * 6. 不拦截 TextView 的 DOWN / UP / MOVE。
 * 7. 不修改 NestedScrollView 的 onInterceptTouchEvent。
 *
 * 「自绘制文本选择」（{@link App#KEY_CUSTOM_TEXT_SELECT}）开启时：
 * 1. 不开启 textIsSelectable、不挂 LinkMovementMethod，彻底绕开系统选择 UI；
 * 2. 选区/高亮/复制/取消由 {@link CustomTextSelection} 自绘实现；
 * 3. 开关切换后通过 {@link #refresh()} 对已展示的帖子立即重放，无需重启。
 */
public final class TextSelectHook {

    private final MainModule module;

    /** 最近一次构造的实例（refresh 用） */
    private static volatile TextSelectHook sInstance;

    /** 已注入过文本选择的根 View（WeakReference，随 Fragment 回收自动清理） */
    private static final List<WeakReference<View>> sRegisteredRoots = new ArrayList<>();

    public TextSelectHook(MainModule module) {
        this.module = module;
        sInstance = this;
    }

    /** 开关切换/配置导入后，对已注册的帖子根 View 立即重放文本选择设置。 */
    public static void refresh() {
        TextSelectHook instance = sInstance;
        if (instance != null) {
            instance.refreshAll();
        }
    }

    public void install(ClassLoader cl) {
        hookTextSelectHandler(cl);
        hookPostTextSelect(cl);
    }

    private void hookTextSelectHandler(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName(
                    "com.max.common.common.selecthandler.TextSelectHandler",
                    false,
                    cl
            );

            Method onTouch = null;

            for (Method m : handler.getDeclaredMethods()) {
                if ("onTouch".equals(m.getName())
                        && m.getParameterCount() == 2) {
                    onTouch = m;
                    break;
                }
            }

            if (onTouch == null) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "✘ 未找到 TextSelectHandler.onTouch"
                );
                return;
            }
            module.hook(onTouch).intercept(chain -> false);

            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ TextSelectHandler 防复制拦截已解除"
            );

        } catch (Throwable t) {
            module.logd(
                    Log.ERROR,
                    module.TAG,
                    "✘ TextSelectHandler 解除失败",
                    t
            );
        }
    }

    private void hookPostTextSelect(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName(
                    "com.max.xiaoheihe.module.bbs.post.ui.fragments.v2.PostPictureFragmentV2",
                    false,
                    cl
            );

            Method target = null;

            for (Method m : clazz.getDeclaredMethods()) {
                if ("installViews".equals(m.getName())
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == View.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                for (Method m : clazz.getDeclaredMethods()) {
                    if (m.getName().startsWith("installViews")
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == View.class) {
                        target = m;
                        break;
                    }
                }
            }

            if (target == null) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "✘ 未找到 PostPictureFragmentV2.installViews"
                );
                return;
            }

            module.hook(target).intercept(chain -> {

                Object result = chain.proceed();

                try {
                    Object arg = chain.getArg(0);

                    if (arg instanceof View) {
                        scheduleEnableTextSelect((View) arg, 0);
                        registerRoot((View) arg);
                    }

                } catch (Throwable t) {
                    module.logd(
                            Log.WARN,
                            module.TAG,
                            "正文选择调度异常: " + t
                    );
                }

                return result;
            });

            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ 帖子正文原生文本选择 Hook 已安装"
            );

        } catch (Throwable t) {
            module.logd(
                    Log.ERROR,
                    module.TAG,
                    "✘ 帖子正文复制 Hook 失败",
                    t
            );
        }
    }

    private void registerRoot(View root) {
        if (root == null) {
            return;
        }
        synchronized (sRegisteredRoots) {
            for (WeakReference<View> ref : sRegisteredRoots) {
                if (ref.get() == root) {
                    return;
                }
            }
            sRegisteredRoots.add(new WeakReference<View>(root));
        }
    }

    private void refreshAll() {
        synchronized (sRegisteredRoots) {
            for (WeakReference<View> ref : sRegisteredRoots) {
                View root = ref.get();
                if (root == null) {
                    continue;
                }
                try {
                    enablePostTextSelect(root);
                } catch (Throwable t) {
                    module.logd(
                            Log.WARN,
                            module.TAG,
                            "刷新文本选择设置异常: " + t
                    );
                }
            }
        }
    }

    private void scheduleEnableTextSelect(
            final View content,
            final int attempt
    ) {
        if (content == null) {
            return;
        }
        if (attempt > 15) {
            module.logd(
                    Log.WARN,
                    module.TAG,
                    "正文 View 长时间未就绪，放弃开启文本选择"
            );
            return;
        }

        long delay = attempt == 0 ? 200L : 150L;

        content.postDelayed(() -> {
            try {
                if (content.isShown()
                        && content.getWidth() > 0
                        && content.getHeight() > 0) {

                    enablePostTextSelect(content);

                } else {
                    scheduleEnableTextSelect(
                            content,
                            attempt + 1
                    );
                }

            } catch (Throwable t) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "正文选择调度异常: " + t
                );
            }
        }, delay);
    }

    private void enablePostTextSelect(View root) {

        if (root == null) {
            return;
        }

        LogRecorder.setContext(root.getContext());

        if (!module.isEnabled(App.KEY_COPY_POST, true)) {
            return;
        }

        boolean customSelect = module.isEnabled(App.KEY_CUSTOM_TEXT_SELECT, false);

        String[] idNames = {
                "tv_title",
                "tv_desc"
        };

        for (String idName : idNames) {
            try {
                int id = root.getResources().getIdentifier(
                        idName,
                        "id",
                        MainModule.TARGET_PKG
                );

                if (id == 0) {
                    continue;
                }

                View v = root.findViewById(id);

                if (!(v instanceof TextView)) {
                    continue;
                }

                applyTextSelect((TextView) v, idName, true, customSelect);

            } catch (Throwable t) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "设置文本选择失败 (" + idName + "): " + t
                );
            }
        }

        /*
         * 头部用户名：默认仅开启原生长按选择（复制），
         * 自绘制模式下由模块接管长按选择，均不修改点击行为。
         */
        String[] usernameIds = {
                "bbs_name", "bbs_username", "bbs_user_name", "tv_post_author",
                "tv_author", "tv_username", "tv_nickname", "tv_user_name",
                "tv_userinfo", "tv_user_info", "author_name", "username",
                "tv_name", "tv_user", "tv_author_name"
        };

        for (String idName : usernameIds) {
            try {
                int uid = root.getResources().getIdentifier(
                        idName,
                        "id",
                        MainModule.TARGET_PKG
                );
                if (uid == 0) {
                    continue;
                }
                View uv = root.findViewById(uid);
                if (!(uv instanceof TextView)) {
                    continue;
                }
                applyTextSelect((TextView) uv, idName, false, customSelect);
            } catch (Throwable t) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "设置用户名长按选择失败 (" + idName + "): " + t
                );
            }
        }
    }

    /**
     * 对单个 TextView 应用文本选择：
     * <ul>
     *   <li>自定义模式（自绘制文本选择）：卸载旧的自绘制控制器后重新挂载，并关闭
     *       textIsSelectable / movement method，保证系统选择 UI 不会与自绘制选区同时出现；</li>
     *   <li>原生模式：恢复系统标准文本选择（正文另挂透明 LinkMovementMethod 保留 @提及点击）。</li>
     * </ul>
     */
    private void applyTextSelect(TextView tv, String idName, boolean body, boolean customSelect) {
        // 先卸载旧的自绘制控制器，避免刷新时新旧逻辑叠加
        CustomTextSelection.detach(tv);

        if (customSelect) {
            if (tv.isTextSelectable()) {
                tv.setTextIsSelectable(false);
            }
            tv.setMovementMethod(null);
            CustomTextSelection.attach(tv);
            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ 已启用自绘制文本选择: " + idName
            );
            return;
        }

        if (!tv.isTextSelectable()) {
            tv.setTextIsSelectable(true);
            module.logd(
                    Log.INFO,
                    module.TAG,
                    "✔ 已开启标准文本选择: " + idName
            );
        }
        if (body) {
            tv.setLinksClickable(true);
            tv.setMovementMethod(SelectionSafeLinkMovementMethod.getInstance());
        }
    }
}
