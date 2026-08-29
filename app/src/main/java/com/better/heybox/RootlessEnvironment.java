package com.better.heybox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runtime inspection helpers for the NPatch rootless deployment. */
public final class RootlessEnvironment {

    public static final String HEYBOX_PACKAGE = "com.max.xiaoheihe";
    public static final String NPATCH_PACKAGE = "top.nkbe.npatch";
    public static final String NPATCH_REMOTE_AUTHORITY = "top.nkbe.npatch.remote";
    public static final String SHIZUKU_PLUS_PACKAGE = "af.shizuku.plus.api";
    public static final String SHIZUKU_COMPAT_PACKAGE = "moe.shizuku.privileged.api";

    private RootlessEnvironment() {
    }

    public static Snapshot inspect(Context context) {
        PackageManager pm = context.getPackageManager();
        Snapshot out = new Snapshot();
        out.sdkInt = Build.VERSION.SDK_INT;
        out.npatchSupported = Build.VERSION.SDK_INT >= 28;
        out.npatchInstalled = packageInfo(pm, NPATCH_PACKAGE) != null;
        out.shizukuPlusInstalled = packageInfo(pm, SHIZUKU_PLUS_PACKAGE) != null;
        out.shizukuCompatInstalled = packageInfo(pm, SHIZUKU_COMPAT_PACKAGE) != null;
        out.npatchRemoteProvider = resolveProvider(pm, NPATCH_REMOTE_AUTHORITY);
        out.rootAvailable = PrivilegedOps.hasSuBinary();

        PackageInfo heybox = packageInfo(pm, HEYBOX_PACKAGE);
        out.heyboxInstalled = heybox != null;
        if (heybox == null) {
            return out;
        }
        out.heyboxVersion = heybox.versionName == null ? "未知" : heybox.versionName;

        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(HEYBOX_PACKAGE, PackageManager.GET_META_DATA);
            out.heyboxSourceDir = appInfo.sourceDir;
            Bundle meta = appInfo.metaData;
            String encoded = meta == null ? null : meta.getString("npatch");
            if (encoded != null && !encoded.isEmpty()) {
                out.npatchPatched = true;
                try {
                    String json = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
                    applyConfig(out, new JSONObject(json));
                } catch (Throwable ignored) {
                }
            }
            inspectNpatchAssets(out, appInfo.sourceDir);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void inspectNpatchAssets(Snapshot out, String sourceDir) {
        if (sourceDir == null || sourceDir.isEmpty()) return;
        File apk = new File(sourceDir);
        if (!apk.isFile()) return;

        try (ZipFile zip = new ZipFile(apk)) {
            ZipEntry config = zip.getEntry("assets/npatch/config.json");
            if (config != null) {
                out.npatchPatched = true;
                if (!out.npatchConfigReadable) {
                    try (InputStream input = zip.getInputStream(config)) {
                        String json = readUtf8(input);
                        if (!json.isEmpty()) {
                            applyConfig(out, new JSONObject(json));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || name == null || !name.startsWith("assets/npatch/modules/")) {
                    continue;
                }
                out.hasEmbeddedModules = true;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("betterheybox") || lower.contains("com.better.heybox")) {
                    out.betterHeyboxModuleVisible = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void applyConfig(Snapshot out, JSONObject config) {
        if (config == null) return;
        out.sigBypassLevel = config.optInt("sigBypassLevel", out.sigBypassLevel);
        out.npatchUseManager = config.optBoolean("useManager", out.npatchUseManager);
        out.npatchConfigReadable = true;
    }

    private static String readUtf8(InputStream input) {
        if (input == null) return "";
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean resolveProvider(PackageManager pm, String authority) {
        try {
            return pm.resolveContentProvider(authority, 0) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static PackageInfo packageInfo(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, 0);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String sigBypassLabel(int level) {
        switch (level) {
            case 0:
                return "None";
            case 1:
                return "Basic";
            case 2:
                return "High";
            case 3:
                return "Extreme";
            case 4:
                return "Seccomp";
            case 5:
                return "Stealth";
            default:
                return "未知";
        }
    }

    public static final class Snapshot {
        public int sdkInt;
        public boolean npatchSupported;
        public boolean heyboxInstalled;
        public String heyboxVersion;
        public String heyboxSourceDir;
        public boolean npatchInstalled;
        public boolean npatchPatched;
        public boolean npatchConfigReadable;
        public boolean npatchUseManager;
        public boolean hasEmbeddedModules;
        public boolean betterHeyboxModuleVisible;
        public int sigBypassLevel = -1;
        public boolean npatchRemoteProvider;
        public boolean shizukuPlusInstalled;
        public boolean shizukuCompatInstalled;
        public boolean rootAvailable;

        /**
         * BetterHeybox's documented NPatch compatibility path is specifically
         * Extreme (3). Seccomp/Stealth are different modes, not assumed supersets.
         */
        public boolean isSignatureBypassCompatible() {
            return sigBypassLevel == 3;
        }

        public boolean isRootlessReady() {
            return npatchSupported
                    && heyboxInstalled
                    && npatchInstalled
                    && npatchPatched
                    && isSignatureBypassCompatible();
        }
    }
}
