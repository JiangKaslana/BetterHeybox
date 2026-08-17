package com.better.heybox;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import io.github.libxposed.service.XposedService;

/**
 * BetterHeybox 设置界面（分组卡片样式）。
 * 开关状态写入 RemotePreferences（经 LSPosed 框架跨进程同步到 Hook 侧）。
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        setTitle(R.string.settings_title);
        ThemeUtils.applyFilledButton(findViewById(R.id.btn_exit), this, 24);
        TextView versionFooter = findViewById(R.id.version_footer);
        if (versionFooter != null) {
            versionFooter.setText(getString(R.string.version_footer,
                    VersionUtils.getVersionName(this)));
        }

        // 广告过滤（即时生效）
        bindSwitch(R.id.switch_open_screen, App.KEY_OPEN_SCREEN, true);
        bindSwitch(R.id.switch_feed_ad, App.KEY_FEED_AD, true);
        bindSwitch(R.id.switch_bubble_ad, App.KEY_BUBBLE_AD, true);
        bindSwitch(R.id.switch_corner_ad, App.KEY_CORNER_AD, true);
        bindSwitch(R.id.switch_promote_ad, App.KEY_PROMOTE_AD, true);

        // 底部导航栏屏蔽（需重启小黑盒生效）
        bindRestartSwitch(R.id.switch_hide_tab_home, App.KEY_HIDE_TAB_HOME, false);
        bindRestartSwitch(R.id.switch_hide_tab_hot, App.KEY_HIDE_TAB_HOT, false);
        bindRestartSwitch(R.id.switch_hide_tab_game, App.KEY_HIDE_TAB_GAME, false);
        bindRestartSwitch(R.id.switch_hide_add, App.KEY_HIDE_ADD, false);

        // 帖子增强（进详情页即时生效）
        bindSwitch(R.id.switch_copy_post, App.KEY_COPY_POST, true);

        // 通用
        bindSwitch(R.id.switch_block_update, App.KEY_BLOCK_UPDATE, false);

        // 退出按钮：关闭设置界面
        findViewById(R.id.btn_exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }

    private void bindSwitch(int switchId, final String key, boolean defaultValue) {
        final Switch sw = findViewById(switchId);
        SharedPreferences prefs = App.getPrefs();
        sw.setChecked(prefs != null ? prefs.getBoolean(key, defaultValue) : defaultValue);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences p = App.getPrefs();
                if (p == null) {
                    Toast.makeText(SettingsActivity.this, R.string.service_not_ready, Toast.LENGTH_SHORT).show();
                    return;
                }
                p.edit().putBoolean(key, isChecked).apply();
            }
        });
    }

    /** 需要重启小黑盒才生效的开关（底栏屏蔽），切换后弹「重启后生效」提示 */
    private void bindRestartSwitch(int switchId, final String key, boolean defaultValue) {
        final Switch sw = findViewById(switchId);
        SharedPreferences prefs = App.getPrefs();
        sw.setChecked(prefs != null ? prefs.getBoolean(key, defaultValue) : defaultValue);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                SharedPreferences p = App.getPrefs();
                if (p == null) {
                    Toast.makeText(SettingsActivity.this, R.string.service_not_ready, Toast.LENGTH_SHORT).show();
                    return;
                }
                p.edit().putBoolean(key, isChecked).apply();
                showRestartDialog();
            }
        });
    }

    /** 「重启后生效」弹窗（模仿小黑盒 DNS 设置的「重新启动APP生效」交互） */
    private void showRestartDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_dialog_title)
                .setMessage(R.string.restart_dialog_msg)
                .setPositiveButton(R.string.restart_dialog_restart, (dialog, which) -> restartHeybox())
                .setNegativeButton(R.string.restart_dialog_ok, null)
                .show();
    }

    /** 立即重启/应用设置（无 root 优先）：
     *  1. 杀小黑盒后台进程（有前台服务时无效）
     *  2. LSPosed 热重载模块（无 root，返回小黑盒后新设置生效） */
    private void restartHeybox() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.killBackgroundProcesses("com.max.xiaoheihe");
        } catch (Throwable ignored) {
        }
        if (tryHotReload()) {
            Toast.makeText(this, "设置已应用，返回小黑盒即可生效", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.restart_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 通过 LSPosed service 热重载模块到小黑盒进程（无 root） */
    private boolean tryHotReload() {
        try {
            XposedService service = App.getService();
            if (service == null) {
                Log.e(TAG, "热重载失败: service 未连接");
                return false;
            }
            Object targetsObj = service.getClass().getMethod("getRunningTargets").invoke(service);
            if (!(targetsObj instanceof List)) {
                Log.e(TAG, "热重载失败: getRunningTargets 返回类型异常 " + (targetsObj == null ? "null" : targetsObj.getClass()));
                return false;
            }
            List<?> targets = (List<?>) targetsObj;
            if (targets.isEmpty()) {
                Log.e(TAG, "热重载失败: 无运行中的目标进程");
                return false;
            }
            // HotReloadCallback 接口动态代理（避免直接依赖嵌套接口类型）
            ClassLoader cl = service.getClass().getClassLoader();
            Class<?> callbackCls = Class.forName(
                    "io.github.libxposed.service.XposedService$HotReloadCallback", true, cl);
            Object callback = Proxy.newProxyInstance(cl, new Class<?>[]{callbackCls},
                    (proxy, method, args) -> null);

            boolean any = false;
            for (Object target : targets) {
                Object proc = target.getClass().getMethod("getProcessName").invoke(target);
                Log.i(TAG, "热重载目标进程: " + proc + " (" + target.getClass().getName() + ")");
                if (String.valueOf(proc).startsWith("com.max.xiaoheihe")) {
                    for (Method m : service.getClass().getMethods()) {
                        if ("hotReloadModule".equals(m.getName()) && m.getParameterTypes().length == 3) {
                            m.invoke(service, target, null, callback);
                            Log.i(TAG, "已调用 hotReloadModule: " + proc);
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        Log.e(TAG, "热重载失败: 未找到 hotReloadModule 方法");
                    }
                }
            }
            return any;
        } catch (Throwable t) {
            Log.e(TAG, "热重载异常: " + t);
            return false;
        }
    }

    private static final String TAG = "BetterHeybox";
}
