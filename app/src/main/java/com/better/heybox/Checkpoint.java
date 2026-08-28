package com.better.heybox;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 运行时检查点（仅 Debug 构建生效，见 {@link BuildFlags#DEBUG}）。
 *
 * <p>「Debug 版检查运行情况」的核心设施：在模块生命周期与 Hook 安装等关键节点打点，
 * 记录相对启动耗时（elapsedRealtime）、pid、线程名，同时输出到 logcat（tag=BHX-CKPT）
 * 与文件日志（{@link LogRecorder}，开关开启时），并保留最近 {@link #MAX} 条内存快照，
 * 供设置页「运行状态」弹窗查看、随「导出日志」一并导出。</p>
 *
 * <p>小黑盒进程（Hook 侧）的检查点会在安装完成后写入 RemotePreferences
 * （见 {@link MainModule#stashRuntimeStatus()}），模块设置页跨进程读取，
 * 从而在设置页直接看到小黑盒进程内 Hook 到底装到哪一步、各模块耗时多少。</p>
 *
 * <p>Release 构建下所有方法均为空操作 / 返回提示文本，不产生任何日志噪音。</p>
 */
public final class Checkpoint {

    private static final String TAG = "BHX-CKPT";
    private static final int MAX = 256;

    private static final Object LOCK = new Object();
    private static final List<String> sEntries = new ArrayList<>();
    private static volatile long sStart = -1;

    private Checkpoint() {
    }

    /** 打点：记录一条检查点（Debug 构建生效，Release 为空操作） */
    public static void mark(String msg) {
        mark("%s", msg);
    }

    /** 打点：格式化消息（Debug 构建生效，Release 为空操作） */
    public static void mark(String fmt, Object... args) {
        if (!BuildFlags.DEBUG) {
            return;
        }
        String msg = args == null || args.length == 0 ? fmt : String.format(fmt, args);
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (sStart < 0) {
                sStart = now;
                sEntries.add(header());
            }
            sEntries.add(String.format(Locale.US, "[%8dms][pid=%d][%s] %s",
                    now - sStart, android.os.Process.myPid(), Thread.currentThread().getName(), msg));
            while (sEntries.size() > MAX) {
                sEntries.remove(0);
            }
        }
        Log.i(TAG, msg);
        LogRecorder.recordEvent("检查点: " + msg);
    }

    /** 导出全部检查点文本（Release 构建返回提示） */
    public static String dump() {
        return dump(Integer.MAX_VALUE);
    }

    /** 导出最近 maxLines 条检查点文本（Release 构建返回提示） */
    public static String dump(int maxLines) {
        if (!BuildFlags.DEBUG) {
            return "（Release 构建未启用检查点）";
        }
        synchronized (LOCK) {
            if (sEntries.isEmpty()) {
                return "（暂无检查点）";
            }
            if (sEntries.size() <= maxLines) {
                return String.join("\n", sEntries);
            }
            return String.join("\n", sEntries.subList(sEntries.size() - maxLines, sEntries.size()));
        }
    }

    private static String header() {
        return String.format(Locale.US,
                "===== BetterHeybox 运行检查点 =====\n构建=%s, SDK=%d, 设备=%s %s, 进程=%s",
                BuildFlags.DEBUG ? "debug" : "release",
                Build.VERSION.SDK_INT,
                Build.MANUFACTURER, Build.MODEL,
                processName());
    }

    private static String processName() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object name = activityThread.getMethod("currentProcessName").invoke(null);
            if (name != null) {
                return name.toString();
            }
        } catch (Throwable ignored) {
        }
        return String.valueOf(android.os.Process.myPid());
    }
}
