package com.better.heybox.hooks;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.MainModule;

/**
 * 每日任务自动化：自动完成小黑盒每日「分享帖子」任务（3 种分享类型）。
 *
 * <p>小黑盒每日任务包含 3 种分享类型，依次完成：</p>
 * <ol>
 *   <li><b>图片帖分享</b>：打开图片帖（{@code PicturePostPageActivityV2}）→ 自动点分享按钮；</li>
 *   <li><b>普通帖分享</b>：打开普通帖（{@code NormalPostPageActivity}）→ 自动点分享按钮；</li>
 *   <li><b>频道关注</b>：打开频道/游戏页（{@code ChannelsDetailActivity}）→ 自动点「更多」按钮。</li>
 * </ol>
 *
 * <p>3 种类型的帖子链接由用户在模块设置中分别配置（图片帖/普通帖/频道）。</p>
 *
 * <p>完成机制（不拦截 QQ）：Hook {@code ShareUtils.P/y(Context, HBShareData)}，自动化进行中直接触发
 * {@code HBShareData.shareListener}（UMShareListener）的 {@code onResult} —— 只触发任务自身回调，
 * 不伪造/拦截 QQ SDK，用户手动 QQ 分享不受影响。</p>
 */
public final class DailyTaskHook {

    /** 3 种分享类型步骤 */
    private static final int STEP_PICTURE = 0;
    private static final int STEP_NORMAL = 1;
    private static final int STEP_CHANNEL = 2;
    private static final int STEP_COUNT = 3;

    private final MainModule module;

    /** 自动化进行中标记（仅自动化流程内生效） */
    private volatile boolean autoActive;
    private volatile int currentStep = -1;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 点击分享/更多按钮前的等待（页面渲染） */
    private static final long CLICK_DELAY_MS = 1500L;
    /** 单步看门狗：超时未完成则跳过（避免卡死） */
    private static final long STEP_TIMEOUT_MS = 25000L;

    public DailyTaskHook(MainModule module) {
        this.module = module;
    }

    public void install(ClassLoader cl) {
        hookShareUtils(cl);
        hookMainResume(cl);
        hookSharePages(cl);
        module.logd(Log.INFO, module.TAG, "✔ 每日任务 Hook 安装完成");
    }

