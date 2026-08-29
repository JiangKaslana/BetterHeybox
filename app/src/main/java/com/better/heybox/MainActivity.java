package com.better.heybox;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Standalone launcher/manager for BetterHeybox.
 *
 * <p>The actual feature hooks still run inside Heybox through libxposed
 * (LSPosed/NPatch). This activity is deliberately dependency-free so adding
 * a launcher does not destabilize the existing module build.</p>
 */
public final class MainActivity extends Activity implements App.OnServiceBoundListener {

    private static final String HEYBOX_PACKAGE = "com.max.xiaoheihe";
    private static final String NPATCH_PACKAGE = "top.nkbe.npatch";
    private static final String SHIZUKU_PLUS_PACKAGE = "af.shizuku.plus.api";
    private static final String SHIZUKU_COMPAT_PACKAGE = "moe.shizuku.privileged.api";

    private LinearLayout statusContainer;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("BetterHeybox");
        buildUi();
        App.addOnServiceBoundListener(this);
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public void onServiceBound() {
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("BetterHeybox");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Rootless / LSPosed 管理中心");
        subtitle.setTextSize(15);
        subtitle.setAlpha(0.7f);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle);

        statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.VERTICAL);
        statusContainer.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.addView(statusContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addSectionLabel(root, "快捷操作");
        addButton(root, "打开小黑盒", v -> openPackage(HEYBOX_PACKAGE));
        addButton(root, "重启小黑盒", v -> restartHeybox());
        addButton(root, "打开 NPatch", v -> openPackageOrStore(NPATCH_PACKAGE));
        addButton(root, "打开 Shizuku+", v -> openPackageOrStore(SHIZUKU_PLUS_PACKAGE));
        addButton(root, "应用详情", v -> openAppDetails());
        addButton(root, "刷新状态", v -> refreshStatus());

        TextView note = new TextView(this);
        note.setText("核心 Hook 仍由 libxposed 提供：Root 用户使用 LSPosed，无 Root 用户使用 NPatch。Shizuku+ 作为可选增强，不是核心 Hook 引擎。");
        note.setTextSize(13);
        note.setAlpha(0.65f);
        note.setPadding(0, dp(20), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void refreshStatus() {
        if (statusContainer == null) return;
        statusContainer.removeAllViews();

        PackageInfo heybox = getPackageInfo(HEYBOX_PACKAGE);
        addStatus("小黑盒", heybox == null ? "未安装" : "已安装 · " + safeVersion(heybox), heybox != null);

        boolean frameworkConnected = App.getService() != null;
        addStatus("libxposed 服务", frameworkConnected ? "已连接" : "未连接", frameworkConnected);

        boolean npatch = isInstalled(NPATCH_PACKAGE);
        addStatus("NPatch", npatch ? "已安装" : "未安装", npatch);

        boolean shizukuPlus = isInstalled(SHIZUKU_PLUS_PACKAGE);
        boolean shizukuCompat = isInstalled(SHIZUKU_COMPAT_PACKAGE);
        String shizukuText = shizukuPlus
                ? (shizukuCompat ? "已安装 · Compat Hub 可见" : "已安装")
                : (shizukuCompat ? "检测到兼容服务" : "未安装");
        addStatus("Shizuku+", shizukuText, shizukuPlus || shizukuCompat);

        boolean root = hasSuBinary();
        addStatus("Root 环境", root ? "检测到 su" : "未检测到", root);

        TextView hint = new TextView(this);
        hint.setText(frameworkConnected
                ? "BetterHeybox 已连接到 libxposed 服务。"
                : "如果你是无 Root 用户，请使用 NPatch 将 BetterHeybox 注入小黑盒；仅安装本 APK 不会自动产生 Hook。 ");
        hint.setTextSize(13);
        hint.setPadding(0, dp(10), 0, dp(4));
        hint.setAlpha(0.7f);
        statusContainer.addView(hint);
    }

    private void addStatus(String label, String value, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextSize(15);
        row.addView(left, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(this);
        right.setText((ok ? "✓ " : "• ") + value);
        right.setTextSize(14);
        if (ok) right.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(right);
        statusContainer.addView(row);
    }

    private void addSectionLabel(LinearLayout root, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(24), 0, dp(8));
        root.addView(view);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        root.addView(button, lp);
    }

    private void restartHeybox() {
        if (!isInstalled(HEYBOX_PACKAGE)) {
            toast("未安装小黑盒");
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(HEYBOX_PACKAGE);
        } catch (Throwable ignored) {
        }
        handler.postDelayed(() -> openPackage(HEYBOX_PACKAGE), 350L);
        toast("已尝试结束后台并重新打开小黑盒");
    }

    private void openPackage(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            toast("应用未安装或没有可启动界面");
            return;
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (Throwable t) {
            toast("启动失败：" + t.getClass().getSimpleName());
        }
    }

    private void openPackageOrStore(String packageName) {
        if (isInstalled(packageName)) {
            openPackage(packageName);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/" + (NPATCH_PACKAGE.equals(packageName)
                            ? "7723mod/NPatch" : "thejaustin/ShizukuPlus"))));
        } catch (Throwable t) {
            toast("未安装对应应用");
        }
    }

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private boolean isInstalled(String packageName) {
        return getPackageInfo(packageName) != null;
    }

    private PackageInfo getPackageInfo(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private String safeVersion(PackageInfo info) {
        return info.versionName == null ? "未知版本" : info.versionName;
    }

    private boolean hasSuBinary() {
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/data/adb/magisk", "/data/adb/ksu"
        };
        for (String path : paths) {
            try {
                if (new java.io.File(path).exists()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
