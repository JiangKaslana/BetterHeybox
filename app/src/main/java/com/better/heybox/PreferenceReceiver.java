package com.better.heybox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.service.XposedService;

/**
 * 接收小黑盒进程（内嵌设置面板）发来的开关写请求，并在模块进程中写入 RemotePreferences。
 *
 * <p>修复「模块进程未运行时切换开关无效」：</p>
 * <ul>
 *   <li>发送方（{@link MainModule#writeEmbeddedBoolean}）给广播加
 *       {@link Intent#FLAG_INCLUDE_STOPPED_PACKAGES}，保证模块 App 处于刚安装/停止态时
 *       广播仍能唤醒其进程；</li>
 *   <li>本接收器用 {@link #goAsync()} 挂起广播，在后台线程等待框架服务绑定（冷启动时
 *       服务绑定是异步的，最多等 6 秒），绑定后立即补交，避免「服务还没绑上进程就被回收」丢设置；</li>
 *   <li>待提交缓存改用 {@code commit()} 同步落盘，进程被杀也不丢，下次服务绑定自动补交。</li>
 * </ul>
 */
public class PreferenceReceiver extends BroadcastReceiver {

    public static final String ACTION_SET_BOOLEAN = "com.better.heybox.SET_BOOLEAN";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_VALUE = "value";
    private static final String PENDING_PREFS = App.PENDING_PREFS;
    private static final long WAIT_SERVICE_BIND_MS = 6000;

    /** 可写开关 key 白名单（新增开关时同步在 App 中登记） */
    private static final Set<String> ALLOWED_KEYS = new HashSet<>(Arrays.asList(
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
            App.KEY_BLOCK_UPDATE,
            App.KEY_SYSTEM_SHARE,
            App.KEY_LOG
    ));

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

        // 挂起广播：onReceive 先返回，进程由 goAsync 保活，后台线程完成「落盘 + 等待服务绑定 + 补交」
        final PendingResult result = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences pending = context.getSharedPreferences(PENDING_PREFS,
                            Context.MODE_PRIVATE);
                    pending.edit().putBoolean(key, value).commit();
                    Log.i("BetterHeybox", "广播已写入待提交缓存: key=" + key + ", value=" + value
                            + ", pendingCount=" + pending.getAll().size());
                    LogRecorder.recordEvent("开关变更已写入待提交缓存: key=" + key + ", value=" + value);

                    // 冷启动时框架服务绑定是异步的：等待绑定后立即补交，确保设置不丢
                    XposedService service = App.getService();
                    long deadline = System.currentTimeMillis() + WAIT_SERVICE_BIND_MS;
                    while (service == null && System.currentTimeMillis() < deadline) {
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                        service = App.getService();
                    }
                    if (service == null) {
                        Log.w("BetterHeybox", "等待框架服务绑定超时，保留待提交缓存: key=" + key
                                + "（服务绑定后会自动补交）");
                    }
                    PreferenceReceiver.tryFlush(context, pending);
                } finally {
                    result.finish();
                }
            }
        }, "bhx-pref-flush").start();
    }

    public static void tryFlush(Context context, SharedPreferences pending) {
        if (pending == null) {
            Log.e("BetterHeybox", "远程提交跳过: pending=null");
            return;
        }
        Map<String, ?> values = pending.getAll();
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
            boolean committed = remoteEditor.commit();
            Log.i("BetterHeybox", "远程提交 commit 已返回: success=" + committed
                    + ", acceptedCount=" + acceptedCount);
            LogRecorder.recordEvent("远程提交完成: success=" + committed + ", count=" + acceptedCount);
            if (committed) {
                pending.edit().clear().commit();
                Log.i("BetterHeybox", "待提交缓存已清理: pendingCount=" + pending.getAll().size());
            } else {
                Log.w("BetterHeybox", "远程提交未成功，保留待提交缓存");
            }
        } catch (Throwable t) {
            Log.e("BetterHeybox", "远程提交异常，保留待提交缓存", t);
        }
    }

    private static boolean isAllowedKey(String key) {
        return key != null && ALLOWED_KEYS.contains(key);
    }
}
