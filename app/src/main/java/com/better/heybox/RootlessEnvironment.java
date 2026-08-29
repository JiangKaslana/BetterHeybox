package com.better.heybox;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
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
                    JSONObject config = new JSONObject(json);
                    out.sigBypassLevel = config.optInt("sigBypassLevel", -1);
                    out.npatchUseManager = config.optBoolean("useManager", false);
                    out.npatchConfigReadable = true;
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
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!entry.isDirectory()
                        && name != null
                        && name.startsWith("assets/npatch/modules/")) {
                    out.hasEmbeddedModules = true;
                    if (name.toLowerCase().contains("betterheybox")
                            || name.toLowerCase().contains("com.better.heybox")) {
                        out.betterHeyboxModuleVisible = true;
                    }
                }
            }
        } catch (Throwable ignored) {
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

        /** BetterHeybox README requires Extreme; stronger NPatch modes are accepted too. */
        public boolean isSignatureBypassCompatible() {
            return sigBypassLevel >= 3;
        }

        public boolean isRootlessReady() {
            return heyboxInstalled
                    && npatchInstalled
                    && npatchPatched
                    && isSignatureBypassCompatible();
        }
    }
}
