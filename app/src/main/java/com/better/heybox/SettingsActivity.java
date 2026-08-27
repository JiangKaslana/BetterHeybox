package com.better.heybox;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
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
 *
 * <p>修复「模块进程未运行时切换开关无效」：开关读写统一走 {@link App#readBoolean}/{@link App#writeBoolean}，
 * 框架服务未连接时写入本地待提交缓存，服务绑定后自动补交；服务绑定后刷新开关显示。</p>
 */
public class SettingsActivity extends Activity {

    /** 服务未连接时用户已手动切换过（防止服务绑定后刷新覆盖用户刚切的值） */
    private boolean mDirty;
    /** 程序化刷新开关时抑制监听回调 */
    private boolean mRefreshing;

    private TextView mLogPathView;

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
        mLogPathView = findViewById(R.id.log_path);

        // 广告过滤（即时生效）
        bindSwitch(R.id.switch_open_screen, App.KEY_OPEN_SCREEN, true);
        bindSwitch(R.id.switch_feed_ad, App.KEY_FEED_AD, true);
        bindSwitch(R.id.switch_bubble_ad, App.KEY_BUBBLE_AD, true);
        bindSwitch(R.id.switch_corner_ad, App.KEY_CORNER_AD, true);
        bindSwitch(R.id.switch_promote_ad, App.KEY_PROMOTE_AD, true);

        // 底部导航栏屏蔽（需重启小黑盒生效）；tab 名称按小黑盒资源动态解析（版本自适应）
        TextView tabHome = findViewById(R.id.tv_tab_home);
        if (tabHome != null) {
            tabHome.setText("隐藏「" + MainModule.getHeyboxTabLabel(this, "discover", "首页") + "」");
        }
        TextView tabHot = findViewById(R.id.tv_tab_hot);
        if (tabHot != null) {
            tabHot.setText("隐藏「" + MainModule.getHeyboxTabLabel(this, "game_store", "热点") + "」");
        }
        TextView tabGame = findViewById(R.id.tv_tab_game);
        if (tabGame != null) {
            tabGame.setText("隐藏「" + MainModule.getHeyboxTabLabel(this, "bbs", "游戏库") + "」");
        }
        bindRestartSwitch(R.id.switch_hide_tab_home, App.KEY_HIDE_TAB_HOME, false);
        bindRestartSwitch(R.id.switch_hide_tab_hot, App.KEY_HIDE_TAB_HOT, false);
        bindRestartSwitch(R.id.switch_hide_tab_game, App.KEY_HIDE_TAB_GAME, false);
        bindRestartSwitch(R.id.switch_hide_add, App.KEY_HIDE_ADD, false);

        // 帖子增强（进详情页即时生效）
        bindSwitch(R.id.switch_copy_post, App.KEY_COPY_POST, true);
        bindSwitch(R.id.switch_system_share, App.KEY_SYSTEM_SHARE, true);

        // 每日任务（3 种分享类型：图片帖 / 普通帖 / 频道）
        bindSwitch(R.id.switch_daily_task, App.KEY_DAILY_TASK_ENABLED, false);
        bindLinkRow(R.id.btn_daily_picture, R.string.daily_task_picture, App.KEY_DAILY_TASK_PICTURE);
        bindLinkRow(R.id.btn_daily_normal, R.string.daily_task_normal, App.KEY_DAILY_TASK_NORMAL);
        bindLinkRow(R.id.btn_daily_channel, R.string.daily_task_channel, App.KEY_DAILY_TASK_CHANNEL);
        bindChannelRow();
        bindClearDailyRow();

        // 通用
        bindSwitch(R.id.switch_fake_notification, App.KEY_FAKE_NOTIFICATION, false);
        bindSwitch(R.id.switch_block_update, App.KEY_BLOCK_UPDATE, false);
        bindSwitch(R.id.switch_log, App.KEY_LOG, false);

        // 退出按钮：关闭设置界面
        findViewById(R.id.btn_exit).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        refreshAll();
        updateLogPath();

        // 框架服务绑定后刷新开关显示（用户已手动切过则跳过，避免覆盖）
        App.addOnServiceBoundListener(new App.OnServiceBoundListener() {
            @Override
            public void onServiceBound() {
                if (!mDirty) {
                    refreshAll();
                }
                updateLogPath();
            }
        });
    }

    private void bindSwitch(int switchId, final String key, boolean defaultValue) {
        final Switch sw = findViewById(switchId);
        sw.setChecked(App.readBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mRefreshing) {
                    return;
                }
                mDirty = true;
                App.writeBoolean(key, isChecked);
                LogRecorder.recordEvent("设置页开关切换: key=" + key + ", value=" + isChecked);
                if (sw.getId() == R.id.switch_log) {
                    updateLogPath();
                }
            }
        });
    }

    /** 需要重启小黑盒才生效的开关（底栏屏蔽），切换后弹「重启后生效」提示 */
    private void bindRestartSwitch(int switchId, final String key, boolean defaultValue) {
        final Switch sw = findViewById(switchId);
        sw.setChecked(App.readBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mRefreshing) {
                    return;
                }
                mDirty = true;
                App.writeBoolean(key, isChecked);
                LogRecorder.recordEvent("设置页开关切换(重启生效): key=" + key + ", value=" + isChecked);
                showRestartDialog();
            }
        });
    }

    /** 重新读取所有开关状态并回填 UI（不触发监听回调） */
    private void refreshAll() {
        mRefreshing = true;
        try {
            setChecked(R.id.switch_open_screen, App.KEY_OPEN_SCREEN, true);
            setChecked(R.id.switch_feed_ad, App.KEY_FEED_AD, true);
            setChecked(R.id.switch_bubble_ad, App.KEY_BUBBLE_AD, true);
            setChecked(R.id.switch_corner_ad, App.KEY_CORNER_AD, true);
            setChecked(R.id.switch_promote_ad, App.KEY_PROMOTE_AD, true);
            setChecked(R.id.switch_hide_tab_home, App.KEY_HIDE_TAB_HOME, false);
            setChecked(R.id.switch_hide_tab_hot, App.KEY_HIDE_TAB_HOT, false);
            setChecked(R.id.switch_hide_tab_game, App.KEY_HIDE_TAB_GAME, false);
            setChecked(R.id.switch_hide_add, App.KEY_HIDE_ADD, false);
            setChecked(R.id.switch_copy_post, App.KEY_COPY_POST, true);
            setChecked(R.id.switch_system_share, App.KEY_SYSTEM_SHARE, true);
            setChecked(R.id.switch_daily_task, App.KEY_DAILY_TASK_ENABLED, false);
            setChecked(R.id.switch_fake_notification, App.KEY_FAKE_NOTIFICATION, false);
            setChecked(R.id.switch_block_update, App.KEY_BLOCK_UPDATE, false);
            setChecked(R.id.switch_log, App.KEY_LOG, false);
        } finally {
            mRefreshing = false;
        }
    }

    private void setChecked(int switchId, String key, boolean defaultValue) {
        Switch sw = findViewById(switchId);
        if (sw != null) {
            sw.setChecked(App.readBoolean(key, defaultValue));
        }
    }

    /** 显示日志文件路径（仅模块进程侧文件；小黑盒进程日志在其应用目录） */
    private void updateLogPath() {
        if (mLogPathView == null) {
            return;
        }
        Switch sw = findViewById(R.id.switch_log);
        boolean enabled = sw != null && sw.isChecked();
        if (!enabled) {
            mLogPathView.setVisibility(View.GONE);
            return;
        }
        String path = LogRecorder.getLogFilePath();
        if (path != null) {
            mLogPathView.setText(getString(R.string.log_file_path, path));
            mLogPathView.setVisibility(View.VISIBLE);
        } else {
            mLogPathView.setVisibility(View.GONE);
        }
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

    /** 绑定分享链接行（点击弹单行编辑框，RemotePreferences 跨进程同步到小黑盒 Hook 侧） */
    private void bindLinkRow(int rowId, final int titleRes, final String key) {
        View row = findViewById(rowId);
        if (row == null) {
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEditLinkDialog(getString(titleRes), key);
            }
        });
    }
    /** 「分享渠道」选择行：写入 RemotePreferences，小黑盒进程下次打卡按所选渠道自动分享 */
    private void bindChannelRow() {
        View row = findViewById(R.id.btn_daily_channel_type);
        if (row == null) {
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final String[] channels = {"QQ", "WECHAT", "WEIBO"};
                final String[] labels = {"QQ / QQ空间", "微信 / 朋友圈", "微博"};
                String cur = App.readString(App.KEY_SHARE_CHANNEL, "QQ");
                int checked = "WECHAT".equals(cur) ? 1 : ("WEIBO".equals(cur) ? 2 : 0);
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.daily_task_channel_type)
                        .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                            App.writeString(App.KEY_SHARE_CHANNEL, channels[which]);
                            LogRecorder.recordEvent("分享渠道已选择: " + channels[which]);
                            Toast.makeText(SettingsActivity.this,
                                    getString(R.string.daily_task_channel_type_done, labels[which]),
                                    Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    private void bindClearDailyRow() {
        View row = findViewById(R.id.btn_daily_clear);
        if (row == null) {
            return;
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    App.writeString(App.KEY_DAILY_TASK_DONE_DATE, "");
                    App.writeBoolean(App.KEY_DAILY_TASK_RESET, true);
                    LogRecorder.recordEvent("清除今日打卡：已写入重置标志");
                    Toast.makeText(SettingsActivity.this, R.string.daily_task_clear_done,
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    Log.e(TAG, "清除今日打卡失败: " + t);
                    Toast.makeText(SettingsActivity.this, "清除失败，请重试", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /** 编辑单个分享链接 */
    private void showEditLinkDialog(String title, final String key) {
        try {
            final EditText input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
            input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456");
            String cur = App.readString(key, "");
            input.setText(cur == null ? "" : cur);
            input.setSelection(input.getText().length());
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(input)
                    .setPositiveButton("保存", (dialog, which) -> {
                        App.writeString(key, input.getText().toString().trim());
                        LogRecorder.recordEvent("分享链接已保存: " + key);
                        Toast.makeText(this, R.string.daily_task_link_saved, Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "打开链接编辑框失败: " + t);
        }
    }

    private static final String TAG = "BetterHeybox";
}
