package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;

import com.better.heybox.liquidglass.LiquidGlassHookBridge;

/**
 * Settings stored directly inside the injected Heybox process.
 *
 * <p>Each local write stores a timestamp. Reads also consult the module's
 * RemotePreferences when the libxposed entry is available, so upstream code
 * that reads HeyboxPrefs directly (for example the liquid-glass renderer) gets
 * the same newest-write-wins semantics as {@link MainModule}. On equal/legacy
 * timestamps the Heybox-local value keeps historical precedence.</p>
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
        SharedPreferences local = get();
        boolean localExists = local != null && local.contains(key);
        boolean localValue = localExists ? local.getBoolean(key, defaultValue) : defaultValue;
        long localTs = localExists ? local.getLong(App.timestampKey(key), 0L) : -1L;

        try {
            SharedPreferences remote = getRemotePreferences();
            boolean remoteExists = remote != null && remote.contains(key);
            if (!remoteExists) {
                return localExists ? localValue : defaultValue;
            }
            boolean remoteValue = remote.getBoolean(key, defaultValue);
            long remoteTs = remote.getLong(App.timestampKey(key), 0L);
            if (!localExists) {
                return remoteValue;
            }
            return remoteTs > localTs ? remoteValue : localValue;
        } catch (Throwable ignored) {
            return localExists ? localValue : defaultValue;
        }
    }

    public static String getString(String key, String defaultValue) {
        SharedPreferences local = get();
        boolean localExists = local != null && local.contains(key);
        String localValue = localExists ? local.getString(key, defaultValue) : defaultValue;
        long localTs = localExists ? local.getLong(App.timestampKey(key), 0L) : -1L;

        try {
            SharedPreferences remote = getRemotePreferences();
            boolean remoteExists = remote != null && remote.contains(key);
            if (!remoteExists) {
                return localExists ? localValue : defaultValue;
            }
            String remoteValue = remote.getString(key, defaultValue);
            long remoteTs = remote.getLong(App.timestampKey(key), 0L);
            if (!localExists) {
                return remoteValue;
            }
            return remoteTs > localTs ? remoteValue : localValue;
        } catch (Throwable ignored) {
            return localExists ? localValue : defaultValue;
        }
    }

    /** Local timestamp only. MainModule compares it against its remote snapshot. */
    public static long getTimestamp(String key) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getLong(App.timestampKey(key), 0L) : 0L;
    }

    /** Local-presence check kept intentionally local for legacy precedence logic. */
    public static boolean contains(String key) {
        SharedPreferences prefs = get();
        return prefs != null && prefs.contains(key);
    }

    public static boolean setBoolean(String key, boolean value) {
        SharedPreferences prefs = get();
        if (prefs == null) {
            return false;
        }
        return prefs.edit()
                .putBoolean(key, value)
                .putLong(App.timestampKey(key), System.currentTimeMillis())
                .commit();
    }

    public static boolean setString(String key, String value) {
        SharedPreferences prefs = get();
        if (prefs == null) {
            return false;
        }
        return prefs.edit()
                .putString(key, value)
                .putLong(App.timestampKey(key), System.currentTimeMillis())
                .commit();
    }

    /**
     * Access the framework RemotePreferences through the already-attached module
     * entry. This avoids depending on the standalone App process, which is not
     * present inside the injected Heybox process.
     */
    private static SharedPreferences getRemotePreferences() {
        try {
            MainModule module = LiquidGlassHookBridge.module();
            return module != null ? module.getRemotePreferences(App.PREFS_GROUP) : null;
        } catch (Throwable ignored) {
            return null;
        }
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
