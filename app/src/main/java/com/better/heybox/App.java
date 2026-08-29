package com.better.heybox;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.service.HookedProcess;
import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.IXposedService;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;
import top.nkbe.npatch.remote.NPatchRemoteClient;

/**
 * BetterHeybox module/settings application.
 *
 * <p>Settings normally use the standard libxposed {@link XposedService}. In
 * NPatch Local mode that callback may not be delivered to the standalone module
 * process, so a compatible NPatch Remote API connection is used as a fallback.
 * Both backends point at the same API-102 service/RemotePreferences contract.</p>
 */
public class App extends Application implements XposedServiceHelper.OnServiceListener {

    private static final String TAG = "BetterHeybox";

    public static final String PREFS_GROUP = "betterheybox";
    public static final String PENDING_PREFS = "betterheybox_pending";
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
        PreferenceReceiver.tryFlush(this, pending);
        notifyServiceBound();
    }

    @Override
    public void onServiceDied(XposedService service) {
        Checkpoint.mark("XposedService 断开: %s", describe(service));
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
                if (prefs != null) return prefs;
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
        if (app == null) return;
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
        if (!NPatchRemoteClient.isAvailable(app)) return;

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

    /**
     * Query API 102 for the actual target process currently hooked by this module.
     * Call from a background thread because this performs Binder IPC.
     */
    public static HookRuntimeStatus inspectHookRuntime(String targetPackage) {
        XposedService standard = sService;
        if (standard != null) {
            try {
                int api = standard.getApiVersion();
                String framework = standard.getFrameworkName() + " " + standard.getFrameworkVersion();
                if (api < XposedService.API_102) {
                    return HookRuntimeStatus.connected("libxposed", framework, api,
                            "服务 API 低于 102，无法查询运行目标");
                }
                List<HookedTarget> targets = standard.getRunningTargets();
                for (HookedTarget target : targets) {
                    if (matchesTarget(targetPackage, target.getProcessName())) {
                        return HookRuntimeStatus.hooked(
                                "libxposed", framework, api,
                                target.getProcessName(), target.getPid(), target.getState().name());
                    }
                }
                return HookRuntimeStatus.connected("libxposed", framework, api,
                        "目标未运行，或当前进程未被模块 Hook");
            } catch (Throwable t) {
                return HookRuntimeStatus.error("libxposed", t);
            }
        }

        NPatchRemoteClient npatch = sNPatchClient;
        if (npatch != null) {
            try {
                IXposedService raw = npatch.getService();
                int api = raw.getApiVersion();
                String framework = raw.getFrameworkName() + " " + raw.getFrameworkVersion();
                if (api < IXposedService.API_102) {
                    return HookRuntimeStatus.connected("NPatch Remote", framework, api,
                            "服务 API 低于 102，无法查询运行目标");
                }
                List<HookedProcess> targets = raw.getRunningTargets();
                if (targets != null) {
                    for (HookedProcess target : targets) {
                        if (target != null && matchesTarget(targetPackage, target.processName)) {
                            return HookRuntimeStatus.hooked(
                                    "NPatch Remote", framework, api,
                                    target.processName, target.pid, rawStateLabel(target.state));
                        }
                    }
                }
                return HookRuntimeStatus.connected("NPatch Remote", framework, api,
                        "目标未运行，或当前进程未被模块 Hook");
            } catch (Throwable t) {
                return HookRuntimeStatus.error("NPatch Remote", t);
            }
        }

        return HookRuntimeStatus.disconnected();
    }

    private static boolean matchesTarget(String packageName, String processName) {
        if (packageName == null || processName == null) return false;
        return processName.equals(packageName) || processName.startsWith(packageName + ":");
    }

    private static String rawStateLabel(int state) {
        switch (state) {
            case HookedProcess.TARGET_STATE_UP_TO_DATE:
                return "UP_TO_DATE";
            case HookedProcess.TARGET_STATE_STALE:
                return "STALE";
            case HookedProcess.TARGET_STATE_RELOADING:
                return "RELOADING";
            case HookedProcess.TARGET_STATE_FAILED:
                return "FAILED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    public static final class HookRuntimeStatus {
        public final boolean serviceConnected;
        public final boolean hooked;
        public final String backend;
        public final String framework;
        public final int apiVersion;
        public final String processName;
        public final int pid;
        public final String state;
        public final String detail;

        private HookRuntimeStatus(boolean serviceConnected, boolean hooked, String backend,
                                  String framework, int apiVersion, String processName,
                                  int pid, String state, String detail) {
            this.serviceConnected = serviceConnected;
            this.hooked = hooked;
            this.backend = backend;
            this.framework = framework;
            this.apiVersion = apiVersion;
            this.processName = processName;
            this.pid = pid;
            this.state = state;
            this.detail = detail;
        }

        static HookRuntimeStatus hooked(String backend, String framework, int api,
                                        String process, int pid, String state) {
            return new HookRuntimeStatus(true, true, backend, framework, api,
                    process, pid, state, null);
        }

        static HookRuntimeStatus connected(String backend, String framework, int api, String detail) {
            return new HookRuntimeStatus(true, false, backend, framework, api,
                    null, -1, null, detail);
        }

        static HookRuntimeStatus disconnected() {
            return new HookRuntimeStatus(false, false, "未连接", null, -1,
                    null, -1, null, "未连接 libxposed / NPatch Remote");
        }

        static HookRuntimeStatus error(String backend, Throwable error) {
            String detail = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            return new HookRuntimeStatus(true, false, backend, null, -1,
                    null, -1, null, detail);
        }
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
        if (sService != null) return "libxposed";
        if (sNPatchClient != null) return "NPatch Remote";
        if (sNPatchConnecting) return "NPatch 连接中";
        return "未连接";
    }

    public static void addOnServiceBoundListener(OnServiceBoundListener listener) {
        synchronized (sBoundListeners) {
            if (!sBoundListeners.contains(listener)) sBoundListeners.add(listener);
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
        if (snapshot.isEmpty()) return;
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
