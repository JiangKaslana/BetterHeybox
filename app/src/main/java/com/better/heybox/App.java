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
import top.nkbe.npatch.remote.NPatchRemoteClient;

/**
 * BetterHeybox module/settings application.
 *
 * <p>Settings normally use the standard libxposed {@link XposedService}. In
 * NPatch Local mode that callback may not be delivered to the standalone module
 * process, so a compatible NPatch Remote API connection is used as a fallback.
 * Both backends point at the same API-102 RemotePreferences contract.</p>
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterHeybox";

    /** RemotePreferences group shared by LSPosed/NPatch and the injected target. */
    public static final String PREFS_GROUP = "betterheybox";

    /** Local queue used until either libxposed or NPatch Remote is available. */
    public static final String PENDING_PREFS = "betterheybox_pending";

    /** Per-key write timestamp used to reconcile manager settings with Heybox-local settings. */
    public static final String META_TIMESTAMP_PREFIX = "__bhx_ts__:";

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
    public static final String KEY_CUSTOM_TEXT_SELECT = "custom_text_select";
    public static final String KEY_BLOCK_UPDATE = "block_update";
    public static final String KEY_SYSTEM_SHARE = "system_share";
    public static final String KEY_DAILY_TASK_ENABLED = "daily_task_enabled";
    public static final String KEY_DAILY_TASK_PICTURE = "daily_task_picture";
    public static final String KEY_DAILY_TASK_NORMAL = "daily_task_normal";
    public static final String KEY_DAILY_TASK_CHANNEL = "daily_task_channel";
    public static final String KEY_DAILY_TASK_DONE_DATE = "daily_task_done_date";
    public static final String KEY_DAILY_TASK_RESET = "daily_task_reset";
    public static final String KEY_SHARE_CHANNEL = "daily_task_channel_type";
    public static final String KEY_FAKE_NOTIFICATION = "fake_notification";
    public static final String KEY_VIDEO_DOWNLOAD = "video_download";
    public static final String KEY_VIDEO_DIR = "video_download_dir";
    public static final String KEY_VIDEO_TO_MP4 = "video_download_to_mp4";
    public static final String KEY_PURIFY_SHARE_LINK = "purify_share_link";
    public static final String KEY_LOG = "log";
    public static final String KEY_RUNTIME_STATUS = "runtime_status";

    /** Standard framework service. Takes precedence when available. */
    private static volatile XposedService sService;

    /** NPatch Local fallback used only by the standalone settings process. */
    private static volatile NPatchRemoteClient sNPatchClient;
    private static volatile boolean sNPatchConnecting;
    private static volatile String sNPatchError;

    private static volatile App sApp;

    private static final List<OnServiceBoundListener> sBoundListeners = new ArrayList<>();

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
        tryConnectNPatchRemote();
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
        tryConnectNPatchRemote();
        notifyServiceBound();
    }

    /** Get settings from standard libxposed first, then NPatch Local fallback. */
    public static SharedPreferences getPrefs() {
        XposedService service = sService;
        if (service != null) {
            try {
                SharedPreferences prefs = service.getRemotePreferences(PREFS_GROUP);
                if (prefs != null) {
                    return prefs;
                }
            } catch (Throwable t) {
                Logs.e(TAG, "libxposed RemotePreferences 获取异常", t);
            }
        }

        NPatchRemoteClient npatch = sNPatchClient;
        if (npatch != null) {
            try {
                return npatch.getRemotePreferences(PREFS_GROUP);
            } catch (Throwable t) {
                sNPatchError = t.getClass().getSimpleName() + ": " + t.getMessage();
                Logs.e(TAG, "NPatch RemotePreferences 获取异常", t);
            }
        }

        tryConnectNPatchRemote();
        return null;
    }

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

    /** Manager-side write. Timestamp lets the injected process pick the newest source. */
    public static void writeBoolean(String key, boolean value) {
        long timestamp = System.currentTimeMillis();
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            try {
                remote.edit()
                        .putBoolean(key, value)
                        .putLong(timestampKey(key), timestamp)
                        .apply();
                LogRecorder.recordEvent("开关已写入 RemotePreferences: key=" + key + ", value=" + value);
                return;
            } catch (Throwable t) {
                Logs.e(TAG, "RemotePreferences 写入失败，转入待提交缓存: " + key, t);
            }
        }
        queueBoolean(key, value, timestamp);
    }

    public static void writeString(String key, String value) {
        long timestamp = System.currentTimeMillis();
        SharedPreferences remote = getPrefs();
        if (remote != null) {
            try {
                remote.edit()
                        .putString(key, value)
                        .putLong(timestampKey(key), timestamp)
                        .apply();
                LogRecorder.recordEvent("字符串已写入 RemotePreferences: key=" + key);
                return;
            } catch (Throwable t) {
                Logs.e(TAG, "RemotePreferences 字符串写入失败，转入待提交缓存: " + key, t);
            }
        }
        App app = sApp;
        if (app != null) {
            SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
            pending.edit()
                    .putString(key, value)
                    .putLong(timestampKey(key), timestamp)
                    .commit();
            tryConnectNPatchRemote();
            PreferenceReceiver.tryFlush(app, pending);
        }
    }

    private static void queueBoolean(String key, boolean value, long timestamp) {
        App app = sApp;
        if (app == null) {
            return;
        }
        SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
        pending.edit()
                .putBoolean(key, value)
                .putLong(timestampKey(key), timestamp)
                .commit();
        LogRecorder.recordEvent("服务未连接，开关写入待提交缓存: key=" + key + ", value=" + value);
        tryConnectNPatchRemote();
        PreferenceReceiver.tryFlush(app, pending);
    }

    public static String timestampKey(String key) {
        return META_TIMESTAMP_PREFIX + key;
    }

    public static boolean isTimestampMetadataKey(String key) {
        return key != null && key.startsWith(META_TIMESTAMP_PREFIX);
    }

    public static String baseKeyFromTimestamp(String key) {
        return isTimestampMetadataKey(key)
                ? key.substring(META_TIMESTAMP_PREFIX.length())
                : null;
    }

    /** Start NPatch Local fallback connection without blocking the UI thread. */
    public static void tryConnectNPatchRemote() {
        App app = sApp;
        if (app == null || sService != null || sNPatchClient != null || sNPatchConnecting) {
            return;
        }
        try {
            if (!NPatchRemoteClient.isAvailable(app)) {
                return;
            }
        } catch (Throwable t) {
            return;
        }

        sNPatchConnecting = true;
        sNPatchError = null;
        NPatchRemoteClient.connectAsync(app)
                .thenAccept(client -> {
                    sNPatchClient = client;
                    sNPatchConnecting = false;
                    sNPatchError = null;
                    Logs.i(TAG, "NPatch Remote API 已连接");
                    try {
                        SharedPreferences pending = app.getSharedPreferences(PENDING_PREFS, MODE_PRIVATE);
                        PreferenceReceiver.tryFlush(app, pending);
                        LogRecorder.setEnabled(readBoolean(KEY_LOG, false));
                    } catch (Throwable t) {
                        Logs.e(TAG, "NPatch Remote 连接后刷新失败", t);
                    }
                    notifyServiceBound();
                })
                .exceptionally(error -> {
                    sNPatchConnecting = false;
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    sNPatchError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    Logs.w(TAG, "NPatch Remote API 连接失败: " + sNPatchError);
                    notifyServiceBound();
                    return null;
                });
    }

    public static boolean hasPreferencesBackend() {
        return sService != null || sNPatchClient != null;
    }

    public static boolean isNPatchRemoteConnected() {
        return sNPatchClient != null;
    }

    public static boolean isNPatchConnecting() {
        return sNPatchConnecting;
    }

    public static String getNPatchError() {
        return sNPatchError;
    }

    public static String getPreferencesBackendLabel() {
        if (sService != null) {
            return "libxposed";
        }
        if (sNPatchClient != null) {
            return "NPatch Remote";
        }
        if (sNPatchConnecting) {
            return "NPatch 连接中";
        }
        return "未连接";
    }

    public static void addOnServiceBoundListener(OnServiceBoundListener listener) {
        synchronized (sBoundListeners) {
            if (!sBoundListeners.contains(listener)) {
                sBoundListeners.add(listener);
            }
        }
    }

    public static void removeOnServiceBoundListener(OnServiceBoundListener listener) {
        synchronized (sBoundListeners) {
            sBoundListeners.remove(listener);
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
        new Handler(Looper.getMainLooper()).post(() -> {
            for (OnServiceBoundListener listener : snapshot) {
                try {
                    listener.onServiceBound();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static String describe(XposedService service) {
        return service == null ? "null"
                : service.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(service));
    }

    public static XposedService getService() {
        return sService;
    }

    public static Context getAppContext() {
        return sApp;
    }
}
