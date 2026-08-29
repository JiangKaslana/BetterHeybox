package com.better.heybox;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Privileged process operations shared by the standalone manager. */
public final class PrivilegedOps {

    private PrivilegedOps() {
    }

    /**
     * Stop a package using the strongest available backend.
     * Order: Shizuku/Shizuku+ -> root su -> legacy Android background kill.
     *
     * <p>Android 14+ deliberately prevents third-party apps from killing another
     * package with {@link ActivityManager#killBackgroundProcesses(String)}, so on
     * those releases we fail clearly instead of pretending that a restart happened.</p>
     */
    public static Result forceStop(Context context, String packageName) {
        String shizukuError = null;
        if (ShizukuBridge.hasPermission()) {
            ShizukuBridge.Result shizuku = ShizukuBridge.forceStop(packageName);
            if (shizuku.success) {
                return new Result(true, "Shizuku", null);
            }
            shizukuError = shizuku.describeFailure();
        }

        String rootError = null;
        if (hasSuBinary()) {
            Result root = forceStopWithRoot(packageName);
            if (root.success) {
                return root;
            }
            rootError = root.error;
        }

        if (Build.VERSION.SDK_INT >= 34) {
            StringBuilder error = new StringBuilder("Android 14+ 需要 Shizuku 授权或 Root 才能可靠结束其他应用");
            if (shizukuError != null && !shizukuError.isEmpty()) {
                error.append("；Shizuku: ").append(shizukuError);
            }
            if (rootError != null && !rootError.isEmpty()) {
                error.append("；Root: ").append(rootError);
            }
            return new Result(false, "无可用高权限后端", error.toString());
        }

        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
                return new Result(true, "Android 13- 兼容结束", null);
            }
        } catch (Throwable t) {
            return new Result(false, "Android 13- 兼容结束",
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return new Result(false, "无", "没有可用的进程控制后端");
    }

    public static boolean hasSuBinary() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/data/adb/magisk", "/data/adb/ksu"
        };
        for (String path : paths) {
            try {
                if (new java.io.File(path).exists()) {
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static Result forceStopWithRoot(String packageName) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "su", "-c", "am force-stop '" + packageName.replace("'", "'\\''") + "'")
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) output.append('\n');
                    output.append(line);
                }
            }
            int code = process.waitFor();
            return new Result(code == 0, "Root", code == 0 ? null : output.toString());
        } catch (Throwable t) {
            return new Result(false, "Root", t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static final class Result {
        public final boolean success;
        public final String backend;
        public final String error;

        Result(boolean success, String backend, String error) {
            this.success = success;
            this.backend = backend;
            this.error = error;
        }
    }
}
