package com.better.heybox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * Mirrors settings changed in the injected Heybox panel into framework
 * RemotePreferences and also flushes standalone-manager writes queued while no
 * settings backend is connected.
 */
public class PreferenceReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_BOOLEAN = "com.better.heybox.SET_BOOLEAN";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    public static final String EXTRA_TIMESTAMP = "timestamp";
    private static final String PENDING_PREFS = App.PENDING_PREFS;
    private static final long WAIT_SERVICE_BIND_MS = 6000;

    private static final Set<String> ALLOWED_BOOLEAN_KEYS = new HashSet<>(Arrays.asList(
            App.KEY_OPEN_SCREEN,
            App.KEY_FEED_AD,
            App.KEY_BUBBLE_AD,
            App.KEY_CORNER_AD,
            App.KEY_PROMOTE_AD,
            App.KEY_HIDE_TAB_HOME,
            App.KEY_HIDE_TAB_HOT,
            App.KEY_HIDE_TAB_GAME,
            App.KEY_HIDE_ADD,
            App.KEY_COPY_POST,
            App.KEY_CUSTOM_TEXT_SELECT,
            App.KEY_BLOCK_UPDATE,
            App.KEY_SYSTEM_SHARE,
            App.KEY_DAILY_TASK_ENABLED,
            App.KEY_DAILY_TASK_RESET,
            App.KEY_FAKE_NOTIFICATION,
            App.KEY_VIDEO_DOWNLOAD,
            App.KEY_VIDEO_TO_MP4,
            App.KEY_PURIFY_SHARE_LINK,
            App.KEY_LOG
    ));

    private static final Set<String> ALLOWED_STRING_KEYS = new HashSet<>(Arrays.asList(
            App.KEY_DAILY_TASK_PICTURE,
            App.KEY_DAILY_TASK_NORMAL,
            App.KEY_DAILY_TASK_CHANNEL,
            App.KEY_DAILY_TASK_DONE_DATE,
            App.KEY_SHARE_CHANNEL,
            App.KEY_VIDEO_DIR
    ));

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_BOOLEAN.equals(intent.getAction())) {
            return;
        }
        String key = intent.getStringExtra(EXTRA_KEY);
        if (!ALLOWED_BOOLEAN_KEYS.contains(key)) {
            Logs.w("BetterHeybox", "广播拒绝: key 不允许, key=" + key);
            return;
        }
        boolean value = intent.getBooleanExtra(EXTRA_VALUE, false);
        long timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
        if (timestamp <= 0L) {
            timestamp = System.currentTimeMillis();
        }
        final long finalTimestamp = timestamp;

        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                SharedPreferences pending = context.getSharedPreferences(PENDING_PREFS,
                        Context.MODE_PRIVATE);
                pending.edit()
                        .putBoolean(key, value)
                        .putLong(App.timestampKey(key), finalTimestamp)
                        .commit();
                LogRecorder.recordEvent("开关镜像缓存: key=" + key + ", value=" + value);

                // Standard libxposed delivery may arrive slightly after process startup.
                XposedService service = App.getService();
                long deadline = System.currentTimeMillis() + WAIT_SERVICE_BIND_MS;
                while (service == null
                        && !App.hasPreferencesBackend()
                        && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    service = App.getService();
                }
                App.tryConnectNPatchRemote();
                tryFlush(context, pending);
            } finally {
                result.finish();
            }
        }, "bhx-pref-flush").start();
    }

    /** Flush queued values to whichever API-102 settings backend is available. */
    public static void tryFlush(Context context, SharedPreferences pending) {
        if (pending == null) {
            return;
        }
        Map<String, ?> values = pending.getAll();
        if (values.isEmpty()) {
            return;
        }
        try {
            SharedPreferences remote = App.getPrefs();
            if (remote == null) {
                App.tryConnectNPatchRemote();
                return;
            }
            SharedPreferences.Editor editor = remote.edit();
            int accepted = 0;
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                if (App.isTimestampMetadataKey(key)) {
                    String baseKey = App.baseKeyFromTimestamp(key);
                    if (isAllowedDataKey(baseKey) && value instanceof Long) {
                        editor.putLong(key, (Long) value);
                        accepted++;
                    }
                    continue;
                }
                if (ALLOWED_BOOLEAN_KEYS.contains(key) && value instanceof Boolean) {
                    editor.putBoolean(key, (Boolean) value);
                    accepted++;
                } else if (ALLOWED_STRING_KEYS.contains(key) && value instanceof String) {
                    editor.putString(key, (String) value);
                    accepted++;
                }
            }

            if (accepted == 0) {
                return;
            }
            boolean committed = editor.commit();
            LogRecorder.recordEvent("远程提交完成: success=" + committed + ", count=" + accepted);
            if (committed) {
                pending.edit().clear().commit();
            }
        } catch (Throwable t) {
            Logs.e("BetterHeybox", "远程提交异常，保留待提交缓存", t);
        }
    }

    private static boolean isAllowedDataKey(String key) {
        return key != null
                && (ALLOWED_BOOLEAN_KEYS.contains(key) || ALLOWED_STRING_KEYS.contains(key));
    }
}
