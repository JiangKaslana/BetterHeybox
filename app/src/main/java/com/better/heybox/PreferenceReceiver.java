package com.better.heybox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/** 兼容接收旧版宿主广播，并在模块进程中写入 RemotePreferences。 */
public class PreferenceReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_BOOLEAN = "com.better.heybox.SET_BOOLEAN";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    private static final String PENDING_PREFS = "betterheybox_pending";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.w("BetterHeybox", "广播接收: intent=null");
            return;
        }
        String action = intent.getAction();
        String key = intent.getStringExtra(EXTRA_KEY);
        boolean value = intent.getBooleanExtra(EXTRA_VALUE, false);
        Log.i("BetterHeybox", "广播接收: action=" + action + ", key=" + key
                + ", value=" + value + ", pid=" + android.os.Process.myPid());
        if (!ACTION_SET_BOOLEAN.equals(action)) {
            Log.w("BetterHeybox", "广播忽略: action 不匹配, action=" + action);
            return;
        }
        if (!isAllowedKey(key)) {
            Log.w("BetterHeybox", "广播拒绝: key 不允许, key=" + key);
            return;
        }
        SharedPreferences pending = context.getSharedPreferences(PENDING_PREFS,
                Context.MODE_PRIVATE);
        pending.edit().putBoolean(key, value).apply();
        Log.i("BetterHeybox", "广播已写入待提交缓存: key=" + key + ", value=" + value
                + ", pendingCount=" + pending.getAll().size());
        tryFlush(context, pending);
    }

    public static void tryFlush(Context context, SharedPreferences pending) {
        if (pending == null) {
            Log.e("BetterHeybox", "远程提交跳过: pending=null");
            return;
        }
        java.util.Map<String, ?> values = pending.getAll();
        Log.i("BetterHeybox", "远程提交开始: pendingCount=" + values.size()
                + ", pid=" + android.os.Process.myPid());
        try {
            SharedPreferences remote = App.getPrefs();
            if (remote == null) {
                Log.w("BetterHeybox", "远程提交等待: RemotePreferences 不可用，保留待提交缓存");
                return;
            }
            Log.i("BetterHeybox", "远程偏好已获取，开始构造 Editor: group=" + App.PREFS_GROUP);
            SharedPreferences.Editor remoteEditor = remote.edit();
            if (remoteEditor == null) {
                Log.e("BetterHeybox", "远程提交失败: RemotePreferences.edit 返回 null");
                return;
            }
            int acceptedCount = 0;
            for (String key : values.keySet()) {
                Object value = values.get(key);
                if (value instanceof Boolean && isAllowedKey(key)) {
                    remoteEditor.putBoolean(key, (Boolean) value);
                    acceptedCount++;
                    Log.i("BetterHeybox", "远程提交加入变更: key=" + key + ", value=" + value);
                } else {
                    Log.w("BetterHeybox", "远程提交跳过无效缓存: key=" + key
                            + ", valueType=" + (value == null ? "null" : value.getClass().getName()));
                }
            }
            remoteEditor.apply();
            Log.i("BetterHeybox", "远程提交 apply 已调用: acceptedCount=" + acceptedCount);
            pending.edit().clear().apply();
            Log.i("BetterHeybox", "待提交缓存已清理: pendingCount=" + pending.getAll().size());
        } catch (Throwable t) {
            Log.e("BetterHeybox", "远程提交异常，保留待提交缓存", t);
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