    // ---------- 1. 分享完成核心：ShareUtils.P / y ----------
    private void hookShareUtils(ClassLoader cl) {
        try {
            Class<?> shareUtils = Class.forName("com.max.hbshare.ShareUtils", false, cl);
            Class<?> hbShareData = Class.forName("com.max.hbshare.bean.HBShareData", false, cl);
            boolean hooked = false;
            for (Method m : shareUtils.getDeclaredMethods()) {
                if (!"P".equals(m.getName()) && !"y".equals(m.getName())) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length != 2 || pts[0] != Context.class || pts[1] != hbShareData) {
                    continue;
                }
                module.hook(m).intercept(chain -> {
                    if (!autoActive) {
                        return chain.proceed();
                    }
                    try {
                        Object data = chain.getArg(1);
                        if (data != null) {
                            Object ctx = chain.getArg(0);
                            completeShare(data, ctx, cl);
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "每日任务完成回调异常: " + t);
                    }
                    return null; // 跳过分享弹窗，视为已分享
                });
                hooked = true;
                module.logd(Log.INFO, module.TAG, "✔ 分享完成 Hook 已安装: ShareUtils." + m.getName());
            }
            if (!hooked) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 ShareUtils.P/y(Context,HBShareData)");
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 分享完成 Hook 失败", t);
        }
    }

    /** 触发 HBShareData.shareListener.onResult(SHARE_MEDIA.QQ)，让小黑盒判定分享成功 */
    private void completeShare(Object hbShareData, Object ctx, ClassLoader cl) throws Throwable {
        Class<?> shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl);
        Object qq = Enum.valueOf((Class<Enum>) shareMedia, "QQ");
        Field listenerField = hbShareData.getClass().getDeclaredField("shareListener");
        listenerField.setAccessible(true);
        Object listener = listenerField.get(hbShareData);
        if (listener == null) {
            module.logd(Log.WARN, module.TAG, "HBShareData.shareListener 为 null，跳过");
            return;
        }
        Method onResult = listener.getClass().getMethod("onResult", shareMedia);
        onResult.invoke(listener, qq);
        module.logd(Log.INFO, module.TAG, "✔ 每日任务：分享成功回调已触发 (步骤 " + (currentStep + 1) + "/" + STEP_COUNT + ")");

        Context context = ctx instanceof Context ? (Context) ctx : null;
        mainHandler.post(() -> onStepCompleted(context));
    }

    // ---------- 2. 入口：MainActivity.onResume ----------
    private void hookMainResume(ClassLoader cl) {
        try {
            Class<?> mainActivity = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onResume = mainActivity.getDeclaredMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        maybeStartDailyTask((Activity) self);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "每日任务启动检查异常: " + t);
                }
                return result;
            });
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 每日任务入口 Hook 失败", t);
        }
    }

    private void maybeStartDailyTask(Activity activity) {
        if (autoActive) {
            return;
        }
        if (!module.isEnabled(App.KEY_DAILY_TASK_ENABLED, false)) {
            return;
        }
        if (isTodayDone()) {
            return;
        }
        if (!hasAnyLink()) {
            module.logd(Log.WARN, module.TAG, "每日任务：未配置分享帖子链接（图片帖/普通帖/频道）");
            return;
        }
        autoActive = true;
        currentStep = STEP_PICTURE;
        module.logd(Log.INFO, module.TAG, "每日任务启动（3 种分享类型：图片帖→普通帖→频道）");
        openStep(activity, STEP_PICTURE);
    }

    // ---------- 3. 分享页自动点按钮 ----------
    private void hookSharePages(ClassLoader cl) {
        // 图片帖 / 普通帖：点分享按钮（iv_appbar_action_button）
        String[] sharePages = {
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.PicturePostPageActivityV2",
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
        };
        for (String page : sharePages) {
            try {
                Class<?> cls = Class.forName(page, false, cl);
                Method onResume = cls.getDeclaredMethod("onResume");
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    if (!autoActive) {
                        return result;
                    }
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof Activity) {
                            scheduleClickShare((Activity) self, "iv_appbar_action_button");
                        }
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "分享页自动点击调度异常: " + t);
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 分享页自动点击 Hook: "
                        + page.substring(page.lastIndexOf('.') + 1));
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "分享页 Hook 失败: " + page, t);
            }
        }
        // 频道详情：点「更多」按钮（iv_appbar_action_button_more）
        try {
            Class<?> cls = Class.forName(
                    "com.max.xiaoheihe.module.bbs.ChannelsDetailActivity", false, cl);
            Method onResume = cls.getDeclaredMethod("onResume");
            module.hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                if (!autoActive) {
                    return result;
                }
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        scheduleClickShare((Activity) self, "iv_appbar_action_button_more");
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "频道页自动点击调度异常: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 频道页自动点击 Hook: ChannelsDetailActivity");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "频道页 Hook 失败", t);
        }
    }

    private void scheduleClickShare(final Activity activity, final String viewName) {
        final int scheduleStep = currentStep;
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!autoActive || scheduleStep != currentStep || activity.isFinishing()) {
                        return;
                    }
                    int btnId = activity.getResources().getIdentifier(
                            viewName, "id", MainModule.TARGET_PKG);
                    if (btnId == 0) {
                        module.logd(Log.WARN, module.TAG, "未找到 " + viewName + "，跳过该步");
                        advance(activity);
                        return;
                    }
                    View btn = activity.findViewById(btnId);
                    if (btn == null) {
                        module.logd(Log.WARN, module.TAG, viewName + " 视图为 null，跳过该步");
                        advance(activity);
                        return;
                    }
                    module.logd(Log.INFO, module.TAG, "每日任务：点击 " + viewName
                            + " (步骤 " + (scheduleStep + 1) + "/" + STEP_COUNT + ")");
                    btn.performClick();
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "点击按钮异常: " + t);
                }
            }
        }, CLICK_DELAY_MS);

        // 看门狗：该步超时未完成则跳过
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoActive && scheduleStep == currentStep) {
                    module.logd(Log.WARN, module.TAG, "每日任务：步骤 " + (scheduleStep + 1) + " 超时，跳过");
                    advance(activity);
                }
            }
        }, STEP_TIMEOUT_MS);
    }

    // ---------- 4. 推进 ----------
    private void onStepCompleted(Context context) {
        if (!autoActive) {
            return;
        }
        int done = currentStep;
        module.logd(Log.INFO, module.TAG, "每日任务：步骤 " + (done + 1) + "/" + STEP_COUNT + " 完成 ("
                + stepName(done) + ")");
        advance(context);
    }

    /** 推进到下一步；全部完成则记录今日状态 */
    private void advance(Context context) {
        if (!autoActive) {
            return;
        }
        int next = currentStep + 1;
        if (next < STEP_COUNT) {
            currentStep = next;
            if (context != null) {
                openStep(context, next);
            }
        } else {
            finishDailyTask(context);
        }
    }

    private void openStep(Context context, int step) {
        String link = getLinkForStep(step);
        if (link == null || link.isEmpty()) {
            module.logd(Log.INFO, module.TAG, "每日任务：步骤 " + (step + 1) + "（"
                    + stepName(step) + "）未配置，跳过");
            advance(context);
            return;
        }
        try {
            Class<?> router = Class.forName("com.max.xiaoheihe.RouterActivity");
            Intent intent = new Intent(context, router)
                    .setData(Uri.parse(link.trim()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            module.logd(Log.INFO, module.TAG, "每日任务：打开 " + stepName(step) + ": " + link.trim());
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "RouterActivity 打开失败，尝试 link_id 直开: " + t);
            String linkId = extractLinkId(link);
            if (linkId == null) {
                module.logd(Log.WARN, module.TAG, "无法解析 link_id，跳过该步");
                advance(context);
                return;
            }
            try {
                Class<?> normalPage = Class.forName(
                        "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity");
                Intent intent = new Intent(context, normalPage)
                        .putExtra("link_id", linkId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t2) {
                module.logd(Log.ERROR, module.TAG, "帖子打开失败", t2);
                advance(context);
            }
        }
    }

    private void finishDailyTask(Context context) {
        autoActive = false;
        currentStep = -1;
        HeyboxPrefs.setString(App.KEY_DAILY_TASK_DONE_DATE, today());
        module.logd(Log.INFO, module.TAG, "每日任务：3 种分享类型全部完成，已记录今日状态");
        if (context != null) {
            try {
                Toast.makeText(context.getApplicationContext(),
                        "每日分享任务已完成", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------- 工具 ----------
    private String getLinkForStep(int step) {
        String key;
        switch (step) {
            case STEP_PICTURE:
                key = App.KEY_DAILY_TASK_PICTURE;
                break;
            case STEP_NORMAL:
                key = App.KEY_DAILY_TASK_NORMAL;
                break;
            case STEP_CHANNEL:
                key = App.KEY_DAILY_TASK_CHANNEL;
                break;
            default:
                return null;
        }
        // 优先小黑盒进程本地配置（内嵌面板直写），其次模块设置页经 RemotePreferences 写入的值
        String value = module.getString(key, "");
        return value == null ? null : value.trim();
    }

    private boolean hasAnyLink() {
        return !getLinkForStep(STEP_PICTURE).isEmpty()
                || !getLinkForStep(STEP_NORMAL).isEmpty()
                || !getLinkForStep(STEP_CHANNEL).isEmpty();
    }

    private static String stepName(int step) {
        switch (step) {
            case STEP_PICTURE:
                return "图片帖";
            case STEP_NORMAL:
                return "普通帖";
            case STEP_CHANNEL:
                return "频道关注";
            default:
                return "未知";
        }
    }

    private boolean isTodayDone() {
        return today().equals(HeyboxPrefs.getString(App.KEY_DAILY_TASK_DONE_DATE, ""));
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    private static String extractLinkId(String link) {
        try {
            Uri uri = Uri.parse(link);
            String id = uri.getQueryParameter("link_id");
            if (id != null && !id.isEmpty()) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        try {
            int idx = link.indexOf("link_id=");
            if (idx >= 0) {
                String v = link.substring(idx + 8);
                int end = v.indexOf('&');
                if (end > 0) {
                    v = v.substring(0, end);
                }
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
