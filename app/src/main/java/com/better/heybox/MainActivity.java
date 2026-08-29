package com.better.heybox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Standalone BetterHeybox manager for both LSPosed and rootless NPatch users.
 * Hook code itself remains unchanged and still executes inside Heybox.
 */
public final class MainActivity extends Activity implements App.OnServiceBoundListener {

    private static final String NPATCH_URL = "https://github.com/7723mod/NPatch";
    private static final String SHIZUKU_PLUS_URL = "https://github.com/thejaustin/ShizukuPlus";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "bhx-manager");
        thread.setDaemon(true);
        return thread;
    });

    private LinearLayout statusContainer;
    private TextView readinessView;
    private Button shizukuButton;
    private final Map<String, ToggleBinding> toggles = new LinkedHashMap<>();
    private volatile boolean destroyed;

    private final Shizuku.OnBinderReceivedListener shizukuReceived = this::onShizukuChanged;
    private final Shizuku.OnBinderDeadListener shizukuDead = this::onShizukuChanged;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission =
            (requestCode, grantResult) -> {
                if (requestCode != ShizukuBridge.REQUEST_CODE) return;
                toast(grantResult == PackageManager.PERMISSION_GRANTED
                        ? "Shizuku 已授权"
                        : "Shizuku 授权被拒绝");
                refreshStatus();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("BetterHeybox");
        buildUi();
        App.addOnServiceBoundListener(this);
        App.tryConnectNPatchRemote();
        try {
            Shizuku.addBinderReceivedListener(shizukuReceived);
            Shizuku.addBinderDeadListener(shizukuDead);
            Shizuku.addRequestPermissionResultListener(shizukuPermission);
        } catch (Throwable ignored) {
        }
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        App.tryConnectNPatchRemote();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        App.removeOnServiceBoundListener(this);
        try {
            Shizuku.removeBinderReceivedListener(shizukuReceived);
            Shizuku.removeBinderDeadListener(shizukuDead);
            Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        } catch (Throwable ignored) {
        }
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onServiceBound() {
        refreshStatus();
    }

    private void onShizukuChanged() {
        refreshStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
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
        subtitle.setPadding(0, dp(4), 0, dp(16));
        root.addView(subtitle);

        readinessView = new TextView(this);
        readinessView.setTextSize(15);
        readinessView.setTypeface(Typeface.DEFAULT_BOLD);
        readinessView.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(readinessView, matchWrap());

        addSectionLabel(root, "运行状态");
        statusContainer = new LinearLayout(this);
        statusContainer.setOrientation(LinearLayout.VERTICAL);
        statusContainer.setPadding(dp(14), dp(8), dp(14), dp(8));
        root.addView(statusContainer, matchWrap());

        addSectionLabel(root, "无 Root 配置");
        addButton(root, "检查 / 查看 NPatch 配置步骤", v -> showRootlessGuide());
        addButton(root, "打开 NPatch", v -> openPackageOrWeb(
                RootlessEnvironment.NPATCH_PACKAGE, NPATCH_URL));
        shizukuButton = addButton(root, "授权 Shizuku+", v -> handleShizukuAction());

        addSectionLabel(root, "快捷操作");
        addButton(root, "打开小黑盒", v -> openPackage(RootlessEnvironment.HEYBOX_PACKAGE));
        addButton(root, "可靠重启小黑盒", v -> restartHeybox());
        addButton(root, "重新连接设置服务", v -> {
            App.tryConnectNPatchRemote();
            refreshStatus();
        });
        addButton(root, "BetterHeybox 应用详情", v -> openAppDetails());
        addButton(root, "刷新全部状态", v -> refreshStatus());

        addSectionLabel(root, "常用功能");
        addToggle(root, "屏蔽开屏广告", null, App.KEY_OPEN_SCREEN, true);
        addToggle(root, "屏蔽信息流广告", null, App.KEY_FEED_AD, true);
        addToggle(root, "屏蔽气泡广告", null, App.KEY_BUBBLE_AD, true);
        addToggle(root, "屏蔽角标广告", null, App.KEY_CORNER_AD, true);
        addToggle(root, "屏蔽推广贴", null, App.KEY_PROMOTE_AD, true);
        addToggle(root, "解除复制", "恢复系统文本选择", App.KEY_COPY_POST, true);
        addToggle(root, "系统分享图片", null, App.KEY_SYSTEM_SHARE, true);
        addToggle(root, "视频下载", "显示视频下载入口", App.KEY_VIDEO_DOWNLOAD, true);
        addToggle(root, "视频自动转 MP4", null, App.KEY_VIDEO_TO_MP4, true);
        addToggle(root, "净化分享链接", null, App.KEY_PURIFY_SHARE_LINK, true);

        addSectionLabel(root, "界面与通用");
        addToggle(root, "隐藏发现 Tab", "修改后建议重启小黑盒", App.KEY_HIDE_TAB_HOME, false);
        addToggle(root, "隐藏游戏库 Tab", "修改后建议重启小黑盒", App.KEY_HIDE_TAB_HOT, false);
        addToggle(root, "隐藏社区 Tab", "修改后建议重启小黑盒", App.KEY_HIDE_TAB_GAME, false);
        addToggle(root, "隐藏加号", "修改后建议重启小黑盒", App.KEY_HIDE_ADD, false);
        addToggle(root, "伪装通知权限", "让小黑盒内部判断通知权限为已开启", App.KEY_FAKE_NOTIFICATION, false);
        addToggle(root, "屏蔽小黑盒更新", null, App.KEY_BLOCK_UPDATE, false);
        addToggle(root, "记录 BetterHeybox 日志", null, App.KEY_LOG, false);

        TextView note = new TextView(this);
        note.setText("说明：LSPosed / NPatch 负责进程内 Hook；Shizuku+ 只负责无 Root 下的进程控制。"
                + " 独立管理器和小黑盒内嵌设置采用时间戳同步，最后一次修改的值生效。");
        note.setTextSize(13);
        note.setAlpha(0.65f);
        note.setPadding(0, dp(22), 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    private void refreshStatus() {
        if (destroyed) return;
        App.tryConnectNPatchRemote();
        refreshToggleValues();
        executor.execute(() -> {
            RootlessEnvironment.Snapshot snapshot = RootlessEnvironment.inspect(getApplicationContext());
            handler.post(() -> {
                if (!destroyed) renderStatus(snapshot);
            });
        });
    }

    private void renderStatus(RootlessEnvironment.Snapshot s) {
        if (statusContainer == null) return;
        statusContainer.removeAllViews();

        if (s.rootAvailable) {
            readinessView.setText("✓ Root 环境可用：可继续使用 LSPosed 模式");
        } else if (s.isRootlessReady()) {
            readinessView.setText("✓ 无 Root 基础环境就绪：NPatch + Extreme/更高签名绕过");
        } else if (!s.heyboxInstalled) {
            readinessView.setText("• 请先安装小黑盒");
        } else if (!s.npatchInstalled) {
            readinessView.setText("• 无 Root 使用需要先安装 NPatch");
        } else if (!s.npatchPatched) {
            readinessView.setText("• 小黑盒尚未经过 NPatch 修补");
        } else if (!s.isSignatureBypassCompatible()) {
            readinessView.setText("• NPatch 签名绕过需设为 Extreme（当前 "
                    + RootlessEnvironment.sigBypassLabel(s.sigBypassLevel) + "）");
        } else {
            readinessView.setText("• Rootless 环境已修补，请确认 NPatch 中已启用 BetterHeybox 模块");
        }

        addStatus("小黑盒", s.heyboxInstalled
                ? "已安装 · " + s.heyboxVersion : "未安装", s.heyboxInstalled);
        addStatus("NPatch 管理器", s.npatchInstalled ? "已安装" : "未安装", s.npatchInstalled);
        addStatus("小黑盒 NPatch 注入", s.npatchPatched ? "已检测到" : "未检测到", s.npatchPatched);
        if (s.npatchPatched) {
            String bypass = RootlessEnvironment.sigBypassLabel(s.sigBypassLevel);
            addStatus("签名绕过", bypass,
                    s.isSignatureBypassCompatible());
            addStatus("内嵌模块", s.hasEmbeddedModules ? "已检测到" : "未检测到 / Manager 模式",
                    s.hasEmbeddedModules || s.npatchUseManager);
        }

        boolean backend = App.hasPreferencesBackend();
        String backendText = App.getPreferencesBackendLabel();
        if (!backend && App.getNPatchError() != null) {
            backendText += " · " + shortText(App.getNPatchError(), 48);
        }
        addStatus("设置服务", backendText, backend);
        addStatus("NPatch Remote Provider", s.npatchRemoteProvider ? "可见" : "不可见",
                s.npatchRemoteProvider);

        boolean shizukuAlive = ShizukuBridge.isBinderAlive();
        boolean shizukuGranted = ShizukuBridge.hasPermission();
        String shizukuInstall = s.shizukuPlusInstalled
                ? "Shizuku+ 已安装"
                : (s.shizukuCompatInstalled ? "Compat Hub 可见" : "未安装 Shizuku+");
        String shizukuState = shizukuInstall
                + (shizukuAlive ? (shizukuGranted ? " · 服务已授权" : " · 服务运行，待授权") : " · 服务未连接");
        addStatus("Shizuku", shizukuState, shizukuGranted);
        addStatus("Root", s.rootAvailable ? "检测到 su" : "无", s.rootAvailable);

        if (shizukuButton != null) {
            if (shizukuGranted) {
                shizukuButton.setText("Shizuku+ 已授权");
            } else if (shizukuAlive) {
                shizukuButton.setText("授权 Shizuku+");
            } else {
                shizukuButton.setText("打开 / 启动 Shizuku+");
            }
        }
    }

    private void refreshToggleValues() {
        for (ToggleBinding binding : toggles.values()) {
            binding.updating = true;
            try {
                binding.view.setChecked(App.readBoolean(binding.key, binding.defaultValue));
                binding.view.setEnabled(true);
            } finally {
                binding.updating = false;
            }
        }
    }

    private void handleShizukuAction() {
        RootlessEnvironment.Snapshot snapshot = RootlessEnvironment.inspect(getApplicationContext());
        if (!snapshot.shizukuPlusInstalled && !snapshot.shizukuCompatInstalled) {
            openPackageOrWeb(RootlessEnvironment.SHIZUKU_PLUS_PACKAGE, SHIZUKU_PLUS_URL);
            return;
        }
        if (!ShizukuBridge.isBinderAlive()) {
            openPackageOrWeb(RootlessEnvironment.SHIZUKU_PLUS_PACKAGE, SHIZUKU_PLUS_URL);
            toast("请先在 Shizuku+ 中启动服务，然后返回 BetterHeybox");
            return;
        }
        if (ShizukuBridge.hasPermission()) {
            toast("Shizuku 已授权，可以可靠重启小黑盒");
            return;
        }
        if (ShizukuBridge.shouldShowRationale()) {
            toast("Shizuku 权限已被拒绝，请在 Shizuku+ 的授权应用中重新允许 BetterHeybox");
            openPackageOrWeb(RootlessEnvironment.SHIZUKU_PLUS_PACKAGE, SHIZUKU_PLUS_URL);
            return;
        }
        if (!ShizukuBridge.requestPermission()) {
            toast("无法发起 Shizuku 授权，请检查 Compat Hub 和服务状态");
        }
    }

    private void restartHeybox() {
        if (!isInstalled(RootlessEnvironment.HEYBOX_PACKAGE)) {
            toast("未安装小黑盒");
            return;
        }
        toast("正在重启小黑盒…");
        executor.execute(() -> {
            PrivilegedOps.Result result = PrivilegedOps.forceStop(
                    getApplicationContext(), RootlessEnvironment.HEYBOX_PACKAGE);
            handler.postDelayed(() -> {
                if (destroyed) return;
                if (result.success) {
                    toast("已通过 " + result.backend + " 结束小黑盒进程");
                    openPackage(RootlessEnvironment.HEYBOX_PACKAGE);
                } else {
                    toast("重启失败：" + (result.error == null ? "未知原因" : result.error));
                }
                refreshStatus();
            }, 450L);
        });
    }

    private void showRootlessGuide() {
        RootlessEnvironment.Snapshot s = RootlessEnvironment.inspect(getApplicationContext());
        StringBuilder message = new StringBuilder();
        message.append("推荐无 Root 路线：\n\n")
                .append("1. 安装 NPatch。\n")
                .append("2. 在 NPatch 选择小黑盒进行本地修补。\n")
                .append("3. 把 BetterHeybox 加入修补模块。\n")
                .append("4. 破解签名校验选择 Extreme。\n")
                .append("5. 安装修补后的小黑盒并启动一次。\n")
                .append("6. 回到本页确认“NPatch 注入”和“设置服务”。\n\n");

        if (s.npatchPatched) {
            message.append("当前检测：小黑盒已 NPatch 修补；签名绕过 = ")
                    .append(RootlessEnvironment.sigBypassLabel(s.sigBypassLevel))
                    .append("。\n");
        } else {
            message.append("当前检测：尚未检测到 NPatch 修补标记。\n");
        }
        if (!s.hasEmbeddedModules && s.npatchPatched) {
            message.append("未在 APK 中看到内嵌模块，可能使用 NPatch Manager 模式；请在 NPatch 中确认 BetterHeybox 已启用。\n");
        }

        new AlertDialog.Builder(this)
                .setTitle("BetterHeybox 无 Root 配置")
                .setMessage(message.toString())
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开 NPatch", (dialog, which) -> openPackageOrWeb(
                        RootlessEnvironment.NPATCH_PACKAGE, NPATCH_URL))
                .show();
    }

    private void addStatus(String label, String value, boolean ok) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        TextView left = new TextView(this);
        left.setText(label);
        left.setTextSize(14);
        row.addView(left, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView right = new TextView(this);
        right.setText((ok ? "✓ " : "• ") + value);
        right.setTextSize(13);
        if (ok) right.setTypeface(Typeface.DEFAULT_BOLD);
        right.setGravity(Gravity.END);
        right.setMaxWidth(dp(230));
        row.addView(right);
        statusContainer.addView(row);
    }

    private void addToggle(LinearLayout root, String title, String description,
                           String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(7), dp(4), dp(7));

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15);
        textColumn.addView(titleView);
        if (description != null && !description.isEmpty()) {
            TextView desc = new TextView(this);
            desc.setText(description);
            desc.setTextSize(12);
            desc.setAlpha(0.65f);
            textColumn.addView(desc);
        }
        row.addView(textColumn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        ToggleBinding binding = new ToggleBinding(key, defaultValue, toggle);
        toggle.setChecked(App.readBoolean(key, defaultValue));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (binding.updating) return;
            App.writeBoolean(key, isChecked);
            if (App.hasPreferencesBackend()) {
                toast(title + (isChecked ? "：已开启" : "：已关闭"));
            } else {
                toast(title + "：已保存，等待 NPatch/LSPosed 设置服务连接");
            }
        });
        row.addView(toggle);
        root.addView(row, matchWrap());
        toggles.put(key, binding);
    }

    private void addSectionLabel(LinearLayout root, String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(24), 0, dp(8));
        root.addView(view);
    }

    private Button addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(6);
        root.addView(button, lp);
        return button;
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

    private void openPackageOrWeb(String packageName, String url) {
        if (isInstalled(packageName)) {
            openPackage(packageName);
            return;
        }
        // Shizuku+ may use Compat Hub/drop-in while the manager package is not launchable.
        if (RootlessEnvironment.SHIZUKU_PLUS_PACKAGE.equals(packageName)
                && isInstalled(RootlessEnvironment.SHIZUKU_COMPAT_PACKAGE)) {
            Intent compat = getPackageManager().getLaunchIntentForPackage(
                    RootlessEnvironment.SHIZUKU_COMPAT_PACKAGE);
            if (compat != null) {
                try {
                    startActivity(compat);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            toast("无法打开下载页面");
        }
    }

    private void openAppDetails() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            toast("无法打开应用详情");
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            PackageInfo ignored = getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String shortText(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }

    private void toast(String message) {
        if (!destroyed) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class ToggleBinding {
        final String key;
        final boolean defaultValue;
        final Switch view;
        boolean updating;

        ToggleBinding(String key, boolean defaultValue, Switch view) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.view = view;
        }
    }
}
