package com.better.heybox;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * Small compatibility layer around the standard Shizuku API.
 *
 * <p>Shizuku+ can serve this API through its Compat Hub, so BetterHeybox does not
 * need to compile against any Shizuku+ private/Plus API. Core Xposed hooks are
 * still provided by LSPosed/NPatch; this bridge is only used for privileged
 * process operations such as a reliable force-stop on non-rooted devices.</p>
 */
public final class ShizukuBridge {

    public static final int REQUEST_CODE = 0x4248;

    private ShizukuBridge() {
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean hasPermission() {
        if (!isBinderAlive()) {
            return false;
        }
        try {
            return !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldShowRationale() {
        try {
            return isBinderAlive() && Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean requestPermission() {
        if (!isBinderAlive()) {
            return false;
        }
        if (hasPermission()) {
            return true;
        }
        try {
            if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) {
                return false;
            }
            Shizuku.requestPermission(REQUEST_CODE);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Execute one short command using the legacy process bridge.
     *
     * <p>The API is intentionally invoked reflectively because upstream marks
     * {@code newProcess} as transitional in newer source revisions, while API
     * 13 servers (including Shizuku+) keep it for root-app compatibility.</p>
     */
    public static Result run(String... command) {
        if (!hasPermission()) {
            return Result.failure("Shizuku 未授权");
        }
        Object process = null;
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            process = newProcess.invoke(null, command, null, null);
            if (process == null) {
                return Result.failure("Shizuku 进程创建失败");
            }

            Method waitFor = process.getClass().getMethod("waitFor");
            Method getInputStream = process.getClass().getMethod("getInputStream");
            Method getErrorStream = process.getClass().getMethod("getErrorStream");
            Method destroy = process.getClass().getMethod("destroy");

            int exitCode = (Integer) waitFor.invoke(process);
            String stdout = readAll((InputStream) getInputStream.invoke(process));
            String stderr = readAll((InputStream) getErrorStream.invoke(process));
            try {
                destroy.invoke(process);
            } catch (Throwable ignored) {
            }
            return new Result(exitCode == 0, exitCode, stdout, stderr, null);
        } catch (Throwable t) {
            if (process != null) {
                try {
                    process.getClass().getMethod("destroy").invoke(process);
                } catch (Throwable ignored) {
                }
            }
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            return Result.failure(cause.getClass().getSimpleName() + ": "
                    + String.valueOf(cause.getMessage()));
        }
    }

    public static Result forceStop(String packageName) {
        return run("sh", "-c", "am force-stop " + shellQuote(packageName));
    }

    private static String shellQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String readAll(InputStream input) {
        if (input == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) {
                    out.append('\n');
                }
                out.append(line);
            }
        } catch (Throwable ignored) {
        }
        return out.toString().trim();
    }

    public static final class Result {
        public final boolean success;
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final String error;

        Result(boolean success, int exitCode, String stdout, String stderr, String error) {
            this.success = success;
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.error = error;
        }

        static Result failure(String error) {
            return new Result(false, -1, "", "", error);
        }

        public String describeFailure() {
            if (error != null && !error.isEmpty()) {
                return error;
            }
            if (!stderr.isEmpty()) {
                return stderr;
            }
            return "exit=" + exitCode;
        }
    }
}
