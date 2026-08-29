package com.better.heybox;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 基类：通过 libxposed service 与框架通信。
 * 核心作用：把功能开关写入 RemotePreferences（LSPosed 数据库），
 * 供注入到小黑盒进程的 Hook 代码跨进程读取。
 *
 * <p>开关写入策略（修复「模块进程未运行时切换无效」）：</p>
 * <ul>
 *   <li>框架服务已连接 → 直接写 RemotePreferences（即时跨进程生效）；</li>
 *   <li>框架服务未连接（冷启动/刚开机）→ 先写本地待提交缓存
 *       （{@link #PENDING_PREFS}，{@code commit()} 同步落盘），
 *       等服务绑定后由 {@link PreferenceReceiver#tryFlush} 统一补交。</li>
 * </ul>
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterHeybox";

    /** RemotePreferences 分组名（Hook 侧用同名读取） */
    public static final String PREFS_GROUP = "betterheybox";

    /** 本地待提交缓存（框架服务未连接时的开关写缓冲） */
    public static final String PENDING_PREFS = "betterheybox_pending";

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

    /** 自绘制文本选择：由模块自行实现选区/高亮/复制，绕过小黑盒与系统原生选择 UI */
    public static final String KEY_CUSTOM_TEXT_SELECT = "custom_text_select";

    public static final String KEY_BLOCK_UPDATE = "block_update";
    public static final String KEY_SYSTEM_SHARE = "system_share";

    /** 每日任务：自动完成分享任务开关 */
    public static final String KEY_DAILY_TASK_ENABLED = "daily_task_enabled";

    /** 每日任务：图片帖分享链接（第一种分享类型） */
    public static final String KEY_DAILY_TASK_PICTURE = "daily_task_picture";

    /** 每日任务：普通帖分享链接（第二种分享类型） */
    public static final String KEY_DAILY_TASK_NORMAL = "daily_task_normal";

    /** 每日任务：频道关注链接（第三种分享类型） */
    public static final String KEY_DAILY_TASK_CHANNEL = "daily_task_channel";

    /** 每日任务：今日已完成日期（yyyy-MM-dd，跨天自动重置） */
    public static final String KEY_DAILY_TASK_DONE_DATE = "daily_task_done_date";

    /** 每日任务：重置标志 */
    public static final String KEY_DAILY_TASK_RESET = "daily_task_reset";

    /** 每日任务：分享渠道（QQ=QQ/QQ空间，WECHAT=微信好友/朋友圈；默认 QQ） */
    public static final String KEY_SHARE_CHANNEL = "daily_task_channel_type";

    /** 伪装授予通知权限：让小黑盒认为通知已开启，获得签到加成（不真正申请权限） */
    public static final String KEY_FAKE_NOTIFICATION = "fake_notification";

    /** 视频下载：在支持的视频（mp4 直链等）上显示下载入口 */
    public static final String KEY_VIDEO_DOWNLOAD = "video_download";

    /** 视频下载保存目录：相册 Movies/ 下的子目录名（默认 BetterHeybox） */
    public static final String KEY_VIDEO_DIR = "video_download_dir";

    /** 视频下载：HLS/TS 下载完成后自动转封装为 MP4（默认开，失败保留 ts） */
    public static final String KEY_VIDEO_TO_MP4 = "video_download_to_mp4";

    /** 净化分享链接：复制链接 / 系统分享时去掉 sid、share_app_id 等追踪参数（默认开） */
    public static final String KEY_PURIFY_SHARE_LINK = "purify_share_link";

    /** 日志开关：开启后自动记录模块日志到文件 */
    public static final String KEY_LOG = "log";

    /** 小黑盒内置 WebView 的 Chrome DevTools 远程调试开关 */
    public static final String KEY_WEBVIEW_DEVTOOLS = "webview_devtools";

    /** 液态玻璃底栏开关及参考项目兼容配置 */
    public static final String KEY_LIQUID_GLASS = "liquid_glass";
    public static final String KEY_GLASS_IMMERSIVE = "glass_immersive";
    public static final String KEY_GLASS_ADAPTIVE = "glass_adaptive";
    public static final String KEY_GLASS_DARK_COLOR = "glass_dark_color";
    public static final String KEY_GLASS_DARK_ALPHA = "glass_dark_alpha";
    public static final String KEY_GLASS_LIGHT_COLOR = "glass_light_color";
    public static final String KEY_GLASS_LIGHT_ALPHA = "glass_light_alpha";
    public static final String KEY_GLASS_BAR_HEIGHT = "glass_bar_height";
    public static final String KEY_GLASS_BAR_OFFSET = "glass_bar_offset";

    public static final String KEY_GLASS_FIT_TABS = "glass_fit_tabs";

    /** 参考项目玻璃颜色预设/透明度/布局参数 */
    public static final String KEY_GLASS_DARK_PRESET = "glass_dark_preset";
    public static final String KEY_GLASS_LIGHT_PRESET = "glass_light_preset";

    /** 设置页「打开网页」入口保存的 URL */
    public static final String KEY_WEBVIEW_ENTRY_URL = "webview_entry_url";

    /** 运行状态检查点（Debug 构建：小黑盒进程 Hook 安装完成后写入，设置页跨进程读取查看/导出） */
    public static final String KEY_RUNTIME_STATUS = "runtime_status";

    /** 框架服务实例（volatile 保证跨线程可见） */
    private static volatile XposedService sService;

    private static volatile App sApp;

    /** 服务绑定监听（设置页等 UI 用于刷新开关状态） */
    private static final List<OnServiceBoundListener> sBoundListeners = new ArrayList<>();

    /** 服务绑定回调接口（回调在主线程执行） */
    public interface OnServiceBoundListener {
        void onServiceBound();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sApp = this;
        LogRecorder.setContext(this);
        Checkpoint.mark("模块进程启动 (pid=%d)", android.os.Process.myPid());
        Logs.i(TAG, "App.onCreate: pid=" + android.os.Process.myPid());
        XposedServiceHelper.registerListener(this);
        Logs.i(TAG, "已注册 XposedService 监听器");
    }

    @Override
    public void onServiceBind(XposedService service) {
        sService = service;
        Checkpoint.mark("XposedService 已绑定: %s", describe(service));
        LogRecorder.setEnabled(readBoolean(KEY_LOG, false));
        LogRecorder.recordEvent("XposedService 已绑定: " + describe(service));
        SharedPreferences pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
        Logs.i(TAG, "XposedService 已绑定: service=" + describe(service)
                + ", pendingCount=" + pending.getAll().size());
        PreferenceReceiver.tryFlush(this, pending);
        notifyServiceBound();
    }

    @Override
    public void onServiceDied(XposedService service) {
        Checkpoint.mark("XposedService 断开: %s", describe(service));
        Logs.w(TAG, "XposedService 已断开: service=" + describe(service)
                + ", current=" + describe(sService));
        sService = null;
    }

    /** 获取跨进程开关存储；框架服务未连接时返回 null */
    public static SharedPreferences getPrefs() {
        XposedService service = sService;
        if (service == null) {
            Logs.w(TAG, "获取 RemotePreferences 失败: XposedService 未绑定");
            return null;
        }
        try {
            SharedPreferences prefs = service.getRemotePreferences(PREFS_GROUP);
            if (prefs == null) {
                Logs.e(TAG, "获取 RemotePreferences 失败: service 返回 null, group=" + PREFS_GROUP);
            } else {
                Logs.i(TAG, "获取 RemotePreferences 成功: group=" + PREFS_GROUP);
            }
            return prefs;
        } catch (Throwable t) {
            Logs.e(TAG, "获取 RemotePreferences 异常: group=" + PREFS_GROUP, t);
            return null;
        }
    }

    /**
     * 读开关：优先待提交缓存（用户在服务未连接时刚切的值），其次 RemotePreferences，最后默认值。
     * 保证 UI 显示与「最终会生效」的值一致。
     */
    public static boolean readBoolean(String key, boolean defaultValue) {
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            if (pending.contains(key)) {
                return pending.getBoolean(key, defaultValue);
            }
        }
        SharedPreferences remote = getPrefs();
        return remote != null ? remote.getBoolean(key, defaultValue) : defaultValue;
    }

    /**
     * 读字符串：优先待提交缓存，其次 RemotePreferences，最后默认值。
     */
    public static String readString(String key, String defaultValue) {
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            if (pending.contains(key)) {
                return pending.getString(key, defaultValue);
            }
        }
        SharedPreferences remote = getPrefs();
        return remote != null ? remote.getString(key, defaultValue) : defaultValue;
    }

    /**
     * 写字符串：框架服务可用直接写 RemotePreferences；否则写入待提交缓存（同步落盘），
     * 服务绑定后由 {@link PreferenceReceiver#tryFlush} 自动补交。
     */
    public static void writeString(String key, String value) {
        App app = sApp;
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            remote.edit().putString(key, value).apply();
            LogRecorder.recordEvent("字符串已写入 RemotePreferences: key=" + key);
            return;
        }
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit().putString(key, value).commit();
            LogRecorder.recordEvent("字符串写入待提交缓存: key=" + key);
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    /**
     * 写开关：框架服务可用直接写 RemotePreferences；否则写入待提交缓存（同步落盘），
     * 服务绑定后由 {@link PreferenceReceiver#tryFlush} 自动补交，任何情况下都不丢设置。
     */
    public static void writeBoolean(String key, boolean value) {
        App app = sApp;
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            remote.edit().putBoolean(key, value).apply();
            LogRecorder.recordEvent("开关已写入 RemotePreferences: key=" + key + ", value=" + value);
            return;
        }
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit().putBoolean(key, value).commit();
            LogRecorder.recordEvent("服务未连接，开关写入待提交缓存: key=" + key + ", value=" + value);
            // 服务可能正在连接中，主动尝试补交一次
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    /** 注册服务绑定监听（回调在主线程执行） */
    public static void addOnServiceBoundListener(OnServiceBoundListener listener) {
        synchronized (sBoundListeners) {
            if (!sBoundListeners.contains(listener)) {
                sBoundListeners.add(listener);
            }
        }
    }

    private static void notifyServiceBound() {
        final List<OnServiceBoundListener> snapshot;
        synchronized (sBoundListeners) {
            snapshot = new ArrayList<>(sBoundListeners);
        }
        if (snapshot.isEmpty()) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (OnServiceBoundListener listener : snapshot) {
                    try {
                        listener.onServiceBound();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }

    private static String describe(XposedService service) {
        return service == null ? "null"
                : service.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(service));
    }

    /** 获取框架服务实例（未连接时为 null） */
    public static XposedService getService() {
        return sService;
    }

    /** 获取 App 实例（Application 创建后可用） */
    public static Context getAppContext() {
        return sApp;
    }
}
