package com.better.heybox;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Privileged process operations shared by the standalone manager. */
public final class PrivilegedOps {

    private PrivilegedOps() {
    }

    /**
     * Stop a package using the strongest available backend.
     * Order: Shizuku/Shizuku+ -> root su -> Android's ordinary background kill.
     */
    public static Result forceStop(Context context, String packageName) {
        if (ShizukuBridge.hasPermission()) {
            ShizukuBridge.Result shizuku = ShizukuBridge.forceStop(packageName);
            if (shizuku.success) {
                return new Result(true, "Shizuku", null);
            }
        }

        if (hasSuBinary()) {
            Result root = forceStopWithRoot(packageName);
            if (root.success) {
                return root;
            }
        }

        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(packageName);
                return new Result(true, "普通后台结束", null);
            }
        } catch (Throwable t) {
            return new Result(false, "普通后台结束", t.getClass().getSimpleName());
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
