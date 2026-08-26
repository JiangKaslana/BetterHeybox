package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;
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

/**
 * BetterHeybox 模块入口（libxposed Modern API 102）。
 *
 * <p>各功能 Hook 已按模块拆分到 {@code hooks} 子包，本类只保留：</p>
 * <ul>
 *   <li>模块生命周期（onModuleLoaded / onHotReloading / onPackageReady）</li>
 *   <li>Hook 安装编排（{@link #installHooks} 按功能逐类安装）</li>
 *   <li>共享工具（开关读取 {@link #isEnabled}、日志 {@link #logd}、dp 换算）</li>
 * </ul>
 *
 * <p>功能分类：</p>
 * <ul>
 *   <li>{@link GeneralHook}    通用：版本检测 / 屏蔽更新</li>
 *   <li>{@link AdFilterHook}   广告过滤：开屏 / 信息流 / 气泡 / 角标</li>
 *   <li>{@link SettingsEntryHook} 设置页入口注入 + 内嵌设置面板</li>
 *   <li>{@link BottomTabHook}  底部导航栏隐藏</li>
 *   <li>{@link PromotePostHook} 推广贴屏蔽</li>
 *   <li>{@link TextSelectHook} 解除复制 / 标准文本选择 / 跨行选择修复</li>
 *   <li>{@link ImageShareHook} 图片系统分享</li>
 * </ul>
 */
public class MainModule extends XposedModule {

    /** 日志 TAG */
    public static final String TAG = "BetterHeybox";

    /** 目标应用（小黑盒）包名 */
    public static final String TARGET_PKG = "com.max.xiaoheihe";

    /** 目标小黑盒主版本（兼容旧引用/日志） */
    public static final String TARGET_HEYBOX_VERSION = "1.3.393";

    /** 支持的小黑盒版本集合（1.3.393 / 1.3.394 双版本兼容） */
    public static final Set<String> SUPPORTED_HEYBOX_VERSIONS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    "1.3.393",
                    "1.3.394"
            )));

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        logd(Log.INFO, TAG, "onModuleLoaded: " + param.getProcessName());
        logd(Log.INFO, TAG, "framework: " + getFrameworkName()
                + " (" + getFrameworkVersion() + ") API " + getApiVersion());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        // 允许热重载（否则设置界面「立即重启」的热重载会被框架拒绝）
        logd(Log.INFO, TAG, "允许热重载");
        return true;
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        logd(Log.INFO, TAG, "onPackageReady: " + packageName);

        if (TARGET_PKG.equals(packageName)) {
            logd(Log.INFO, TAG, ">>> 命中小黑盒，安装 Hook");
            installHooks(param);
        }
    }

    /** 按功能模块安装全部 Hook */
    private void installHooks(PackageReadyParam param) {
        ClassLoader cl = param.getClassLoader();

        new GeneralHook(this).install(cl);
        new AdFilterHook(this).install(cl);
        new SettingsEntryHook(this).install(cl);
        new BottomTabHook(this).install(cl);
        new PromotePostHook(this).install(cl);
        new TextSelectHook(this).install(cl);
        new ImageShareHook(this).install(cl);
        new DailyTaskHook(this).install(cl);

        logd(Log.INFO, TAG, "Hook 安装流程结束");
    }

    /**
     * 读取功能开关：优先小黑盒进程本地配置（内嵌面板直写，不跨进程，任何系统都可靠），
     * 键不存在时回退框架 RemotePreferences（模块设置页写入的值，直连框架已确认可用）。
     */
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
            // 读取失败按默认值处理
        }
        return def;
    }

    /** 读取字符串开关：优先小黑盒进程本地配置，其次框架 RemotePreferences（模块设置页写入值） */
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
            // 读取失败按默认值处理
        }
        return def;
    }

    /**
     * 模块日志统一出口：先输出到 LSPosed 日志（logcat），
     * 「记录日志」开关（{@link App#KEY_LOG}）开启时同步写入文件（见 {@link LogRecorder}）。
     * 原 XposedModule.log 为 final 方法无法覆写，故全量改用本方法。
     */
    public void logd(int level, String tag, String msg) {
        try {
            boolean logEnabled = isEnabled(App.KEY_LOG, false);
            LogRecorder.setEnabled(logEnabled);
            if (logEnabled) {
                LogRecorder.record(level, tag, msg);
            }
        } catch (Throwable ignored) {
            // 文件日志失败不影响主流程
        }
        log(level, tag, msg);
    }

    /** 带异常的模块日志（见 {@link #logd(int, String, String)}） */
    public void logd(int level, String tag, String msg, Throwable tr) {
        try {
            boolean logEnabled = isEnabled(App.KEY_LOG, false);
            LogRecorder.setEnabled(logEnabled);
            if (logEnabled) {
                LogRecorder.record(level, tag, msg, tr);
            }
        } catch (Throwable ignored) {
            // 文件日志失败不影响主流程
        }
        log(level, tag, msg, tr);
    }

    /** dp 换算（内嵌设置面板布局使用） */
    public int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
