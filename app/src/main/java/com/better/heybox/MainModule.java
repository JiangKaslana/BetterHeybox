package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

import com.better.heybox.hooks.AdFilterHook;
import com.better.heybox.hooks.BottomTabHook;
import com.better.heybox.hooks.DailyTaskHook;
import com.better.heybox.hooks.GeneralHook;
import com.better.heybox.hooks.ImageShareHook;
import com.better.heybox.hooks.PromotePostHook;
import com.better.heybox.hooks.SettingsEntryHook;
import com.better.heybox.hooks.TextSelectHook;
import com.better.heybox.hooks.VideoDownloadHook;

/**
 * BetterHeybox 模块入口（libxposed Modern API 102）。
 * 本类只保留：模块生命周期，Hook 安装编排
 *   {@link GeneralHook}    通用：版本检测 / 屏蔽更新
 *   {@link AdFilterHook}   广告过滤：开屏 / 信息流 / 气泡 / 角标
 *   {@link SettingsEntryHook} 设置页入口注入 + 内嵌设置面板
 *   {@link BottomTabHook}  底部导航栏隐藏
 *   {@link PromotePostHook} 推广贴屏蔽
 *   {@link TextSelectHook} 解除复制 / 标准文本选择 / 跨行选择修复
 *   {@link ImageShareHook} 图片系统分享
 */
public class MainModule extends XposedModule {

    /** 日志 TAG */
    public static final String TAG = "BetterHeybox";

    /** 每日任务 Hook 实例 */
    private com.better.heybox.hooks.DailyTaskHook dailyTaskHook;

    /** 目标应用包名 */
    public static final String TARGET_PKG = "com.max.xiaoheihe";

    /** 目标小黑盒主版本 */
    public static final String TARGET_HEYBOX_VERSION = "1.3.393";
    public static final Set<String> SUPPORTED_HEYBOX_VERSIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "1.3.393",
                    "1.3.394"
            )));

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Checkpoint.mark("onModuleLoaded: %s", param.getProcessName());
        Checkpoint.mark("framework: %s (%s) API %d", getFrameworkName(), getFrameworkVersion(), getApiVersion());
        logd(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName());
        logd(Log.INFO, TAG, "framework: " + getFrameworkName()
                + " (" + getFrameworkVersion() + ") API " + getApiVersion());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // 允许热重载
        logd(Log.INFO, TAG, "允许热重载");
        return true;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        Checkpoint.mark("onPackageReady: %s (target=%b)", packageName, TARGET_PKG.equals(packageName));
        logd(Log.INFO, TAG, "onPackageReady: " + packageName);

        if (TARGET_PKG.equals(packageName)) {
            logd(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook");
            installHooks(param);
        }
    }
    private void installHooks(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();
        Checkpoint.mark(">>> 开始安装 Hook");
        long t0 = SystemClock.elapsedRealtime();

        installHook("通用", new GeneralHook(this)::install, cl);
        installHook("广告过滤", new AdFilterHook(this)::install, cl);
        installHook("设置入口", new SettingsEntryHook(this)::install, cl);
        installHook("底部导航", new BottomTabHook(this)::install, cl);
        installHook("推广贴", new PromotePostHook(this)::install, cl);
        installHook("文本选择", new TextSelectHook(this)::install, cl);
        installHook("图片分享", new ImageShareHook(this)::install, cl);
        installHook("视频下载", new VideoDownloadHook(this)::install, cl);
        installHook("每日任务", ignored -> {
            dailyTaskHook = new DailyTaskHook(this);
            dailyTaskHook.install(ignored);
        }, cl);

        Checkpoint.mark(">>> Hook 安装完成，总耗时 %d ms", SystemClock.elapsedRealtime() - t0);
        logd(Log.INFO, TAG, "Hook 安装流程结束");
        stashRuntimeStatus();
    }
    private interface HookInstaller {
        void install(ClassLoader cl);
    }
    private void installHook(String label, HookInstaller installer, ClassLoader cl) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            installer.install(cl);
            Checkpoint.mark("✔ %s Hook 安装完成 (%d ms)", label, SystemClock.elapsedRealtime() - t0);
        } catch (Throwable t) {
            Checkpoint.mark("✘ %s Hook 安装失败: %s (%d ms)",
                    label, t, SystemClock.elapsedRealtime() - t0);
        }
    }
    private void stashRuntimeStatus() {
        if (!BuildFlags.DEBUG) {
            return;
        }
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null) {
                prefs.edit().putString(App.KEY_RUNTIME_STATUS, Checkpoint.dump()).commit();
                logd(Log.INFO, TAG, "运行状态检查点已写入 RemotePreferences");
            }
        } catch (Throwable t) {
            logd(Log.WARN, TAG, "运行状态检查点写入失败", t);
        }
    }
    public boolean isEnabled(String key, boolean def) {
        if (HeyboxPrefs.contains(key)) {
            return HeyboxPrefs.getBoolean(key, def);
        }
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null && prefs.contains(key)) {
                return prefs.getBoolean(key, def);
            }
        } catch (Throwable t) {
        }
        return def;
    }
    public static String getHeyboxTabLabel(Context context, String resName, String def) {
        try {
            android.content.res.Resources res = null;
            int id = 0;
            try {
                res = context.getResources();
                id = res.getIdentifier(resName, "string", TARGET_PKG);
            } catch (Throwable ignored) {
            }
            if (id == 0) {
                try {
                    res = context.getPackageManager().getResourcesForApplication(TARGET_PKG);
                    id = res.getIdentifier(resName, "string", TARGET_PKG);
                } catch (Throwable ignored) {
                }
            }
            if (id != 0 && res != null) {
                return res.getString(id);
            }
        } catch (Throwable ignored) {
        }
        return def;
    }
    public String getString(String key, String def) {
        if (HeyboxPrefs.contains(key)) {
            return HeyboxPrefs.getString(key, def);
        }
        try {
            SharedPreferences prefs = getRemotePreferences(App.PREFS_GROUP);
            if (prefs != null && prefs.contains(key)) {
                return prefs.getString(key, def);
            }
        } catch (Throwable t) {
        }
        return def;
    }

    public void logd(int level, String tag, String msg) {
        try {
            boolean logEnabled = isEnabled(App.KEY_LOG, false);
            LogRecorder.setEnabled(logEnabled);
            if (logEnabled) {
                LogRecorder.record(level, tag, msg);
            }
        } catch (Throwable ignored) {
        }
        log(level, tag, msg);
    }
    public void logd(int level, String tag, String msg, Throwable tr) {
        try {
            boolean logEnabled = isEnabled(App.KEY_LOG, false);
            LogRecorder.setEnabled(logEnabled);
            if (logEnabled) {
                LogRecorder.record(level, tag, msg, tr);
            }
        } catch (Throwable ignored) {
        }
        log(level, tag, msg, tr);
    }

    /** dp 换算（内嵌设置面板布局使用） */
    public int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
    
    public void clearDailyTaskAndRetry(android.app.Activity activity) {
        if (dailyTaskHook != null) {
            dailyTaskHook.clearTodayAndRetry(activity);
        }
    }
}
