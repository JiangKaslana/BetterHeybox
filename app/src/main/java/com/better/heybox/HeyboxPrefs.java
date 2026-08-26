package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 小黑盒进程内的设置存储（架构调整：所有开关操作都在小黑盒进程内完成，不跨进程）。
 *
 * <p>背景：内嵌设置面板原先通过「显式广播 → 模块进程 → RemotePreferences」写回，
 * 部分系统会拦截跨进程广播，导致小黑盒内切换开关无效；而模块设置页直写框架
 * RemotePreferences 可以生效。为此把运行时配置改为直接存放在小黑盒自己的目录：</p>
 *
 * <pre>
 *   /data/user/0/com.max.xiaoheihe/shared_prefs/betterheybox.xml
 * </pre>
 *
 * <p>读写规则：</p>
 * <ul>
 *   <li>内嵌面板开关 → {@link #setBoolean} 直接写本文件（立即生效、重启保留）；</li>
 *   <li>Hook 侧 {@code isEnabled} → {@link #getBoolean} 优先本文件，
 *       键不存在时回退框架 RemotePreferences（模块设置页写入的值）；</li>
 *   <li>模块设置页仍写 RemotePreferences（直连框架，已确认可用），
 *       内嵌面板另发尽力而为的镜像广播同步（失败不影响本进程生效）。</li>
 * </ul>
 */
public final class HeyboxPrefs {

    public static final String PREFS_NAME = "betterheybox";

    private static volatile SharedPreferences sPrefs;

    private HeyboxPrefs() {
    }

    public static void init(Context context) {
        if (context != null && sPrefs == null) {
            sPrefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    public static SharedPreferences get() {
        SharedPreferences prefs = sPrefs;
        if (prefs == null) {
            Context context = resolveAppContext();
            if (context != null) {
                prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                sPrefs = prefs;
            }
        }
        return prefs;
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getBoolean(key, defaultValue) : defaultValue;
    }

    public static boolean contains(String key) {
        SharedPreferences prefs = get();
        return prefs != null && prefs.contains(key);
    }

    public static boolean setBoolean(String key, boolean value) {
        SharedPreferences prefs = get();
        if (prefs == null) {
            return false;
        }
        return prefs.edit().putBoolean(key, value).commit();
    }

    public static String getString(String key, String defaultValue) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getString(key, defaultValue) : defaultValue;
    }

    public static boolean setString(String key, String value) {
        SharedPreferences prefs = get();
        if (prefs == null) {
            return false;
        }
        return prefs.edit().putString(key, value).commit();
    }

    private static Context resolveAppContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = activityThread.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
