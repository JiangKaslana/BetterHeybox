package com.better.heybox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** 接收宿主进程的设置变更请求，在模块进程中写入可写的 RemotePreferences。 */
public class PreferenceReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_BOOLEAN = "com.better.heybox.SET_BOOLEAN";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    private static final String PENDING_PREFS = "betterheybox_pending";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_SET_BOOLEAN.equals(intent.getAction())) {
            return;
        }
        String key = intent.getStringExtra(EXTRA_KEY);
        if (!isAllowedKey(key)) {
            return;
        }
        boolean value = intent.getBooleanExtra(EXTRA_VALUE, false);
        SharedPreferences pending = context.getSharedPreferences(PENDING_PREFS,
                Context.MODE_PRIVATE);
        pending.edit().putBoolean(key, value).apply();
        tryFlush(context, pending);
    }

    public static void tryFlush(Context context, SharedPreferences pending) {
        try {
            SharedPreferences remote = App.getPrefs();
            if (remote == null) {
                return;
            }
            SharedPreferences.Editor remoteEditor = remote.edit();
            for (String key : pending.getAll().keySet()) {
                Object value = pending.getAll().get(key);
                if (value instanceof Boolean && isAllowedKey(key)) {
                    remoteEditor.putBoolean(key, (Boolean) value);
                }
            }
            remoteEditor.apply();
            pending.edit().clear().apply();
        } catch (Throwable t) {
            Log.e("BetterHeybox", "写入远程设置失败", t);
        }
    }

    private static boolean isAllowedKey(String key) {
        return App.KEY_OPEN_SCREEN.equals(key)
                || App.KEY_FEED_AD.equals(key)
                || App.KEY_BUBBLE_AD.equals(key)
                || App.KEY_CORNER_AD.equals(key)
                || App.KEY_PROMOTE_AD.equals(key)
                || App.KEY_HIDE_TAB_HOME.equals(key)
                || App.KEY_HIDE_TAB_HOT.equals(key)
                || App.KEY_HIDE_TAB_GAME.equals(key)
                || App.KEY_HIDE_ADD.equals(key)
                || App.KEY_COPY_POST.equals(key)
                || App.KEY_BLOCK_UPDATE.equals(key);
    }
}
