package com.better.heybox.hooks;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
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
 * <p>小黑盒每日任务包含 3 种分享任务，依次完成：</p>
 * <ol>
 *   <li><b>分享任意帖子</b>：打开用户配置的帖子（{@code PicturePostPageActivityV2}/{@code NormalPostPageActivity}）→ 自动点分享按钮；</li>
 *   <li><b>分享游戏详情</b>：打开游戏详情页（{@code ChannelsDetailActivity}）→ 自动点分享按钮；</li>
 *   <li><b>分享游戏评价</b>：打开游戏评价页（{@code GameDetailFragment}）→ 自动点分享按钮。</li>
 * </ol>
 *
 * <p>3 个任务的链接由用户在模块设置中分别配置（帖子/游戏详情/游戏评价）。</p>
 *
 * <p>完成机制：Hook {@code com.max.hbcommon.component.i.show()}（分享面板）自动点「QQ」渠道，
 * 并 Hook {@code Tencent.shareToQQ(Activity, Bundle, IUiListener)} 直接触发 {@code IUiListener.onComplete({"ret":0})}
 * 跳过真实 QQ SDK；同时对走 {@code ShareUtils.P/y(Context, HBShareData)} 的分享入口（如游戏详情页）触发
 * {@code HBShareData.shareListener}（UMShareListener）的 {@code onResult}。只触发任务自身回调，
 * 用户手动 QQ 分享不受影响。</p>
 */
public final class DailyTaskHook {

    /** 3 种分享类型步骤 */
    private static final int STEP_PICTURE = 0;
    private static final int STEP_NORMAL = 1;
    private static final int STEP_CHANNEL = 2;
    private static final int STEP_COUNT = 3;

    private final MainModule module;

