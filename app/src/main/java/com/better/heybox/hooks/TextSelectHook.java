package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.widget.TextView;

import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.LogRecorder;
import com.better.heybox.SelectionSafeLinkMovementMethod;

/**
 * 帖子正文/标题复制。
 *
 * 设计原则：
 * 1. 只解除小黑盒 TextSelectHandler 对触摸事件的自定义拦截（防复制）。
 * 2. 正文 TextView 使用 Android 原生 textIsSelectable 文本选择。
 * 3. 正文挂透明 LinkMovementMethod（SelectionSafeLinkMovementMethod），
 *    保留正文内 @提及 / ClickableSpan 的点击跳转。
 * 4. 头部用户名 TextView（bbs_name 等）仅开启原生长按选择（textIsSelectable），
 *    不设 movement method，不修改点击行为。
 * 5. 不 Hook Selection.setSelection / removeSelection。
 * 6. 不拦截 TextView 的 DOWN / UP / MOVE。
 * 7. 不修改 NestedScrollView 的 onInterceptTouchEvent。
 */
public final class TextSelectHook {

    private final MainModule module;

    public TextSelectHook(MainModule module) {
        this.module = module;
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

                TextView tv = (TextView) v;
                if (!tv.isTextSelectable()) {
                    tv.setTextIsSelectable(true);

                    module.logd(
                            Log.INFO,
                            module.TAG,
                            "✔ 已开启标准文本选择: " + idName
                    );
                }
                tv.setLinksClickable(true);
                tv.setMovementMethod(SelectionSafeLinkMovementMethod.getInstance());

            } catch (Throwable t) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "设置文本选择失败 (" + idName + "): " + t
                );
            }
        }

        /*
         * 头部用户名：仅开启原生长按选择（复制），
         * 不设置 movement method，不修改点击行为。
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
                TextView utv = (TextView) uv;
                if (!utv.isTextSelectable()) {
                    utv.setTextIsSelectable(true);
                    module.logd(
                            Log.INFO,
                            module.TAG,
                            "✔ 用户名已开启长按选择: " + idName
                    );
                }
            } catch (Throwable t) {
                module.logd(
                        Log.WARN,
                        module.TAG,
                        "设置用户名长按选择失败 (" + idName + "): " + t
                );
            }
        }
    }
}
