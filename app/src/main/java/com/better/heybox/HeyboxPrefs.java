package com.better.heybox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings stored directly inside the injected Heybox process.
 *
 * <p>Each write also stores a timestamp. {@link MainModule} compares this local
 * timestamp with the RemotePreferences timestamp written by the standalone
 * manager, so whichever UI changed a setting most recently wins. Existing
 * installations without timestamps keep the old behaviour (Heybox-local value
 * wins on a tie).</p>
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

    public static String getString(String key, String defaultValue) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getString(key, defaultValue) : defaultValue;
    }

    public static long getTimestamp(String key) {
        SharedPreferences prefs = get();
        return prefs != null ? prefs.getLong(App.timestampKey(key), 0L) : 0L;
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
