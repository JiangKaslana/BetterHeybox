package com.better.heybox;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 基类：通过 libxposed service 与框架通信。
 * 核心作用：把功能开关写入 RemotePreferences（LSPosed 数据库），
 * 供注入到小黑盒进程的 Hook 代码跨进程读取。
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterHeybox";

    /** RemotePreferences 分组名（Hook 侧用同名读取） */
    public static final String PREFS_GROUP = "betterheybox";

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
    public static final String KEY_BLOCK_UPDATE = "block_update";

    // 框架服务实例（volatile 保证跨线程可见）
    private static volatile XposedService sService;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "App.onCreate: pid=" + android.os.Process.myPid());
        XposedServiceHelper.registerListener(this);
        Log.i(TAG, "已注册 XposedService 监听器");
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        SharedPreferences pending = getSharedPreferences("betterheybox_pending", MODE_PRIVATE);
        Log.i(TAG, "XposedService 已绑定: service=" + describe(service)
                + ", pendingCount=" + pending.getAll().size());
        PreferenceReceiver.tryFlush(this, pending);
    }

    @Override
    public void onServiceDied(XposedService service) {
        Log.w(TAG, "XposedService 已断开: service=" + describe(service)
                + ", current=" + describe(sService));
        sService = null;
    }

    /** 获取跨进程开关存储；框架服务未连接时返回 null */
    public static SharedPreferences getPrefs() {
        XposedService service = sService;
        if (service == null) {
            Log.w(TAG, "获取 RemotePreferences 失败: XposedService 未绑定");
            return null;
        }
        try {
            SharedPreferences prefs = service.getRemotePreferences(PREFS_GROUP);
            if (prefs == null) {
                Log.e(TAG, "获取 RemotePreferences 失败: service 返回 null, group=" + PREFS_GROUP);
            } else {
                Log.i(TAG, "获取 RemotePreferences 成功: group=" + PREFS_GROUP);
            }
            return prefs;
        } catch (Throwable t) {
            Log.e(TAG, "获取 RemotePreferences 异常: group=" + PREFS_GROUP, t);
            return null;
        }
    }

    private static String describe(XposedService service) {
        return service == null ? "null"
                : service.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(service));
    }

    /** 获取框架服务实例（未连接时为 null） */
    public static XposedService getService() {
        return sService;
    }
}
