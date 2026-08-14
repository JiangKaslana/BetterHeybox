package com.better.heybox;

import android.app.Application;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 基类：通过 libxposed service 与框架通信。
 * 核心作用：把功能开关写入 RemotePreferences（LSPosed 数据库），
 * 供注入到小黑盒进程的 Hook 代码跨进程读取。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    /** RemotePreferences 分组名（Hook 侧用同名读取） */
    public static final String PREFS_GROUP = "betterheybox";

    /** 本地 UI 状态存储（用于桌面图标组件校正，不依赖 RemotePreferences 连接） */
    public static final String LOCAL_PREFS = "betterheybox_ui";

    /** 功能开关 key */
    public static final String KEY_OPEN_SCREEN = "open_screen";
    public static final String KEY_FEED_AD = "feed_ad";
    public static final String KEY_BUBBLE_AD = "bubble_ad";
    public static final String KEY_CORNER_AD = "corner_ad";
    public static final String KEY_PROMOTE_AD = "promote_ad";
    public static final String KEY_HIDE_TAB_HOME = "hide_tab_home";
    public static final String KEY_HIDE_TAB_HOT = "hide_tab_hot";
    public static final String KEY_HIDE_TAB_GAME = "hide_tab_game";
    public static final String KEY_HIDE_ADD = "hide_add";
    public static final String KEY_COPY_POST = "copy_post";
    public static final String KEY_HIDE_ICON = "hide_icon";

    // 框架服务实例（volatile 保证跨线程可见）
    private static volatile XposedService sService;

    @Override
    public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
        syncDesktopIconState();
    }

    /**
     * 校正桌面图标组件状态：与本地记录保持一致。
     * 防止「恢复图标」时残留 DISABLED（即使桌面未即时刷新，下次进程启动也会校正回 DEFAULT）。
     */
    private void syncDesktopIconState() {
        try {
            boolean hide = getSharedPreferences(LOCAL_PREFS, MODE_PRIVATE)
                    .getBoolean(KEY_HIDE_ICON, false);
            ComponentName cn = new ComponentName(this, "com.better.heybox.LaunchActivity");
            getPackageManager().setComponentEnabledSetting(cn,
                    hide
                            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable t) {
            // 校正失败不影响主流程
        }
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
    }

    @Override
    public void onServiceDied(XposedService service) {
        sService = null;
    }

    /** 获取跨进程开关存储；框架服务未连接时返回 null */
    public static SharedPreferences getPrefs() {
        XposedService service = sService;
        if (service == null) {
            return null;
        }
        try {
            return service.getRemotePreferences(PREFS_GROUP);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 获取框架服务实例（未连接时为 null） */
    public static XposedService getService() {
        return sService;
    }
}
