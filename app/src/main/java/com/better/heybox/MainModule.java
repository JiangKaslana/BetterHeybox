package com.better.heybox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * BetterHeybox 模块入口（libxposed Modern API 102）。
 *
 * 功能：
 * 1. 广告过滤（开屏 / 信息流 / 气泡 / 角标），各功能可在设置界面独立开关
 * 2. 小黑盒设置页（GeneralSettingsActivity）注入 "BetterHeybox 设置" 入口
 *
 * 开关状态：模块 App 通过 libxposed service 写入 RemotePreferences，
 * 本类在小黑盒进程用 getRemotePreferences() 读取（跨进程共享）。
 */
public class MainModule extends XposedModule {

    private static final String TAG = "BetterHeybox";
    private static final String TARGET_PKG = "com.max.xiaoheihe";
    private static final String TARGET_HEYBOX_VERSION = "1.3.393";
    private static final String ENTRY_TAG = "betterheybox_entry";
    private static final String EMBEDDED_SETTINGS_TAG = "betterheybox_embedded_settings";
    private static final AtomicBoolean VERSION_NOTICE_SHOWN = new AtomicBoolean(false);
    private static volatile Boolean HOST_DARK_MODE_OVERRIDE;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName());
        log(Log.INFO, TAG, "framework: " + getFrameworkName()
                + " (" + getFrameworkVersion() + ") API " + getApiVersion());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // 允许热重载（否则设置界面「立即重启」的热重载会被框架拒绝）
        log(Log.INFO, TAG, "允许热重载");
        return true;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        log(Log.INFO, TAG, "onPackageReady: " + packageName);

        if (TARGET_PKG.equals(packageName)) {
            log(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook");
            installHooks(param);
        }
    }

    private void installHooks(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();

        hookVersionNotice(cl);
        hookUpdateBlocking(cl);
        hookThemeSwitch();
        hookOpenScreenAd(cl);
        hookFeedAds(cl);
        hookBubbleAndCornerAds(cl);
        hookSettingsEntry(cl);
        hookBottomTabs(cl);
        hookPromotePosts(cl);
        hookTextSelectHandler(cl);
        hookPostTextSelect(cl);
        hookScrollIntercept(cl);

        log(Log.INFO, TAG, "Hook 安装流程结束");
    }

    /** 读取功能开关（RemotePreferences，与设置界面跨进程共享） */
    private boolean isEnabled(String key, boolean def) {
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null) {
                return prefs.getBoolean(key, def);
            }
        } catch (Throwable t) {
            // 读取失败按默认值处理
        }
        return def;
    }

    // ==================== 0. 版本前置检查 / 更新屏蔽 ====================

    /**
     * Heybox 的页面基类会在主界面及其它页面恢复时回调，适合作为版本提示入口。
     * 提示使用 Heybox 自带的底部提示栏，避免引入额外 UI 依赖。
     */
    private void hookVersionNotice(ClassLoader cl) {
        try {
            Class<?> baseActivity = Class.forName(
                    "com.max.hbcommon.base.BaseActivity", false, cl);
            Method onResume = baseActivity.getDeclaredMethod("onResume");
            hook(onResume).intercept(chain -> {
                Object result = chain.proceed();
                Object self = chain.getThisObject();
                if (self instanceof Activity) {
                    Activity activity = (Activity) self;
                    View decor = activity.getWindow().getDecorView();
                    decor.postDelayed(() -> {
                        syncHostDarkModeFromContext(activity);
                        showVersionNotice(activity, cl);
                        refreshSettingsThemeIfOpen(activity);
                    }, 600L);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ Heybox 版本检测 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ Heybox 版本检测 Hook 失败", t);
        }
    }

    private void showVersionNotice(Activity activity, ClassLoader cl) {
        if (activity.isFinishing() || VERSION_NOTICE_SHOWN.get()) {
            return;
        }
        String version = "unknown";
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(TARGET_PKG, 0);
            if (info.versionName != null) {
                version = info.versionName;
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "读取 Heybox 版本失败", t);
            return;
        }
        if (TARGET_HEYBOX_VERSION.equals(version)
                || !VERSION_NOTICE_SHOWN.compareAndSet(false, true)) {
            return;
        }

        String message = "BetterHeybox 目标版本为 Heybox " + TARGET_HEYBOX_VERSION
                + "，当前检测到 " + version;
        try {
            Class<?> toastUtil = Class.forName("com.max.hbutils.utils.f", false, cl);
            Method showBottomHint = toastUtil.getDeclaredMethod("d", String.class);
            showBottomHint.invoke(null, message);
        } catch (Throwable t) {
            // 目标 Toast 工具不可用时仍保留版本提示。
            Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        }
        log(Log.WARN, TAG, message);
    }

    /** 屏蔽 AppUpdateManager 的统一更新入口，开关关闭时完全保留原行为。 */
    private void hookUpdateBlocking(ClassLoader cl) {
        try {
            Class<?> manager = Class.forName(
                    "com.max.xiaoheihe.utils.AppUpdateManager", false, cl);
            Method updateEntry = null;
            for (Method method : manager.getDeclaredMethods()) {
                if ("P".equals(method.getName())
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == Boolean.class) {
                    updateEntry = method;
                    break;
                }
            }
            if (updateEntry == null) {
                log(Log.WARN, TAG, "✘ 未找到 AppUpdateManager.P(Boolean)");
                return;
            }
            hook(updateEntry).intercept(chain -> {
                if (isEnabled(App.KEY_BLOCK_UPDATE, false)) {
                    log(Log.INFO, TAG, "已屏蔽 Heybox 更新入口 AppUpdateManager.P()");
                    return chain.getThisObject();
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "✔ Heybox 更新屏蔽 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ Heybox 更新屏蔽 Hook 失败", t);
        }
    }

    // ==================== 1. 开屏广告 ====================

    private void hookOpenScreenAd(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.ads.e", false, cl);
            Method g = clazz.getDeclaredMethod("g", boolean.class);
            hook(g).intercept(chain -> {
                if (isEnabled(App.KEY_OPEN_SCREEN, true)) {
                    log(Log.INFO, TAG, "拦截开屏广告 e.g()");
                    return null; // 调用方已判空，null = 无广告
                }
                return chain.proceed();
            });
            log(Log.INFO, TAG, "✔ 开屏广告 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 开屏广告 Hook 失败", t);
        }
    }

    // ==================== 2. 信息流广告 ====================

    private void hookFeedAds(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.network.gson.FeedsContentDeserializer", false, cl);
            // 必须用小黑盒的 classloader 加载 gson（单参 Class.forName 会用模块自己的 classloader）
            Class<?> jsonElement = Class.forName("com.google.gson.JsonElement", false, cl);
            Class<?> type = Class.forName("java.lang.reflect.Type", false, cl);
            Class<?> ctx = Class.forName("com.google.gson.JsonDeserializationContext", false, cl);

            try {
                Method a = clazz.getDeclaredMethod("a", jsonElement, type, ctx);
                hook(a).intercept(chain -> filterFeedAd(chain));
                log(Log.INFO, TAG, "✔ 信息流广告 Hook 已安装 (a)");
            } catch (NoSuchMethodException ignored) {
            }
            try {
                Method deserialize = clazz.getDeclaredMethod("deserialize", jsonElement, type, ctx);
                hook(deserialize).intercept(chain -> filterFeedAd(chain));
                log(Log.INFO, TAG, "✔ 信息流广告 Hook 已安装 (deserialize)");
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 信息流广告 Hook 失败", t);
        }
    }

    /** 信息流广告过滤：content_type == "23" 时返回空 FeedsContentBaseObj（避免 null 导致列表 NPE 崩溃） */
    private Object filterFeedAd(Object chainObj) throws Throwable {
        XposedInterface.Chain chain = (XposedInterface.Chain) chainObj;
        if (!isEnabled(App.KEY_FEED_AD, true)) {
            return chain.proceed();
        }
        try {
            Object elem = chain.getArg(0);
            if (elem != null) {
                Object obj = elem.getClass().getMethod("getAsJsonObject").invoke(elem);
                if (obj != null) {
                    Object ct = obj.getClass().getMethod("get", String.class).invoke(obj, "content_type");
                    if (ct != null) {
                        String ctStr = (String) ct.getClass().getMethod("getAsString").invoke(ct);
                        if ("23".equals(ctStr)) {
                            log(Log.INFO, TAG, "过滤信息流广告条目 (content_type=23)");
                            return createEmptyFeedObj(chain.getThisObject());
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "信息流广告判断异常，放行: " + t);
        }
        return chain.proceed();
    }

    /** 创建空的 FeedsContentBaseObj（content_type=0 + 无分割线），替换广告条目避免 null 崩溃 */
    private Object createEmptyFeedObj(Object thisObj) {
        try {
            ClassLoader cl = thisObj != null ? thisObj.getClass().getClassLoader()
                    : getClass().getClassLoader();
            Class<?> base = Class.forName("com.max.xiaoheihe.bean.news.FeedsContentBaseObj", false, cl);
            Object empty = base.getDeclaredConstructor().newInstance();
            base.getMethod("setContent_type", String.class).invoke(empty, "0");
            try {
                base.getMethod("setShowDivider", boolean.class).invoke(empty, false);
            } catch (Throwable ignored) {
            }
            return empty;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "创建空 FeedsContentBaseObj 失败: " + t);
            return null;
        }
    }

    // ==================== 3. 气泡 / 角标广告 ====================

    private void hookBubbleAndCornerAds(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.ads.h", false, cl);
            Class<?> callback = Class.forName("com.max.xiaoheihe.utils.x0$g", false, cl);

            // 气泡展示检查入口：l() 的参数是内部类 h$g（注意：不是 x0$g）
            try {
                Class<?> innerG = Class.forName("com.max.xiaoheihe.module.ads.h$g", false, cl);
                Method l = clazz.getDeclaredMethod("l", innerG);
                hook(l).intercept(chain -> {
                    if (isEnabled(App.KEY_BUBBLE_AD, true)) {
                        log(Log.INFO, TAG, "拦截气泡广告 h.l()");
                        return null;
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "✔ 气泡广告 Hook 已安装");
            } catch (NoSuchMethodException ignored) {
            }

            // 广告拉取入口：阻断后 f86785b 恒为 null，角标数据源消失
            try {
                Method h = clazz.getDeclaredMethod("h", callback);
                hook(h).intercept(chain -> {
                    if (isEnabled(App.KEY_CORNER_AD, true)) {
                        log(Log.INFO, TAG, "拦截广告拉取 h.h()");
                        return null;
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "✔ 角标广告拉取 Hook 已安装");
            } catch (NoSuchMethodException ignored) {
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 气泡/角标广告 Hook 失败", t);
        }
    }

    // ==================== 4. 设置页入口 ====================

    /**
     * 监听小黑盒通用设置中的深色模式入口。只在原点击完成后读取宿主实际背景亮度，
     * 不替换小黑盒自己的点击监听器，避免破坏其主题切换逻辑。
     */
    private void hookThemeSwitch() {
        try {
            Method performClick = View.class.getDeclaredMethod("performClick");
            hook(performClick).intercept(chain -> {
                Object result = chain.proceed();
                Object target = chain.getThisObject();
                if (target instanceof View && isDarkModeEntry((View) target)) {
                    View view = (View) target;
                    view.postDelayed(() -> updateHostDarkModeFromEntry(view), 250L);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 小黑盒深色模式开关 Hook 已安装");
        } catch (Throwable t) {
            log(Log.WARN, TAG, "深色模式开关 Hook 安装失败", t);
        }
    }

    private boolean isDarkModeEntry(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID) {
                return false;
            }
            return "vg_dark_mode_v2".equals(view.getResources().getResourceEntryName(id))
                    && TARGET_PKG.equals(view.getResources().getResourcePackageName(id));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void updateHostDarkModeFromEntry(View entry) {
        try {
            Boolean value = readDarkModeEntryValue(entry);
            if (value != null) {
                HOST_DARK_MODE_OVERRIDE = value;
            } else {
                syncHostDarkModeFromContext(entry.getContext());
            }
            Activity activity = findActivity(entry.getContext());
            if (activity != null) {
                refreshSettingsThemeIfOpen(activity);
            }
            log(Log.INFO, TAG, "小黑盒 vg_dark_mode_v2 状态已同步: dark="
                    + HOST_DARK_MODE_OVERRIDE);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "同步小黑盒主题状态失败", t);
        }
    }

    /** 在宿主 Activity 恢复后重新读取实际主题，覆盖旧 Activity 的点击状态。 */
    private void syncHostDarkModeFromContext(Context context) {
        int background = ThemeUtils.resolveColor(context, android.R.attr.colorBackground, 0);
        HOST_DARK_MODE_OVERRIDE = background != 0
                ? isDarkColor(background) : ThemeUtils.isDarkMode(context);
    }

    /** 读取 vg_dark_mode_v2 右侧状态值：打开=深色，关闭=浅色，跟随系统=读取系统 uiMode。 */
    private Boolean readDarkModeEntryValue(View view) {
        if (view instanceof TextView) {
            String text = String.valueOf(((TextView) view).getText()).trim();
            if (isDarkModeEnabledText(text)) {
                return true;
            }
            if (isDarkModeDisabledText(text)) {
                return false;
            }
            if (isDarkModeFollowSystemText(text)) {
                return ThemeUtils.isDarkMode(view.getContext());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Boolean value = readDarkModeEntryValue(group.getChildAt(i));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private boolean isDarkModeEnabledText(String text) {
        return "打开".equals(text) || "开启".equals(text) || "已打开".equals(text)
                || "已开启".equals(text);
    }

    private boolean isDarkModeDisabledText(String text) {
        return "关闭".equals(text) || "关".equals(text) || "已关闭".equals(text)
                || "未开启".equals(text);
    }

    private boolean isDarkModeFollowSystemText(String text) {
        return "跟随系统".equals(text) || "跟随系统设置".equals(text)
                || "系统默认".equals(text);
    }

    private Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) {
                break;
            }
            current = base;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private void hookSettingsEntry(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl);
            // hook G1（onCreate 模板，每个 Activity 实例恰好一次）。
            // 不能在 G1 同步插入：布局尚未稳定（经验证会破坏设置项）；
            // 用 postDelayed 等布局稳定后再插入。Activity 重建（反复进出设置页）会重新触发 G1 并重新插入。
            Method g1 = clazz.getDeclaredMethod("G1");
            hook(g1).intercept(chain -> {
                Object result = chain.proceed(); // 先执行原 G1（setContentView 完成）
                try {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
                        final Activity activity = (Activity) thisObj;
                        activity.getWindow().getDecorView().postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    syncHostDarkModeBeforeInjection(activity);
                                    insertSettingsEntry(activity);
                                } catch (Throwable t) {
                                    log(Log.ERROR, TAG, "插入设置入口异常", t);
                                }
                            }
                        }, 500);
                    }
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "设置入口插入调度异常", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 设置页入口 Hook 已安装 (G1+delayed)");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 设置页入口 Hook 失败", t);
        }
    }

    /**
     * 在创建入口前读取当前设置页的深色模式值，避免先用旧主题注入、随后再变色造成闪烁。
     * vg_dark_mode_v2 的文本是宿主最终显示状态，优先级高于旧 Activity 缓存和主题回调。
     */
    private void syncHostDarkModeBeforeInjection(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "vg_dark_mode_v2", "id", TARGET_PKG);
            View modeEntry = id != 0 ? activity.findViewById(id) : null;
            Boolean value = modeEntry != null ? readDarkModeEntryValue(modeEntry) : null;
            if (value != null) {
                HOST_DARK_MODE_OVERRIDE = value;
                return;
            }
        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "注入前读取深色模式状态失败，回退 Theme", t);
        }
        syncHostDarkModeFromContext(activity);
    }

    private void insertSettingsEntry(Object activityObj) {
        if (!(activityObj instanceof Activity)) {
            return;
        }
        Activity activity = (Activity) activityObj;
        try {
            // 定位设置页根布局（id=root，从小黑盒资源里取）
            int rootId = activity.getResources().getIdentifier("root", "id", TARGET_PKG);
            View rootView = rootId != 0 ? activity.findViewById(rootId) : null;
            if (!(rootView instanceof ViewGroup)) {
                log(Log.WARN, TAG, "未找到设置页 root 容器");
                return;
            }
            ViewGroup root = (ViewGroup) rootView;

            // 宿主可能在滚动/重绘时再次调用 G1；已有入口或展开面板时不要重复创建。
            if (root.findViewWithTag(EMBEDDED_SETTINGS_TAG) != null
                    || root.findViewWithTag(ENTRY_TAG) != null) {
                return;
            }

            // 1. 清理旧入口（root 直接子中的）
            removeOldEntry(root);

            // 2. 找标题栏：root 中第一个 RelativeLayout 子节点（含"通用设置"标题）
            //    入口插到标题栏之后、内容区之前 —— 完全不碰小黑盒的设置项容器，避免破坏设置项
            int insertIndex = -1;
            for (int i = 0; i < root.getChildCount(); i++) {
                View c = root.getChildAt(i);
                if (c instanceof RelativeLayout) {
                    insertIndex = i; // 标题栏
                    break;
                }
            }
            if (insertIndex < 0) {
                log(Log.WARN, TAG, "未找到设置页标题栏");
                return;
            }

            // 3. 构建 BetterHeybox 入口（纯代码 + 主题色，避免反射创建小黑盒组件引发异常），插到标题栏之后
            final int insertPosition = insertIndex + 1;
            View entry = buildEntryView(activity);
            entry.setTag(ENTRY_TAG);
            entry.setClickable(true);
            entry.setFocusable(true);
            entry.setElevation(dp(activity, 2));
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        showEmbeddedSettings(activity, root, entry, insertPosition);
                    } catch (Throwable t) {
                        log(Log.ERROR, TAG, "渲染内嵌设置界面失败", t);
                        Toast.makeText(activity, "BetterHeybox 内嵌设置加载失败",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            root.addView(entry, insertIndex + 1);
            log(Log.INFO, TAG, "✔ 设置入口已插入标题栏下方");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "插入设置入口异常", t);
        }
    }

    /**
     * 在小黑盒设置页原地渲染模块设置，不启动模块自己的 SettingsActivity。
     * 原设置项只在面板展开期间隐藏，关闭面板后恢复，避免破坏宿主页面状态。
     */
    private void showEmbeddedSettings(final Activity activity, final ViewGroup root,
                                      final View entry, final int panelIndex)
            throws PackageManager.NameNotFoundException {
        if (root.findViewWithTag(EMBEDDED_SETTINGS_TAG) != null) {
            return;
        }

        Context moduleContext = activity.createPackageContext(
                "com.better.heybox", Context.CONTEXT_IGNORE_SECURITY);
        // 使用宿主当前 Configuration，确保小黑盒自己的深色模式也能命中 values-night。
        moduleContext = moduleContext.createConfigurationContext(
                new Configuration(activity.getResources().getConfiguration()));
        // 保留模块自己的 Theme 和 Resources。宿主 Theme 可能包含小黑盒私有资源 ID，
        // 直接 setTo 到模块 Context 会在解析 TextView 颜色时触发 Resources$NotFoundException。
        ContextThemeWrapper themedModuleContext = new ContextThemeWrapper(
                moduleContext, android.R.style.Theme_DeviceDefault_DayNight);
        View panel = LayoutInflater.from(themedModuleContext)
                .inflate(com.better.heybox.R.layout.activity_settings, root, false);
        panel.setTag(EMBEDDED_SETTINGS_TAG);
        panel.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 面板必须覆盖宿主原页面，避免底层文字透出或与开关重叠。
        applyEmbeddedPalette(panel, activity);
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setElevation(dp(activity, 4));

        final List<View> hiddenViews = new ArrayList<>();
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child != entry && child != panel && i >= panelIndex
                    && child.getVisibility() != View.GONE) {
                hiddenViews.add(child);
                child.setVisibility(View.GONE);
            }
        }
        entry.setVisibility(View.GONE);
        bindEmbeddedSettings(activity, panel, new Runnable() {
            @Override
            public void run() {
                root.removeView(panel);
                for (View child : hiddenViews) {
                    child.setVisibility(View.VISIBLE);
                }
                entry.setVisibility(View.VISIBLE);
            }
        });
        root.addView(panel, Math.min(panelIndex, root.getChildCount()));
        panel.bringToFront();
        log(Log.INFO, TAG, "✔ BetterHeybox 设置已在小黑盒页面内展开");
    }

    /** 宿主切换深浅色后刷新已打开的内嵌面板，不重新创建或跳转 Activity。 */
    private void refreshSettingsThemeIfOpen(Activity activity) {
        try {
            int rootId = activity.getResources().getIdentifier("root", "id", TARGET_PKG);
            View rootView = rootId != 0 ? activity.findViewById(rootId) : null;
            if (!(rootView instanceof ViewGroup)) {
                return;
            }
            ViewGroup root = (ViewGroup) rootView;
            View entry = root.findViewWithTag(ENTRY_TAG);
            if (entry != null) {
                applyEntryPalette(entry, activity);
            }
            View panel = root.findViewWithTag(EMBEDDED_SETTINGS_TAG);
            if (panel != null) {
                applyEmbeddedPalette(panel, activity);
                View close = panel.findViewById(com.better.heybox.R.id.btn_exit);
                if (close != null) {
                    ThemeUtils.applyFilledButton(close, activity, 24);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "刷新内嵌设置主题失败", t);
        }
    }

    /** 绑定内嵌面板控件；数据源使用当前 Hook 进程可访问的 RemotePreferences。 */
    private void bindEmbeddedSettings(final Activity activity, View panel, final Runnable onClose) {
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_open_screen,
                App.KEY_OPEN_SCREEN, true);
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_feed_ad,
                App.KEY_FEED_AD, true);
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_bubble_ad,
                App.KEY_BUBBLE_AD, true);
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_corner_ad,
                App.KEY_CORNER_AD, true);
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_promote_ad,
                App.KEY_PROMOTE_AD, true);

        bindEmbeddedRestartSwitch(activity, panel, com.better.heybox.R.id.switch_hide_tab_home,
                App.KEY_HIDE_TAB_HOME, false);
        bindEmbeddedRestartSwitch(activity, panel, com.better.heybox.R.id.switch_hide_tab_hot,
                App.KEY_HIDE_TAB_HOT, false);
        bindEmbeddedRestartSwitch(activity, panel, com.better.heybox.R.id.switch_hide_tab_game,
                App.KEY_HIDE_TAB_GAME, false);
        bindEmbeddedRestartSwitch(activity, panel, com.better.heybox.R.id.switch_hide_add,
                App.KEY_HIDE_ADD, false);
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_copy_post,
                App.KEY_COPY_POST, true);
        bindEmbeddedSwitch(activity, panel, com.better.heybox.R.id.switch_block_update,
                App.KEY_BLOCK_UPDATE, false);

        TextView title = panel.findViewById(com.better.heybox.R.id.settings_embedded_title);
        if (title != null) {
            title.setText("BetterHeybox 设置");
        }
        TextView versionFooter = panel.findViewById(com.better.heybox.R.id.version_footer);
        if (versionFooter != null) {
            Context moduleContext = versionFooter.getContext();
            versionFooter.setText(moduleContext.getString(com.better.heybox.R.string.version_footer,
                    VersionUtils.getVersionName(moduleContext)));
        }
        View close = panel.findViewById(com.better.heybox.R.id.btn_exit);
        if (close != null) {
            close.setOnClickListener(v -> onClose.run());
            if (close instanceof TextView) {
                ((TextView) close).setText("返回小黑盒设置");
            }
            ThemeUtils.applyFilledButton(close, activity, 24);
        }
    }

    /** 强制同步卡片和分割线颜色，避免宿主使用自定义深色模式时资源限定符不更新。 */
    private void applyEmbeddedPalette(View root, Context hostContext) {
        boolean dark = isHostDarkMode(hostContext);
        int background = dark ? 0xFF000000 : 0xFFFFFFFF;
        int surface = dark ? 0xFF1F1F1F : 0xFFF7F7F7;
        int primary = dark ? 0xFFFFFFFF : 0xFF000000;
        int secondary = dark ? 0xFFBDBDBD : 0xFF666666;
        int divider = dark ? 0x40FFFFFF : 0x1F000000;
        root.setBackgroundColor(background);
        applyEmbeddedPaletteRecursive(root, surface, divider, primary, secondary);
        applySwitchPaletteRecursive(root, hostContext, dark);
    }

    private void applyEmbeddedPaletteRecursive(View view, int surface, int divider,
                                               int primary, int secondary) {
        if (view instanceof TextView) {
            TextView textView = (TextView) view;
            textView.setTextColor(textView.getTextSize() <= dp(view.getContext(), 14.5f)
                    ? secondary : primary);
        }
        if (view.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) view.getBackground()).setColor(surface);
        } else if (view.getLayoutParams() != null
                && view.getLayoutParams().height >= 0
                && view.getLayoutParams().height <= dp(view.getContext(), 2)
                && view.getBackground() instanceof ColorDrawable) {
            view.setBackgroundColor(divider);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyEmbeddedPaletteRecursive(group.getChildAt(i), surface, divider,
                        primary, secondary);
            }
        }
    }

    private void applySwitchPaletteRecursive(View view, Context hostContext, boolean dark) {
        if (view instanceof Switch) {
            int accent = ThemeUtils.resolveAccent(hostContext);
            int inactiveThumb = dark ? 0xFFBDBDBD : 0xFF757575;
            int inactiveTrack = dark ? 0x66757575 : 0x4D000000;
            int checkedTrack = android.graphics.Color.argb(0x66,
                    android.graphics.Color.red(accent),
                    android.graphics.Color.green(accent),
                    android.graphics.Color.blue(accent));
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked}, new int[]{}
            };
            ((Switch) view).setThumbTintList(new ColorStateList(states,
                    new int[]{accent, inactiveThumb}));
            ((Switch) view).setTrackTintList(new ColorStateList(states,
                    new int[]{checkedTrack, inactiveTrack}));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applySwitchPaletteRecursive(group.getChildAt(i), hostContext, dark);
            }
        }
    }

    private void bindEmbeddedSwitch(final Activity activity, View panel, int switchId,
                                    final String key, boolean defaultValue) {
        final Switch sw = panel.findViewById(switchId);
        if (sw == null) {
            return;
        }
        sw.setChecked(readEmbeddedBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            writeEmbeddedBoolean(activity, key, isChecked);
        });
    }

    private void bindEmbeddedRestartSwitch(final Activity activity, View panel, int switchId,
                                           final String key, boolean defaultValue) {
        final Switch sw = panel.findViewById(switchId);
        if (sw == null) {
            return;
        }
        sw.setChecked(readEmbeddedBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!writeEmbeddedBoolean(activity, key, isChecked)) {
                return;
            }
            new AlertDialog.Builder(activity)
                    .setTitle("重新启动APP生效")
                    .setMessage("底栏改动需重启小黑盒后生效")
                    .setPositiveButton("我知道了", null)
                    .show();
        });
    }

    private boolean readEmbeddedBoolean(String key, boolean defaultValue) {
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            return prefs != null ? prefs.getBoolean(key, defaultValue) : defaultValue;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "读取设置失败: " + key, t);
            return defaultValue;
        }
    }

    private boolean writeEmbeddedBoolean(Activity activity, String key, boolean value) {
        try {
            Intent request = new Intent(PreferenceReceiver.ACTION_SET_BOOLEAN)
                    .setComponent(new android.content.ComponentName(
                            "com.better.heybox", "com.better.heybox.PreferenceReceiver"))
                    .putExtra(PreferenceReceiver.EXTRA_KEY, key)
                    .putExtra(PreferenceReceiver.EXTRA_VALUE, value);
            activity.sendBroadcast(request);
            return true;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "写入设置失败: " + key, t);
            Toast.makeText(activity, R.string.service_not_ready, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** 移除旧版入口（仅清 root 直接子，不递归，避免误删设置项） */
    private void removeOldEntry(ViewGroup root) {
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            if (ENTRY_TAG.equals(root.getChildAt(i).getTag())) {
                root.removeViewAt(i);
            }
        }
    }

    /** 纯代码构建入口项（高对比硬编码颜色，深浅色均清晰可见） */
    private View buildEntryView(Context context) {
        boolean dark = isHostDarkMode(context);
        int textPrimary = dark ? 0xFFFFFFFF : 0xFF000000;
        int textSecondary = dark ? 0xFFBDBDBD : 0xFF666666;
        int background = dark ? 0xFF000000 : 0xFFFFFFFF;

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(context, 16);
        row.setPadding(pad, dp(context, 16), pad, dp(context, 16));
        row.setBackgroundColor(background);

        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(context);
        title.setText("BetterHeybox 设置");
        title.setTextSize(16);
        title.setTextColor(textPrimary);
        textBox.addView(title);

        TextView desc = new TextView(context);
        desc.setText("广告过滤与界面增强");
        desc.setTextSize(12);
        desc.setTextColor(textSecondary);
        textBox.addView(desc);

        row.addView(textBox);

        TextView arrow = new TextView(context);
        arrow.setText("›");
        arrow.setTextSize(22);
        arrow.setTextColor(textSecondary);
        row.addView(arrow);

        applyEntryPalette(row, context);

        return row;
    }

    private void applyEntryPalette(View entry, Context context) {
        boolean dark = isHostDarkMode(context);
        int primary = dark ? 0xFFFFFFFF : 0xFF000000;
        int secondary = dark ? 0xFFBDBDBD : 0xFF666666;
        int background = dark ? 0xFF000000 : 0xFFFFFFFF;
        entry.setBackgroundColor(background);
        if (!(entry instanceof ViewGroup)) {
            return;
        }
        ViewGroup row = (ViewGroup) entry;
        if (row.getChildCount() > 0 && row.getChildAt(0) instanceof ViewGroup) {
            ViewGroup textBox = (ViewGroup) row.getChildAt(0);
            if (textBox.getChildCount() > 0 && textBox.getChildAt(0) instanceof TextView) {
                ((TextView) textBox.getChildAt(0)).setTextColor(primary);
            }
            if (textBox.getChildCount() > 1 && textBox.getChildAt(1) instanceof TextView) {
                ((TextView) textBox.getChildAt(1)).setTextColor(secondary);
            }
        }
        if (row.getChildCount() > 1 && row.getChildAt(1) instanceof TextView) {
            ((TextView) row.getChildAt(1)).setTextColor(secondary);
        }
    }

    private boolean isHostDarkMode(Context context) {
        Boolean override = HOST_DARK_MODE_OVERRIDE;
        if (override != null) {
            return override;
        }
        try {
            int background = ThemeUtils.resolveColor(context, android.R.attr.colorBackground, 0);
            if (background != 0) {
                return isDarkColor(background);
            }
        } catch (Throwable ignored) {
        }
        return ThemeUtils.isDarkMode(context);
    }

    private boolean isDarkColor(int color) {
        double luminance = (0.2126 * linearColor(android.graphics.Color.red(color))
                + 0.7152 * linearColor(android.graphics.Color.green(color))
                + 0.0722 * linearColor(android.graphics.Color.blue(color)));
        return luminance < 0.45;
    }

    private double linearColor(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928
                ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 5. 底部导航栏屏蔽 ====================

    private void hookBottomTabs(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = clazz.getDeclaredMethod("onCreate", android.os.Bundle.class);
            hook(onCreate).intercept(chain -> {
                Object result = chain.proceed(); // 先执行原 onCreate（底部 tab 已初始化）
                try {
                    applyBottomTabSettings(chain.getThisObject());
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "应用底部导航栏设置异常", t);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 底部导航栏 Hook 已安装");

            // hook onResume：热重载后切回小黑盒立即重新应用底栏设置（不依赖生命周期观察者回调）
            try {
                Method onResume = clazz.getDeclaredMethod("onResume");
                hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        applyBottomTabSettings(chain.getThisObject());
                    } catch (Throwable t) {
                        log(Log.WARN, TAG, "onResume 应用底栏设置失败: " + t);
                    }
                    return result;
                });
                log(Log.INFO, TAG, "✔ 底栏 onResume Hook 已安装");
            } catch (Throwable t) {
                log(Log.WARN, TAG, "底栏 onResume Hook 失败: " + t);
            }

            // 加号/底栏会被 MainActivity$j.b(Boolean) 生命周期回调重新 setVisibility(0) 显示，
            // hook 该回调，显示后重新应用隐藏设置
            try {
                Class<?> observerCls = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                for (Method m : observerCls.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Boolean.class) {
                        hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object mainActivity = findOuterInstance(chain.getThisObject(), cl);
                                if (mainActivity != null) {
                                    applyBottomTabSettings(mainActivity);
                                }
                            } catch (Throwable t) {
                                log(Log.WARN, TAG, "底栏状态回调后重新隐藏失败: " + t);
                            }
                            return result;
                        });
                        log(Log.INFO, TAG, "✔ 底栏状态回调 Hook 已安装");
                        break;
                    }
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "底栏状态回调 Hook 安装失败: " + t);
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 底部导航栏 Hook 失败", t);
        }
    }

    /** 通用查找：内部类里指向 MainActivity 的字段（this$0 被 Robust 改名） */
    private Object findOuterInstance(Object innerObj, ClassLoader cl) {
        try {
            Class<?> mainCls = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            for (Field f : innerObj.getClass().getDeclaredFields()) {
                if (f.getType() == mainCls) {
                    f.setAccessible(true);
                    return f.get(innerObj);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "查找外部 MainActivity 实例失败: " + t);
        }
        return null;
    }

    /** 根据开关反射隐藏底部导航栏的 tab（首页/热点/游戏库）与加号 */
    private void applyBottomTabSettings(Object activityObj) {
        try {
            Object binding = findViewBinding(activityObj);
            if (binding == null) {
                log(Log.WARN, TAG, "未找到 ViewBinding 字段（fi.i1）");
                return;
            }
            // 诊断：打印 hook 侧读到的开关值
            log(Log.INFO, TAG, "开关值: home=" + isEnabled(App.KEY_HIDE_TAB_HOME, false)
                    + " hot=" + isEnabled(App.KEY_HIDE_TAB_HOT, false)
                    + " game=" + isEnabled(App.KEY_HIDE_TAB_GAME, false)
                    + " add=" + isEnabled(App.KEY_HIDE_ADD, false));
            boolean anyTabHidden = false;
            if (isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabField(binding, "j", "首页");
                anyTabHidden = true;
            }
            if (isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabField(binding, "k", "热点");
                anyTabHidden = true;
            }
            if (isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabField(binding, "m", "游戏库");
                anyTabHidden = true;
            }
            // 加号：独立开关，或隐藏了任意 tab 时联动隐藏（保持底栏布局对称）
            if (isEnabled(App.KEY_HIDE_ADD, false) || anyTabHidden) {
                hideTabField(binding, "r", "加号");
                // 同时去掉「推荐」占位（rb_3 默认 INVISIBLE 占位），让剩余 tab 完全等分、无空白
                hideTabField(binding, "l", "推荐占位");
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "底部导航栏设置应用失败: " + t);
        }
    }

    /** 通用查找：遍历字段找类型为 fi.i1 的绑定类字段（Robust 混淆后名字为 v4） */
    private Object findViewBinding(Object activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (f.getType().getName().endsWith(".i1")) {
                    f.setAccessible(true);
                    return f.get(activity);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "查找 ViewBinding 失败: " + t);
        }
        return null;
    }

    /** 诊断：打印对象所有字段名和类型 */
    private void dumpFields(Object obj) {
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                log(Log.WARN, TAG, "  field: " + f.getName() + " : " + f.getType().getName());
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "转储字段失败: " + t);
        }
    }

    private void hideTabField(Object binding, String fieldName, String label) {
        try {
            Field field = binding.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object obj = field.get(binding);
            if (obj instanceof View) {
                final View v = (View) obj;
                v.setVisibility(View.GONE);
                // 小黑盒会在启动/生命周期回调中延迟重新显示 tab/加号，
                // 延迟多次重新隐藏以覆盖（否则出现 tab 与加号重合、布局错乱）
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 500);
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 1500);
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 3000);
                log(Log.INFO, TAG, "隐藏 " + label + ": " + v.getVisibility());
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "隐藏 tab 失败 (" + label + ")，字段 " + fieldName + " 可能被 Robust 重命名，转储字段名：");
            dumpFields(binding);
        }
    }

    // ==================== 6. 推广贴屏蔽 ====================

    private void hookPromotePosts(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.bbs.utils.b", false, cl);
            // 遍历找渲染 BBS 帖子的静态方法 L（5 参数），避免精确参数类型匹配受 Robust 影响
            for (Method m : clazz.getDeclaredMethods()) {
                if ("L".equals(m.getName()) && m.getParameterTypes().length == 5) {
                    hook(m).intercept(chain -> {
                        try {
                            if (!isEnabled(App.KEY_PROMOTE_AD, true)) {
                                return chain.proceed();
                            }
                            Object bbsLink = chain.getArg(1);
                            String ct = getContentType(bbsLink);
                            if ("28".equals(ct) || "29".equals(ct)) {
                                log(Log.INFO, TAG, "屏蔽推广贴 (content_type=" + ct + ")");
                                hideItemView(chain.getArg(3));
                                return null; // 跳过原渲染
                            }
                            // 屏蔽指定官方账号的帖子（小黑盒推广 / 商城看板娘）
                            String username = getUsername(chain.getArg(2));
                            if ("小黑盒推广".equals(username) || "商城看板娘".equals(username)) {
                                log(Log.INFO, TAG, "屏蔽账号帖子: " + username);
                                hideItemView(chain.getArg(3));
                                return null;
                            }
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, "推广贴判断异常，放行: " + t);
                        }
                        return chain.proceed();
                    });
                    log(Log.INFO, TAG, "✔ 推广贴屏蔽 Hook 已安装");
                    return;
                }
            }
            log(Log.WARN, TAG, "✘ 未找到推广贴渲染方法 L");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 推广贴屏蔽 Hook 失败", t);
        }
    }

    /** 反射获取 BBSLinkObj 的 content_type */
    private String getContentType(Object bbsLink) {
        try {
            Method getter = bbsLink.getClass().getMethod("getContent_type");
            Object v = getter.invoke(bbsLink);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 反射获取帖子作者的 username（BBSUserInfoObj.getUsername()） */
    private String getUsername(Object userInfo) {
        try {
            if (userInfo == null) {
                return null;
            }
            Method getter = userInfo.getClass().getMethod("getUsername");
            Object v = getter.invoke(userInfo);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 隐藏 ViewHolder 的 itemView（高度置 0，避免列表留空白） */
    private void hideItemView(Object viewHolder) {
        try {
            Field itemViewField = viewHolder.getClass().getField("itemView");
            Object v = itemViewField.get(viewHolder);
            if (v instanceof View) {
                View itemView = (View) v;
                itemView.setVisibility(View.GONE);
                ViewGroup.LayoutParams lp = itemView.getLayoutParams();
                if (lp != null) {
                    lp.height = 0;
                    itemView.setLayoutParams(lp);
                }
            }
        } catch (Throwable t) {
            log(Log.WARN, TAG, "隐藏 itemView 失败: " + t);
        }
    }

    // ==================== 6. 帖子正文复制（系统标准文本选择） ====================

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
                log(Log.WARN, TAG, "✘ 未找到 TextSelectHandler.onTouch");
                return;
            }
            hook(onTouch).intercept(chain -> false); // 不消费任何触摸 → 长按回到 TextView 默认逻辑
            log(Log.INFO, TAG, "✔ TextSelectHandler 防复制拦截已解除");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ TextSelectHandler 解除失败", t);
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
                log(Log.WARN, TAG, "✘ 未找到 PostPictureFragmentV2.installViews");
                return;
            }
            hook(target).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof View) {
                        final View content = (View) arg;
                        // 等布局稳定后恢复标准文本选择（installViews 同步流程里小黑盒会设置拦截）
                        content.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    enablePostTextSelect(content);
                                } catch (Throwable t) {
                                    log(Log.WARN, TAG, "正文选择设置异常: " + t);
                                }
                            }
                        }, 300);
                    }
                } catch (Throwable t) {
                    log(Log.WARN, TAG, "正文选择调度异常: " + t);
                }
                return result;
            });
            log(Log.INFO, TAG, "✔ 帖子正文复制 Hook 已安装");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "✘ 帖子正文复制 Hook 失败", t);
        }
    }

    /** 恢复标题/正文 TextView 的系统标准文本选择（绕过小黑盒防复制） */
    private void enablePostTextSelect(View root) {
        if (!isEnabled(App.KEY_COPY_POST, true)) {
            return;
        }
        String[] idNames = {"tv_title", "tv_desc"};
        for (String idName : idNames) {
            try {
                int id = root.getResources().getIdentifier(idName, "id", TARGET_PKG);
                if (id == 0) {
                    continue;
                }
                View v = root.findViewById(id);
                if (v instanceof TextView) {
                    TextView tv = (TextView) v;
                    tv.setTextIsSelectable(true);
                    tv.setOnTouchListener(null); // 清掉 TextSelectHandler 拦截
                    try {
                        tv.setCustomSelectionActionModeCallback(null);
                    } catch (Throwable ignored) {
                    }
                    log(Log.INFO, TAG, "✔ 已开启标准文本选择: " + idName);
                }
            } catch (Throwable t) {
                log(Log.WARN, TAG, "设置文本选择失败 (" + idName + "): " + t);
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
                "androidx.core.widget.NestedScrollView", // binding 根（正文所在）
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
                hook(m).intercept(chain -> {
                    try {
                        Object self = chain.getThisObject();
                        if (self instanceof ViewGroup && hasSelectingTextView((ViewGroup) self)) {
                            return false; // 文本选择激活时放行触摸给 TextView
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
                log(Log.INFO, TAG, "✔ 滚动容器选择放行 Hook 已安装: " + name);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 子树中是否存在处于文本选择模式（有选中范围）的可选择 TextView */
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