    /** 小黑盒进程 classloader（openStep 打开页面时使用，避免用模块 classloader 找不到小黑盒类） */
    private volatile ClassLoader targetCl;

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
        this.targetCl = cl;
        hookShareUtils(cl);
        hookSharePanel(cl);
        hookTencentShareToQQ(cl);
        hookWeChatShare(cl);
        hookSinaShare(cl);
        hookMainResume(cl);
        hookSharePages(cl);
        module.logd(Log.INFO, module.TAG, "✔ 每日任务 Hook 安装完成");
    }
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
                    return null;
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
    private void hookSharePanel(ClassLoader cl) {
        try {
            Class<?> panel = Class.forName("com.max.hbcommon.component.i", false, cl);
            Method show = panel.getMethod("show");
            module.hook(show).intercept(chain -> {
                Object result = chain.proceed();
                if (!autoActive) {
                    return result;
                }
                try {
                    Object self = chain.getThisObject();
                    if (self instanceof Dialog) {
                        autoClickChannel((Dialog) self);
                    }
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "分享面板自动点渠道异常: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 分享面板 Hook 已安装: component.i.show()");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "✘ 分享面板 Hook 失败", t);
        }
    }

    /** 按配置的分享渠道（QQ/WECHAT）在面板中自动点击对应渠道按钮 */
    private void autoClickChannel(final Dialog dialog) {
        final String channel = currentChannel();
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!autoActive || dialog == null || !dialog.isShowing()) {
                        return;
                    }
                    ViewGroup root = dialog.getWindow() != null
                            && dialog.getWindow().getDecorView() instanceof ViewGroup
                            ? (ViewGroup) dialog.getWindow().getDecorView() : null;
                    // 按配置渠道在面板查找对应按钮文本
                    View target = null;
                    if ("WECHAT".equals(channel)) {
                        target = findChannelView(root, "微信");
                        if (target == null) {
                            target = findChannelView(root, "朋友圈");
                        }
                    } else if ("WEIBO".equals(channel)) {
                        target = findChannelView(root, "微博");
                    } else {
                        target = findChannelView(root, "QQ");
                    }
                    if (target == null) {
                        module.logd(Log.WARN, module.TAG, "分享面板未找到渠道按钮(" + channel + ")，跳过该步");
                        return;
                    }
                    module.logd(Log.INFO, module.TAG, "每日任务：自动点击分享面板 " + channel + " 渠道 (步骤 "
                            + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    target.performClick();
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "自动点渠道异常: " + t);
                }
            }
        }, 800L);
    }

    /** 当前配置的分享渠道（默认 QQ） */
    private String currentChannel() {
        String v = module.getString(App.KEY_SHARE_CHANNEL, "");
        return v == null || v.isEmpty() ? "QQ" : v;
    }

    /** 递归查找分享面板里文本为目标渠道名的可点击 View（SoulFrog 3.8.0 b() 同款） */
    private static View findChannelView(ViewGroup root, String targetText) {
        if (root == null || targetText == null) {
            return null;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof ViewGroup) {
                View found = findChannelView((ViewGroup) child, targetText);
                if (found != null) {
                    return found;
                }
            }
            if (child instanceof android.widget.TextView) {
                CharSequence text = ((android.widget.TextView) child).getText();
                if (text != null && targetText.equals(text.toString().trim())) {
                    View v = child;
                    while (v != null) {
                        if (v.isClickable()) {
                            return v;
                        }
                        if (!(v.getParent() instanceof View)) {
                            break;
                        }
                        v = (View) v.getParent();
                    }
                    return child;
                }
            }
        }
        return null;
    }
    private void hookTencentShareToQQ(ClassLoader cl) {
        try {
            Class<?> tencent = Class.forName("com.tencent.tauth.Tencent", false, cl);
            Class<?> iUiListener = Class.forName("com.tencent.tauth.IUiListener", false, cl);
            Method shareToQQ = null;
            for (Method m : tencent.getDeclaredMethods()) {
                if ("shareToQQ".equals(m.getName())) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 3 && pts[0] == Activity.class
                            && pts[1] == android.os.Bundle.class && pts[2] == iUiListener) {
                        shareToQQ = m;
                        break;
                    }
                }
            }
            if (shareToQQ == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 Tencent.shareToQQ(Activity,Bundle,IUiListener)");
                return;
            }
            final Method onComplete = iUiListener.getMethod("onComplete", Object.class);
            module.hook(shareToQQ).intercept(chain -> {
                if (!autoActive || !"QQ".equals(currentChannel())) {
                    return chain.proceed();
                }
                try {
                    Object listener = chain.getArg(2);
                    if (listener != null) {
                        org.json.JSONObject ret = new org.json.JSONObject();
                        ret.put("ret", 0);
                        onComplete.invoke(listener, ret);
                        module.logd(Log.INFO, module.TAG, "✔ 每日任务：Tencent.shareToQQ 伪造成功回调 (步骤 "
                                + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    }
                    Object ctx = chain.getArg(0);
                    Context context = ctx instanceof Context ? (Context) ctx : null;
                    mainHandler.post(() -> onStepCompleted(context));
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "Tencent.shareToQQ 伪造回调异常: " + t);
                }
                return null; // 跳过真实 QQ SDK
            });
            module.logd(Log.INFO, module.TAG, "✔ 分享完成 Hook 已安装: Tencent.shareToQQ");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ Tencent.shareToQQ Hook 失败", t);
        }
    }

    // ---------- 1d. 微信分享完成核心：UMWXHandler.share(ShareContent, UMShareListener) ----------
    /**
     * 微信好友/朋友圈分享经友盟 {@code UMWXHandler.share(ShareContent, UMShareListener)}。
     * 自动化进行中直接触发 {@code UMShareListener.onResult(SHARE_MEDIA.WEIXIN)} 并返回 true
     * 跳过真实微信 SDK（不调起微信），让小黑盒判定分享成功。393/394 双版本同签名。
     */
    private void hookWeChatShare(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName("com.umeng.socialize.handler.UMWXHandler", false, cl);
            Class<?> listener = Class.forName("com.umeng.socialize.UMShareListener", false, cl);
            Class<?> shareContent = Class.forName("com.umeng.socialize.ShareContent", false, cl);
            Method share = null;
            for (Method m : handler.getDeclaredMethods()) {
                if ("share".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == shareContent
                        && m.getParameterTypes()[1] == listener) {
                    share = m;
                    break;
                }
            }
            if (share == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 UMWXHandler.share(ShareContent,UMShareListener)");
                return;
            }
            final Method onResult = listener.getMethod("onResult",
                    Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl));
            final Object weixin = Enum.valueOf(
                    (Class<Enum>) Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl),
                    "WEIXIN");
            module.hook(share).intercept(chain -> {
                if (!autoActive || !"WECHAT".equals(currentChannel())) {
                    return chain.proceed();
                }
                try {
                    Object l = chain.getArg(1);
                    if (l != null) {
                        onResult.invoke(l, weixin);
                        module.logd(Log.INFO, module.TAG, "✔ 每日任务：微信分享成功回调已触发 (步骤 "
                                + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    }
                    mainHandler.post(() -> onStepCompleted(null));
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "微信分享伪造回调异常: " + t);
                }
                return Boolean.TRUE; // 跳过真实微信 SDK
            });
            module.logd(Log.INFO, module.TAG, "✔ 分享完成 Hook 已安装: UMWXHandler.share");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 微信分享 Hook 失败", t);
        }
    }

    // ---------- 1e. 微博分享完成核心：SinaSsoHandler.share(ShareContent, UMShareListener) ----------
    /**
     * 微博分享经友盟 {@code SinaSsoHandler.share(ShareContent, UMShareListener)} → 新浪
     * {@code WBAPI.shareMessage} 拉起微博，成功回调经 {@code WbShareCallback.onComplete} →
     * {@code UMShareListener.onResult(SHARE_MEDIA.SINA)}。自动化进行中直接触发 onResult
     * 并返回 true 跳过真实微博 SDK。393/394 双版本同签名。
     */
    private void hookSinaShare(ClassLoader cl) {
        try {
            Class<?> handler = Class.forName("com.umeng.socialize.handler.SinaSsoHandler", false, cl);
            Class<?> listener = Class.forName("com.umeng.socialize.UMShareListener", false, cl);
            Class<?> shareContent = Class.forName("com.umeng.socialize.ShareContent", false, cl);
            Class<?> shareMedia = Class.forName("com.umeng.socialize.bean.SHARE_MEDIA", false, cl);
            Method share = null;
            for (Method m : handler.getDeclaredMethods()) {
                if ("share".equals(m.getName()) && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == shareContent
                        && m.getParameterTypes()[1] == listener) {
                    share = m;
                    break;
                }
            }
            if (share == null) {
                module.logd(Log.WARN, module.TAG, "✘ 未找到 SinaSsoHandler.share(ShareContent,UMShareListener)");
                return;
            }
            final Method onResult = listener.getMethod("onResult", shareMedia);
            final Object sina = Enum.valueOf((Class<Enum>) shareMedia, "SINA");
            module.hook(share).intercept(chain -> {
                if (!autoActive || !"WEIBO".equals(currentChannel())) {
                    return chain.proceed();
                }
                try {
                    Object l = chain.getArg(1);
                    if (l != null) {
                        onResult.invoke(l, sina);
                        module.logd(Log.INFO, module.TAG, "✔ 每日任务：微博分享成功回调已触发 (步骤 "
                                + (currentStep + 1) + "/" + STEP_COUNT + ")");
                    }
                    mainHandler.post(() -> onStepCompleted(null));
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "微博分享伪造回调异常: " + t);
                }
                return Boolean.TRUE; // 跳过真实微博 SDK
            });
            module.logd(Log.INFO, module.TAG, "✔ 分享完成 Hook 已安装: SinaSsoHandler.share");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 微博分享 Hook 失败", t);
        }
    }
    private void hookMainResume(ClassLoader cl) {
        try {
            Class<?> mainActivity = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onResume = mainActivity.getMethod("onResume");
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
        if (module.isEnabled(App.KEY_DAILY_TASK_RESET, false)) {
            clearDoneDate();
            HeyboxPrefs.setBoolean(App.KEY_DAILY_TASK_RESET, false);
            try {
                SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
                if (remote != null) {
                    remote.edit().remove(App.KEY_DAILY_TASK_RESET).apply();
                }
            } catch (Throwable ignored) {
            }
            module.logd(Log.INFO, module.TAG, "检测到清除今日打卡标志，已重置完成状态");
        }
        if (isTodayDone()) {
            return;
        }
        if (!hasAnyLink()) {
            module.logd(Log.WARN, module.TAG, "每日任务：未配置分享链接（帖子/游戏详情/游戏评价）");
            return;
        }
        autoActive = true;
        currentStep = STEP_PICTURE;
        module.logd(Log.INFO, module.TAG, "每日任务启动（3 种分享类型：图片帖→普通帖→频道）");
        openStep(activity, STEP_PICTURE);
    }
    private void hookSharePages(ClassLoader cl) {

        String[] sharePages = {
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.v2.PicturePostPageActivityV2",
                "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
        };
        for (String page : sharePages) {
            try {
                Class<?> cls = Class.forName(page, false, cl);
                Method onResume = cls.getMethod("onResume");
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
        try {
            Class<?> cls = Class.forName(
                    "com.max.xiaoheihe.module.bbs.ChannelsDetailActivity", false, cl);
            Method onResume = cls.getMethod("onResume");
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
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (autoActive && scheduleStep == currentStep) {
                    module.logd(Log.WARN, module.TAG, "每日任务：步骤 " + (scheduleStep + 1) + " 超时，跳过");
                    advance(activity);
                }
            }
        }, STEP_TIMEOUT_MS);
    }    private void onStepCompleted(Context context) {
        if (!autoActive) {
            return;
        }
        int done = currentStep;
        module.logd(Log.INFO, module.TAG, "每日任务：步骤 " + (done + 1) + "/" + STEP_COUNT + " 完成 ("
                + stepName(done) + ")");
        advance(context);
    }
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
        ClassLoader cl = targetCl != null ? targetCl
                : (context != null ? context.getClassLoader() : null);
        try {
            Class<?> router = Class.forName("com.max.xiaoheihe.RouterActivity", false, cl);
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
                        "com.max.xiaoheihe.module.bbs.post.ui.activitys.NormalPostPageActivity",
                        false, cl);
                Intent intent = new Intent(context, normalPage)
                        .putExtra("link_id", linkId)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Throwable t2) {
                module.logd(Log.ERROR, module.TAG, "帖子打开失败", t2);
                abortDailyTask();
            }
        }
    }
    private void abortDailyTask() {
        autoActive = false;
        currentStep = -1;
        module.logd(Log.WARN, module.TAG,
                "每日任务：打开帖子失败，已复位（今日未标记完成，下次进入主页将重试）");
    }
    public void clearTodayAndRetry(Activity activity) {
        autoActive = false;
        currentStep = -1;
        clearDoneDate();
        module.logd(Log.INFO, module.TAG, "已清除今日打卡状态，重新尝试每日任务");
        if (activity != null) {
            maybeStartDailyTask(activity);
        }
    }
    private void clearDoneDate() {
        HeyboxPrefs.setString(App.KEY_DAILY_TASK_DONE_DATE, "");
        try {
            SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null && remote.contains(App.KEY_DAILY_TASK_DONE_DATE)) {
                remote.edit().remove(App.KEY_DAILY_TASK_DONE_DATE).apply();
            }
        } catch (Throwable ignored) {
        }
    }

    private void finishDailyTask(Context context) {
        autoActive = false;
        currentStep = -1;
        HeyboxPrefs.setString(App.KEY_DAILY_TASK_DONE_DATE, today());
        try {
            SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null) {
                remote.edit().putString(App.KEY_DAILY_TASK_DONE_DATE, today()).apply();
            }
        } catch (Throwable ignored) {
        }
        module.logd(Log.INFO, module.TAG, "每日任务：3 种分享类型全部完成，已记录今日状态");
        if (context != null) {
            try {
                Toast.makeText(context.getApplicationContext(),
                        "每日分享任务已完成", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
        }
    }
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
                return "分享任意帖子";
            case STEP_NORMAL:
                return "分享游戏详情";
            case STEP_CHANNEL:
                return "分享游戏评价";
            default:
                return "未知";
        }
    }

    private boolean isTodayDone() {
        if (today().equals(HeyboxPrefs.getString(App.KEY_DAILY_TASK_DONE_DATE, ""))) {
            return true;
        }
        try {
            SharedPreferences remote = module.getRemotePreferences(App.PREFS_GROUP);
            if (remote != null && today().equals(
                    remote.getString(App.KEY_DAILY_TASK_DONE_DATE, ""))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
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
