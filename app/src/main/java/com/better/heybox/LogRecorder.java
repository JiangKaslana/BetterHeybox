package com.better.heybox;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 模块文件日志记录器。
 *
 * <p>受「记录日志」开关（{@link App#KEY_LOG}，默认关闭）控制：开启后，模块在运行中产生的
 * 日志会自动写入文件，便于不接 logcat 时离线排查（例如复现「开关不生效」等问题）。</p>
 *
 * <p>写入位置：{@code <filesDir>/betterheybox/log.txt}</p>
 * <ul>
 *   <li>小黑盒进程（Hook 侧）→ 小黑盒的 files 目录（模块以小黑盒 uid 运行，可直接写）</li>
 *   <li>模块进程（设置页/广播接收）→ 模块自己的 files 目录</li>
 * </ul>
 *
 * <p>滚动：单文件超过 {@link #MAX_BYTES} 时重命名为 log.1.txt（覆盖旧备份），
 * 始终保持最多 2 个文件。所有写入都在 {@link #LOCK} 上同步，线程安全。</p>
 *
 * <p>上下文获取：优先使用调用方显式传入的 {@link #setContext(Context)}，
 * 兜底反射 {@code ActivityThread.currentApplication()}；拿不到 Context 时跳过文件写入
 * （logcat 输出不受影响）。</p>
 */
public final class LogRecorder {

    private static final String TAG = "BetterHeybox";
    private static final String DIR_NAME = "betterheybox";
    private static final String FILE_NAME = "log.txt";
    private static final String BACKUP_NAME = "log.1.txt";
    private static final long MAX_BYTES = 512 * 1024;

    private static volatile Context sContext;
    private static volatile boolean sEnabled;
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private LogRecorder() {
    }

    public static void setContext(Context context) {
        if (context != null && sContext == null) {
            sContext = context.getApplicationContext();
        }
    }

    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    public static boolean isEnabled() {
        return sEnabled;
    }

    public static void record(int level, String tag, String msg) {
        if (!sEnabled || msg == null) {
            return;
        }
        recordLocked(level, tag, msg, null);
    }

    public static void record(int level, String tag, String msg, Throwable tr) {
        if (!sEnabled) {
            return;
        }
        recordLocked(level, tag, msg, tr);
    }

    public static void recordEvent(String msg) {
        record(Log.INFO, TAG, msg);
    }

    public static String getLogFilePath() {
        Context ctx = sContext != null ? sContext : resolveApplicationContext();
        if (ctx == null) {
            return null;
        }
        return new File(new File(ctx.getFilesDir(), DIR_NAME), FILE_NAME).getAbsolutePath();
    }

    private static void recordLocked(int level, String tag, String msg, Throwable tr) {
        synchronized (LOCK) {
            try {
                Context ctx = sContext;
                if (ctx == null) {
                    ctx = resolveApplicationContext();
                    if (ctx == null) {
                        return;
                    }
                    sContext = ctx;
                }
                File dir = new File(ctx.getFilesDir(), DIR_NAME);
                if (!dir.exists() && !dir.mkdirs()) {
                    return;
                }
                File file = new File(dir, FILE_NAME);
                if (file.length() > MAX_BYTES) {
                    File backup = new File(dir, BACKUP_NAME);
                    //noinspection ResultOfMethodCallIgnored
                    backup.delete();
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(backup);
                }
                String line = formatLine(level, tag, msg, tr);
                try (FileOutputStream fos = new FileOutputStream(file, true);
                     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    writer.write(line);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String formatLine(int level, String tag, String msg, Throwable tr) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(TIME_FORMAT.format(new Date()));
        sb.append(' ').append(levelChar(level));
        sb.append('/').append(tag == null ? TAG : tag);
        sb.append(" [pid=").append(android.os.Process.myPid()).append("] ");
        sb.append(msg).append('\n');
        if (tr != null) {
            StringWriter sw = new StringWriter();
            tr.printStackTrace(new PrintWriter(sw));
            sb.append(sw.toString());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static char levelChar(int level) {
        switch (level) {
            case Log.VERBOSE:
                return 'V';
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            case Log.ASSERT:
                return 'A';
            default:
                return '?';
        }
    }

    private static Context resolveApplicationContext() {
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
