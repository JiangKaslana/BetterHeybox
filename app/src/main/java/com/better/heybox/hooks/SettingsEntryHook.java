package com.better.heybox.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.better.heybox.App;
import com.better.heybox.BuildFlags;
import com.better.heybox.Checkpoint;
import com.better.heybox.ConfigBackup;
import com.better.heybox.DexKitResolver;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.LogExport;
import com.better.heybox.LogRecorder;
import com.better.heybox.ThemeUtils;
import com.better.heybox.VideoDownloadManager;
import com.better.heybox.MainModule;
import com.better.heybox.PreferenceReceiver;

public final class SettingsEntryHook {

    private final MainModule module;

    public SettingsEntryHook(MainModule module) {
        this.module = module;
    }
    public void install(ClassLoader cl) {
        hookSettingsEntry(cl);
    }

    private static final String ENTRY_TAG = "betterheybox_entry";
    private static final String EMBEDDED_SETTINGS_TAG = "betterheybox_embedded_settings";

    /** 内嵌面板配置导出*/
    private static final int REQUEST_EMBEDDED_EXPORT = 0x4248;
    /** 内嵌面板配置导入 */
    private static final int REQUEST_EMBEDDED_IMPORT = 0x4249;
    /** 内嵌面板日志导出*/
    private static final int REQUEST_EMBEDDED_LOG_EXPORT = 0x424A;
    /** 视频保存位置选择（系统文件夹选择器） */
    private static final int REQUEST_PICK_SAVE_DIR = 0x424B;

    /** 文件选择结果回调 */
    private interface PickCallback {
        void onResult(Uri uri);
    }

    /** 等待中的文件选择回调 */
    private static PickCallback sPendingPick;

    private WeakReference<View> mSettingsPanel;

    private static class SwitchDef {
        final String title;
        final String desc;
        final String key;
        final boolean def;
        final boolean restart;
        final boolean clickRow;
        final String editKey; // clickRow 时编辑的字符串配置 key
        final boolean actionClearDaily; // clickRow 动作：清除每日打卡状态并重试
        final boolean actionChannel; // clickRow 动作：选择分享渠道
        final boolean actionExport; // clickRow 动作：导出配置
        final boolean actionImport; // clickRow 动作：导入配置
        final boolean actionExportLog; // clickRow 动作：导出日志
        final boolean actionRuntimeStatus; // clickRow 动作：查看运行状态（仅 Debug 构建显示）
        final boolean actionPickDir; // clickRow 动作：选择视频保存文件夹（系统 SAF 选择器）
        final boolean actionDownloadManager; // clickRow 动作：打开下载管理页
        SwitchDef(String title, String desc, String key, boolean def, boolean restart) {
            this(title, desc, key, def, restart, false, null, false, false, false, false, false, false, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart, boolean clickRow) {
            this(title, desc, key, def, restart, clickRow, null, false, false, false, false, false, false, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart, boolean clickRow, String editKey) {
            this(title, desc, key, def, restart, clickRow, editKey, false, false, false, false, false, false, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, boolean actionClearDaily) {
            this(title, desc, key, def, restart, clickRow, editKey, actionClearDaily, false, false, false, false, false, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, boolean actionClearDaily, boolean actionChannel) {
            this(title, desc, key, def, restart, clickRow, editKey, actionClearDaily, actionChannel, false, false, false, false, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, boolean actionExport, boolean actionImport) {
            this(title, desc, key, def, restart, clickRow, null, false, false, actionExport, actionImport, false, false, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, boolean actionClearDaily, boolean actionChannel,
                  boolean actionExport, boolean actionImport, boolean actionExportLog, boolean actionRuntimeStatus) {
            this(title, desc, key, def, restart, clickRow, editKey, actionClearDaily, actionChannel,
                    actionExport, actionImport, actionExportLog, actionRuntimeStatus, false, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, boolean actionClearDaily, boolean actionChannel,
                  boolean actionExport, boolean actionImport, boolean actionExportLog,
                  boolean actionRuntimeStatus, boolean actionPickDir) {
            this(title, desc, key, def, restart, clickRow, editKey, actionClearDaily, actionChannel,
                    actionExport, actionImport, actionExportLog, actionRuntimeStatus, actionPickDir, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, boolean actionClearDaily, boolean actionChannel,
                  boolean actionExport, boolean actionImport, boolean actionExportLog,
                  boolean actionRuntimeStatus, boolean actionPickDir, boolean actionDownloadManager) {
            this.title = title;
            this.desc = desc;
            this.key = key;
            this.def = def;
            this.restart = restart;
            this.clickRow = clickRow;
            this.editKey = editKey;
            this.actionClearDaily = actionClearDaily;
            this.actionChannel = actionChannel;
            this.actionExport = actionExport;
            this.actionImport = actionImport;
            this.actionExportLog = actionExportLog;
            this.actionRuntimeStatus = actionRuntimeStatus;
            this.actionPickDir = actionPickDir;
            this.actionDownloadManager = actionDownloadManager;
        }
    }

        private static class SettingsGroup {
        final String title;
        final SwitchDef[] items;
        SettingsGroup(String title, SwitchDef[] items) {
            this.title = title;
            this.items = items;
        }
    }

        private static final SettingsGroup[] BASE_GROUPS = new SettingsGroup[]{
            new SettingsGroup("广告过滤", new SwitchDef[]{
                    new SwitchDef("屏蔽开屏广告", null, App.KEY_OPEN_SCREEN, true, false),
                    new SwitchDef("屏蔽信息流广告", null, App.KEY_FEED_AD, true, false),
                    new SwitchDef("屏蔽气泡广告", null, App.KEY_BUBBLE_AD, true, false),
                    new SwitchDef("屏蔽角标广告", null, App.KEY_CORNER_AD, true, false),
                    new SwitchDef("屏蔽推广贴", null, App.KEY_PROMOTE_AD, true, false),
            }),
            new SettingsGroup("视频下载", new SwitchDef[]{
                    new SwitchDef("下载视频", "在支持的视频上显示下载入口", App.KEY_VIDEO_DOWNLOAD, true, false),
                    new SwitchDef("保存位置", "点击选择保存文件夹", null, false, false, true, null, false, false, false, false, false, false, true),
                    new SwitchDef("转存 MP4", "下载合并后自动转封装为 MP4", App.KEY_VIDEO_TO_MP4, true, false),
            }),
            new SettingsGroup("解除复制", new SwitchDef[]{
                    new SwitchDef("解除复制", "恢复系统标准文本选择", App.KEY_COPY_POST, true, false),
                    new SwitchDef("自绘制文本选择", "用于修复可能的选区错误（需开启「解除复制」）", App.KEY_CUSTOM_TEXT_SELECT, false, false),
                    new SwitchDef("系统分享图片", "在图片长按菜单中打开系统分享", App.KEY_SYSTEM_SHARE, true, false),
            }),
            new SettingsGroup("分享净化", new SwitchDef[]{
                    new SwitchDef("净化分享链接", null, App.KEY_PURIFY_SHARE_LINK, true, false),
            }),
            new SettingsGroup("每日任务", new SwitchDef[]{
                    new SwitchDef("自动完成每日分享任务", null, App.KEY_DAILY_TASK_ENABLED, false, false),
                    new SwitchDef("帖子链接", "任务一：分享任意帖子", null, false, false, true, App.KEY_DAILY_TASK_PICTURE),
                    new SwitchDef("游戏详情链接", "任务二：分享游戏详情", null, false, false, true, App.KEY_DAILY_TASK_NORMAL),
                    new SwitchDef("游戏评价链接", "任务三：分享游戏评价", null, false, false, true, App.KEY_DAILY_TASK_CHANNEL),
                    new SwitchDef("分享渠道", null, App.KEY_SHARE_CHANNEL, false, false, true, null, false, true),
                    new SwitchDef("清除今日打卡", null, null, false, false, true, null, true),
            }),
            new SettingsGroup("通用", new SwitchDef[]{
                    new SwitchDef("伪装通知权限", "让小黑盒认为通知已开启，获得签到加成", App.KEY_FAKE_NOTIFICATION, false, false),
                    new SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
                    new SwitchDef("记录日志", null, App.KEY_LOG, false, false),
                    new SwitchDef("导出日志", null, null, false, false, true, null, false, false, false, false, true, false),
            }),
            new SettingsGroup("配置备份", new SwitchDef[]{
                    new SwitchDef("导出配置", null, null, false, false, true, true, false),
                    new SwitchDef("导入配置", null, null, false, false, true, false, true),
            }),
    };
    private static SettingsGroup buildBottomTabGroup(Activity activity) {
        String home = MainModule.getHeyboxTabLabel(activity, "discover", "发现");
        String store = MainModule.getHeyboxTabLabel(activity, "game_store", "游戏库");
        String bbs = MainModule.getHeyboxTabLabel(activity, "bbs", "社区");
        return new SettingsGroup("底部导航栏隐藏", new SwitchDef[]{
                new SwitchDef("隐藏「" + home + "」", null, App.KEY_HIDE_TAB_HOME, false, true),
                new SwitchDef("隐藏「" + store + "」", null, App.KEY_HIDE_TAB_HOT, false, true),
                new SwitchDef("隐藏「" + bbs + "」", null, App.KEY_HIDE_TAB_GAME, false, true),
                new SwitchDef("隐藏「加号」", null, App.KEY_HIDE_ADD, false, true),
        });
    }

    /** 完整分组列表*/
    private static SettingsGroup[] getSettingsGroups(Activity activity) {
        SettingsGroup[] base = BASE_GROUPS;
        if (BuildFlags.DEBUG) {
            base = withRuntimeStatusGroup(base);
        }
        SettingsGroup[] all = new SettingsGroup[base.length + 1];
        all[0] = buildBottomTabGroup(activity);
        System.arraycopy(base, 0, all, 1, base.length);
        return all;
    }

    /** Debug 构建：往「通用」分组追加「运行状态」行 */
    private static SettingsGroup[] withRuntimeStatusGroup(SettingsGroup[] groups) {
        SettingsGroup[] out = new SettingsGroup[groups.length];
        for (int i = 0; i < groups.length; i++) {
            SettingsGroup g = groups[i];
            if ("通用".equals(g.title)) {
                SwitchDef[] items = new SwitchDef[g.items.length + 1];
                System.arraycopy(g.items, 0, items, 0, g.items.length);
                items[g.items.length] = new SwitchDef(
                        "运行状态", "查看模块运行检查点", null, false, false,
                        true, null, false, false, false, false, false, true);
                out[i] = new SettingsGroup(g.title, items);
            } else {
                out[i] = g;
            }
        }
        return out;
    }
    private void hookSettingsEntry(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl);
            Method setupMethod = findSetupMethod(clazz);
            if (setupMethod == null) {
                // 混淆名全部失效时的兜底：挂生命周期方法，靠重试循环等待列表构建完成
                setupMethod = findLifecycleFallback(clazz);
            }
            if (setupMethod == null) {
                module.logd(Log.ERROR, module.TAG, "✘ 未找到设置页入口方法（G1/L1/onResume 均不可用）");
                return;
            }
            final Class<?> entryClass = clazz;
            module.hook(setupMethod).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thisObj = chain.getThisObject();
                    // 兜底走生命周期方法时可能命中父类实现，仅对设置页 Activity 生效
                    if (thisObj instanceof Activity && entryClass.isInstance(thisObj)) {
                        final Activity activity = (Activity) thisObj;
                        activity.getWindow().getDecorView().post(new Runnable() {
                            @Override
                            public void run() {
                                insertSettingsEntryWithRetry(activity, 0);
                            }
                        });
                    }
                } catch (Throwable t) {
                    module.logd(Log.ERROR, module.TAG, "设置入口插入调度异常", t);
                }
                return result;
            });
            // 内嵌面板导入/导出依赖文件选择结果
            hookActivityResult(clazz);
            module.logd(Log.INFO, module.TAG, "✔ 设置页入口 Hook 已安装 (" + setupMethod.getName() + "+retry)");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 设置页入口 Hook 失败", t);
        }
    }
    /** 入口方法解析：先按已知混淆名快速匹配，跨版本失效后由 {@link #findLifecycleFallback} 兜底 */
    private Method findSetupMethod(Class<?> clazz) {
        for (String name : new String[]{"G1", "L1"}) {
            try {
                return clazz.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    /** 沿继承链找 onResume（框架方法名永不混淆） */
    private Method findLifecycleFallback(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Method m = c.getDeclaredMethod("onResume");
                module.logd(Log.WARN, module.TAG, "设置页入口混淆名失效，回退生命周期 Hook: "
                        + c.getSimpleName() + ".onResume");
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private void hookActivityResult(Class<?> clazz) {
        try {
            Method m = findOnActivityResult(clazz);
            if (m == null) {
                module.logd(Log.WARN, module.TAG, "未找到 onActivityResult，内嵌面板导入/导出不可用");
                return;
            }
            module.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object a0 = chain.getArg(0);
                    Object a1 = chain.getArg(1);
                    Object a2 = chain.getArg(2);
                    int requestCode = a0 instanceof Integer ? (Integer) a0 : 0;
                    int resultCode = a1 instanceof Integer ? (Integer) a1 : 0;
                    Intent data = a2 instanceof Intent ? (Intent) a2 : null;
                    handleEmbeddedPickResult(requestCode, resultCode, data);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "处理文件选择结果异常: " + t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ onActivityResult Hook 已安装 ("
                    + m.getDeclaringClass().getSimpleName() + "." + m.getName() + ")");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "onActivityResult Hook 失败，内嵌面板导入/导出不可用: " + t);
        }
    }

    private Method findOnActivityResult(Class<?> clazz) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod("onActivityResult", int.class, int.class, Intent.class);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** 文件选择结果分发：仅处理内嵌面板的请求码，其余原样放行 */
    private void handleEmbeddedPickResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_SAVE_DIR) {
            handleSaveDirResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_EMBEDDED_EXPORT && requestCode != REQUEST_EMBEDDED_IMPORT
                && requestCode != REQUEST_EMBEDDED_LOG_EXPORT) {
            return;
        }
        PickCallback cb = sPendingPick;
        sPendingPick = null;
        if (cb == null || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        try {
            cb.onResult(data.getData());
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "执行文件选择回调失败: " + t);
        }
    }

    /** 保存位置选择结果：持久化授权并写入配置（空值 = 恢复默认 Movies/BetterHeybox） */
    private void handleSaveDirResult(int resultCode, Intent data) {
        Context context = mSettingsPanel != null && mSettingsPanel.get() != null
                ? ((View) mSettingsPanel.get()).getContext() : null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri treeUri = data.getData();
        try {
            if (context != null) {
                context.getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "持久化保存位置授权失败: " + t);
        }
        HeyboxPrefs.setString(App.KEY_VIDEO_DIR, treeUri.toString());
        String name = context != null ? queryDirDisplayName(context, treeUri) : null;
        Toast.makeText(context, "保存位置已设置：" + (name != null ? name : treeUri),
                Toast.LENGTH_LONG).show();
        LogRecorder.recordEvent("视频保存位置已设置: " + treeUri);
    }

    /** 查询文件夹显示名（查询失败返回 null） */
    private String queryDirDisplayName(Context context, Uri treeUri) {
        try {
            android.database.Cursor c = context.getContentResolver().query(
                    treeUri,
                    new String[]{android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        return c.getString(0);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 「保存位置」行点击：已设置时给「换目录 / 恢复默认」（小黑盒原生弹窗），否则直接打开系统文件夹选择器 */
    private void showSaveDirDialog(final Activity activity) {
        String current = HeyboxPrefs.getString(App.KEY_VIDEO_DIR, null);
        if (current == null || !current.startsWith("content:")) {
            startDirPicker(activity);
            return;
        }
        DexKitResolver.getHeyboxDialogSpec(module, activity, new DexKitResolver.SpecCallback() {
            @Override
            public void onReady(DexKitResolver.HeyboxDialogSpec spec) {
                try {
                    showSaveDirDialogNative(activity, current, spec);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t);
                    showSaveDirDialogFallback(activity, current);
                }
            }

            @Override
            public void onFailed(String reason) {
                module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗解析失败(" + reason + ")，回退系统弹窗");
                showSaveDirDialogFallback(activity, current);
            }
        });
    }

    private void showSaveDirDialogNative(final Activity activity, final String current,
                                         DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        TextView message = new TextView(activity);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        message.setLayoutParams(lp);
        message.setPadding(pad, pad, pad, pad);
        message.setText("当前：" + describeSaveDir(activity, current)
                + "\n\n默认位置为相册 Movies/BetterHeybox");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        try {
            int colorId = activity.getResources().getIdentifier(
                    "color_text_primary_day_night", "color", MainModule.TARGET_PKG);
            if (colorId != 0) {
                message.setTextColor(activity.getResources().getColor(colorId));
            }
        } catch (Throwable ignored) {
        }
        DialogInterface.OnClickListener pick = (d, w) -> {
            d.dismiss();
            startDirPicker(activity);
        };
        DialogInterface.OnClickListener reset = (d, w) -> {
            HeyboxPrefs.setString(App.KEY_VIDEO_DIR, "");
            Toast.makeText(activity, "已恢复默认：Movies/BetterHeybox",
                    Toast.LENGTH_SHORT).show();
            LogRecorder.recordEvent("视频保存位置已恢复默认");
            d.dismiss();
        };
        spec.buildAndShow(activity, "保存位置", message, "选择其他文件夹", pick, "恢复默认", reset);
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗管理保存位置");
    }

    /** 保存位置展示名：优先查 DocumentsProvider 显示名，失败则取 URI 末段，再不行给通用描述 */
    private String describeSaveDir(Activity activity, String current) {
        String name = queryDirDisplayName(activity, Uri.parse(current));
        if (name == null || name.isEmpty()) {
            try {
                String decoded = Uri.decode(current);
                int idx = decoded.lastIndexOf('/');
                if (idx >= 0 && idx < decoded.length() - 1) {
                    name = decoded.substring(idx + 1);
                }
            } catch (Throwable ignored) {
            }
        }
        return name == null || name.isEmpty() ? "已选择的文件夹" : name;
    }

    private void showSaveDirDialogFallback(final Activity activity, final String current) {
        try {
            String name = queryDirDisplayName(activity, Uri.parse(current));
            new AlertDialog.Builder(activity)
                    .setTitle("保存位置")
                    .setMessage("当前：" + (name != null ? name : current)
                            + "\n\n默认位置为相册 Movies/BetterHeybox")
                    .setPositiveButton("选择其他文件夹", (d, w) -> startDirPicker(activity))
                    .setNeutralButton("恢复默认", (d, w) -> {
                        HeyboxPrefs.setString(App.KEY_VIDEO_DIR, "");
                        Toast.makeText(activity, "已恢复默认：Movies/BetterHeybox",
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            startDirPicker(activity);
        }
    }

    /** 调起系统文件夹选择器（SAF），结果经 onActivityResult Hook 回调 */
    private void startDirPicker(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            activity.startActivityForResult(intent, REQUEST_PICK_SAVE_DIR);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开文件夹选择器失败: " + t);
            Toast.makeText(activity, "打开文件夹选择器失败", Toast.LENGTH_SHORT).show();
        }
    }
        private void insertSettingsEntryWithRetry(final Activity activity, final int attempt) {
        if (attempt > 20) {
            module.logd(Log.WARN, module.TAG, "设置页布局迟迟未就绪，放弃插入入口");
            return;
        }
        try {
            boolean ok = tryInsertSettingsEntry(activity);
            if (!ok && !activity.isFinishing()) {
                activity.getWindow().getDecorView().postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        insertSettingsEntryWithRetry(activity, attempt + 1);
                    }
                }, 50);
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "插入设置入口重试异常: " + t);
        }
    }

    private boolean tryInsertSettingsEntry(Activity activity) {
        LogRecorder.setContext(activity);
        HeyboxPrefs.init(activity);
        try {
            Object binding = getGeneralSettingsBinding(activity);
            if (binding == null) {
                return false;
            }
            LinearLayout list = resolveSettingsList(activity, binding);
            if (list == null) {
                return false;
            }
            for (int i = list.getChildCount() - 1; i >= 0; i--) {
                if (ENTRY_TAG.equals(list.getChildAt(i).getTag())) {
                    list.removeViewAt(i);
                }
            }

            View entry = buildEntryCard(activity);
            if (entry == null) {
                return false;
            }
            entry.setTag(ENTRY_TAG);
            entry.setClickable(true);
            entry.setFocusable(true);
            entry.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        showEmbeddedSettings(activity);
                    } catch (Throwable t) {
                        module.logd(Log.ERROR, module.TAG, "渲染内嵌设置界面失败", t);
                        Toast.makeText(activity, "BetterHeybox 内嵌设置加载失败",
                                Toast.LENGTH_SHORT).show();
                    }
                }
            });
            list.addView(entry, 0);
            module.logd(Log.INFO, module.TAG, "✔ 原生 BetterHeybox 入口已作为列表项插入通用设置页顶部");
            return true;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "插入设置入口异常: " + t);
            return false;
        }
    }

    private Object getGeneralSettingsBinding(Activity activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                String typeName = f.getType().getName();
                if ("fi.r0".equals(typeName) || "hi.r0".equals(typeName)) {
                    f.setAccessible(true);
                    return f.get(activity);
                }
            }
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (!isViewBindingShape(f.getType())) {
                    continue;
                }
                f.setAccessible(true);
                Object binding = f.get(activity);
                if (binding != null) {
                    module.logd(Log.INFO, module.TAG,
                            "GeneralSettings binding 已按 ViewBinding 形态解析: " + f.getType().getName());
                    return binding;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "查找 GeneralSettings binding 失败: " + t);
        }
        return null;
    }
    private static boolean isViewBindingShape(Class<?> type) {
        if (type.isInterface() || type.isPrimitive()) {
            return false;
        }
        for (Class<?> itf : type.getInterfaces()) {
            Method[] ms = itf.getDeclaredMethods();
            if (ms.length == 1 && ms[0].getParameterCount() == 0
                    && ms[0].getReturnType() == View.class) {
                return true;
            }
        }
        return false;
    }
    private LinearLayout resolveSettingsList(Activity activity, Object binding) {
        for (Method m : binding.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != LinearLayout.class) {
                continue;
            }
            try {
                Object result = m.invoke(binding);
                if (result instanceof LinearLayout && isViewAttachedUnder((View) result, activity)) {
                    return (LinearLayout) result;
                }
            } catch (Throwable ignored) {
            }
        }
        for (Method m : binding.getClass().getMethods()) {
            if (m.getParameterCount() != 0 || m.getReturnType() != LinearLayout.class) {
                continue;
            }
            try {
                Object result = m.invoke(binding);
                if (result instanceof LinearLayout) {
                    return (LinearLayout) result;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isViewAttachedUnder(View view, Activity activity) {
        try {
            Object decor = activity.getWindow().getDecorView();
            for (ViewParent p = view.getParent(); p instanceof View; p = ((View) p).getParent()) {
                if (p == decor) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void showEmbeddedSettings(final Activity activity) {
        try {
            dismissEmbeddedSettings();
            HeyboxPrefs.init(activity);
            int appbarBg = 0xFFFFFFFF;
            int pageBg = 0xFFFFFFFF;
            int appbarBgId = resId(activity, "appbar_bg_color", "color", 0);
            if (appbarBgId != 0) {
                try {
                    appbarBg = activity.getResources().getColor(appbarBgId);
                } catch (Throwable ignored) {
                }
            }
            int pageBgId = resId(activity, "color_bg_subtle_day_night", "color", 0);
            if (pageBgId != 0) {
                try {
                    pageBg = activity.getResources().getColor(pageBgId);
                } catch (Throwable ignored) {
                }
            }

            int statusBarH = 0;
            try {
                int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) {
                    statusBarH = activity.getResources().getDimensionPixelSize(id);
                }
            } catch (Throwable ignored) {
            }
            if (statusBarH <= 0) {
                statusBarH = module.dp(activity, 24);
            }

            FrameLayout overlay = new FrameLayout(activity);
            overlay.setBackgroundColor(pageBg);
            overlay.setTag(EMBEDDED_SETTINGS_TAG);
            overlay.setClickable(true);
            overlay.setFocusable(true);
            overlay.setFocusableInTouchMode(true);

            LinearLayout page = new LinearLayout(activity);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            overlay.addView(page);
            View statusSpacer = new View(activity);
            statusSpacer.setBackgroundColor(appbarBg);
            statusSpacer.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, statusBarH));
            page.addView(statusSpacer);
            ClassLoader cl = activity.getClassLoader();
            Class<?> titleBarCls = Class.forName("com.max.hbcommon.component.TitleBar", false, cl);
            Object titleBar = titleBarCls.getConstructor(Context.class).newInstance(activity);
            ((View) titleBar).setBackgroundColor(appbarBg);
            titleBarCls.getMethod("setTitle", CharSequence.class).invoke(titleBar, "BetterHeybox 设置");
            titleBarCls.getMethod("setNavigationIcon", int.class)
                    .invoke(titleBar, resId(activity, "appbar_back", "drawable", 0));
            Class<?> ocl = Class.forName("android.view.View$OnClickListener", false, cl);
            Object backListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismissEmbeddedSettings();
                }
            };
            titleBarCls.getMethod("setNavigationOnClickListener", ocl).invoke(titleBar, backListener);
            ((View) titleBar).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, module.dp(activity, 44)));
            page.addView((View) titleBar);
            ScrollView scroller = new ScrollView(activity);
            scroller.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setLayoutParams(new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setPadding(0, module.dp(activity, 2), 0, 0);
            scroller.addView(box);
            page.addView(scroller);

            for (SettingsGroup group : getSettingsGroups(activity)) {
                View card = buildSectionCard(activity, cl, group);
                if (card != null) {
                    box.addView(card);
                }
            }
            try {
                TextView footer = new TextView(activity);
                String moduleVersion = null;
                try {
                    android.content.pm.ApplicationInfo moduleInfo = module.getModuleApplicationInfo();
                    android.content.pm.PackageInfo pkgInfo = activity.getPackageManager()
                            .getPackageArchiveInfo(moduleInfo.sourceDir, 0);
                    if (pkgInfo != null) {
                        moduleVersion = pkgInfo.versionName;
                    }
                } catch (Throwable ignored) {
                }
                String displayVersion = moduleVersion;
                if (displayVersion != null && displayVersion.startsWith("v")) {
                    displayVersion = displayVersion.substring(1);
                }
                footer.setText("BetterHeybox v"
                        + (displayVersion == null ? "unknown" : displayVersion));
                footer.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                footer.setGravity(android.view.Gravity.CENTER);
                int footerColor = 0xFF8A8A8A;
                int footerColorId = resId(activity, "color_text_tertiary_day_night", "color", 0);
                if (footerColorId != 0) {
                    try {
                        footerColor = activity.getResources().getColor(footerColorId);
                    } catch (Throwable ignored) {
                    }
                }
                footer.setTextColor(footerColor);
                LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                int fm = module.dp(activity, 16);
                footerLp.setMargins(fm, module.dp(activity, 12), fm, module.dp(activity, 24));
                footer.setLayoutParams(footerLp);
                box.addView(footer);
                module.logd(Log.INFO, module.TAG, "✔ 内嵌面板底部版本号已添加: "
                        + (displayVersion == null ? "unknown" : displayVersion));
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "内嵌面板版本号页脚渲染失败: " + t);
            }
            overlay.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                        dismissEmbeddedSettings();
                        return true;
                    }
                    return false;
                }
            });

            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(overlay);
            overlay.requestFocus();
            mSettingsPanel = new WeakReference<View>(overlay);
            module.logd(Log.INFO, module.TAG, "✔ 原生子页面设置面板已叠加到小黑盒窗口");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "渲染原生设置面板失败", t);
        }
    }

    private void dismissEmbeddedSettings() {
        try {
            View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
            if (panel != null && panel.getParent() != null) {
                ((ViewGroup) panel.getParent()).removeView(panel);
            }
        } catch (Throwable ignored) {
        }
        mSettingsPanel = null;
    }

    private View buildSectionCard(Activity activity, ClassLoader cl, SettingsGroup group) {
        try {
            LinearLayout groupRoot = new LinearLayout(activity);
            groupRoot.setOrientation(LinearLayout.VERTICAL);
            groupRoot.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView groupTitle = new TextView(activity);
            groupTitle.setText(group.title);
            int titleSize = module.dp(activity, 13);
            groupTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize);
            int titleColor = 0xFF8A8A8A;
            int titleColorId = resId(activity, "color_text_tertiary_day_night", "color", 0);
            if (titleColorId != 0) {
                try {
                    titleColor = activity.getResources().getColor(titleColorId);
                } catch (Throwable ignored) {
                }
            }
            groupTitle.setTextColor(titleColor);
            groupTitle.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int tm = module.dp(activity, 12);
            titleLp.setMargins(tm, module.dp(activity, 16), tm, 0);
            groupTitle.setLayoutParams(titleLp);
            groupRoot.addView(groupTitle);
            Class<?> cardCls = Class.forName("androidx.cardview.widget.CardView", false, cl);
            Object card = cardCls.getConstructor(Context.class).newInstance(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            cardCls.getMethod("setRadius", float.class).invoke(card, 8f * density);
            cardCls.getMethod("setCardElevation", float.class).invoke(card, 0f);
            try {
                cardCls.getMethod("setMaxCardElevation", float.class).invoke(card, 0f);
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = module.dp(activity, 12);
            cardLp.setMargins(m, module.dp(activity, 8), m, 0);
            ((View) card).setLayoutParams(cardLp);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((ViewGroup) card).addView(content);
            for (int i = 0; i < group.items.length; i++) {
                View item = createSettingSwitch(activity, cl, group.items[i]);
                if (item == null) {
                    continue;
                }
                if (i == group.items.length - 1) {
                    try {
                        Class<?> itemCls = Class.forName(
                                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
                        itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, false);
                    } catch (Throwable ignored) {
                    }
                }
                content.addView(item);
            }
            groupRoot.addView((View) card);
            return groupRoot;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "构建设置卡片分区失败: " + t);
            return null;
        }
    }

    private View createSettingSwitch(Activity activity, ClassLoader cl, SwitchDef def) {
        try {
            Class<?> itemCls = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
            Object item = itemCls.getConstructor(Context.class).newInstance(activity);

            itemCls.getMethod("setTitle", String.class).invoke(item, def.title);
            if (def.desc != null) {
                // 标题下方灰色小字介绍：setTitleDesc 只写文本，tvTitleDesc 默认 GONE，
                // 还需打开可见性开关（f(boolean)，混淆名跨版本会变，用探针自动解析）
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, def.desc);
                Method descToggle = resolveDescToggle(itemCls, activity);
                if (descToggle != null) {
                    descToggle.invoke(item, true);
                }
            }
            Class<?> typeEnum = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
            if (def.clickRow) {
                Object arrowType = Enum.valueOf((Class) typeEnum, "Arrow");
                itemCls.getMethod("setRightType", typeEnum).invoke(item, arrowType);
                try {
                    itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
                } catch (Throwable ignored) {
                }
                final String editKey = def.editKey;
                if (def.actionClearDaily) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> {
                                try {
                                    module.clearDailyTaskAndRetry(activity);
                                    Toast.makeText(activity, "已清除今日打卡状态，重新尝试中…",
                                            Toast.LENGTH_SHORT).show();
                                } catch (Throwable t) {
                                    module.logd(Log.ERROR, module.TAG, "清除今日打卡失败", t);
                                }
                            });
                } else if (def.actionChannel) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> showChannelDialog(activity, def));
                } else if (def.actionExport) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> startEmbeddedExport(activity));
                } else if (def.actionImport) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> startEmbeddedImport(activity));
                } else if (def.actionExportLog) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> startEmbeddedLogExport(activity));
                } else if (def.actionPickDir) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> showSaveDirDialog(activity));

                } else if (def.actionRuntimeStatus) {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> showEmbeddedRuntimeStatus(activity));
                } else {
                    itemCls.getMethod("setOnClickListener", View.OnClickListener.class)
                            .invoke(item, (View.OnClickListener) v -> showEditLinkDialog(activity, def.title, editKey));
                }
                int itemH = module.dp(activity, 48);
                ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, itemH));
                return (View) item;
            }
            Object switchType = Enum.valueOf((Class) typeEnum, "SwitchButton");
            itemCls.getMethod("setRightType", typeEnum).invoke(item, switchType);
            try {
                itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
            } catch (Throwable ignored) {
            }
            boolean cur = readEmbeddedBoolean(def.key, def.def);
            itemCls.getMethod("setChecked", boolean.class, boolean.class).invoke(item, cur, false);
            Class<?> listenerCls = Class.forName(
                    "android.widget.CompoundButton$OnCheckedChangeListener", false, cl);
            Object listener = new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    try {
                        if (writeEmbeddedBoolean(activity, def.key, isChecked) && def.restart) {
                            showRestartAppDialog(activity, cl);
                        }
                        // 文本选择相关开关：对已展示的帖子立即重放，无需重启即运行时生效
                        if (App.KEY_CUSTOM_TEXT_SELECT.equals(def.key)
                                || App.KEY_COPY_POST.equals(def.key)) {
                            TextSelectHook.refresh();
                        }
                    } catch (Throwable t) {
                        module.logd(Log.ERROR, module.TAG, "开关监听回调异常: " + def.title, t);
                    }
                }
            };
            itemCls.getMethod("setOnCheckedChangeListener", listenerCls).invoke(item, listener);
            int itemH = module.dp(activity, 48);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            return (View) item;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "创建 SettingItemView 开关失败 (" + def.title + "): " + t);
            return null;
        }
    }
    /** 「标题下描述」可见性开关（SettingItemView.f(boolean)），每进程解析一次 */
    private static Method sDescToggle;
    private static final String DESC_PROBE_TEXT = "BH_DESC_PROBE";

    /**
     * 解析描述可见性开关方法：用一个不挂到窗口的一次性 SettingItemView，
     * 逐个尝试 boolean 单参方法，能把 {@code setTitleDesc} 写入的探针 TextView 点亮的就是它。
     */
    private Method resolveDescToggle(Class<?> itemCls, Activity activity) {
        if (sDescToggle != null) {
            return sDescToggle;
        }
        try {
            Object probe = itemCls.getConstructor(Context.class).newInstance(activity);
            itemCls.getMethod("setTitleDesc", String.class).invoke(probe, DESC_PROBE_TEXT);
            for (Method m : itemCls.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount() != 1
                        || m.getParameterTypes()[0] != boolean.class
                        || m.getReturnType() != void.class) {
                    continue;
                }
                try {
                    m.invoke(probe, true);
                    boolean lit = isProbeDescVisible(probe);
                    m.invoke(probe, false);
                    if (lit) {
                        sDescToggle = m;
                        module.logd(Log.INFO, module.TAG, "desc 可见性开关已解析: " + m.getName() + "(boolean)");
                        return sDescToggle;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 在（未挂载的）视图树中查找探针文本 TextView 是否可见 */
    private static boolean isProbeDescVisible(Object root) {
        if (!(root instanceof View)) {
            return false;
        }
        if (root instanceof TextView
                && DESC_PROBE_TEXT.equals(((TextView) root).getText().toString())) {
            return ((TextView) root).getVisibility() == View.VISIBLE;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isProbeDescVisible(vg.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private int resId(Activity activity, String name, String type, int fallback) {
        try {
            int id = activity.getResources().getIdentifier(name, type, MainModule.TARGET_PKG);
            return id != 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void showChannelDialog(final Activity activity, final SwitchDef def) {
        DexKitResolver.getHeyboxDialogSpec(module, activity, new DexKitResolver.SpecCallback() {
            @Override
            public void onReady(DexKitResolver.HeyboxDialogSpec spec) {
                try {
                    showChannelDialogNative(activity, spec);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t);
                    showChannelDialogFallback(activity, def);
                }
            }

            @Override
            public void onFailed(String reason) {
                module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗解析失败(" + reason + ")，回退系统弹窗");
                showChannelDialogFallback(activity, def);
            }
        });
    }

    private void showChannelDialogNative(final Activity activity, DexKitResolver.HeyboxDialogSpec spec)
            throws Exception {
        final String[] channels = {"QQ", "WECHAT", "WEIBO"};
        final String[] labels = {"QQ / QQ空间", "微信 / 朋友圈", "微博"};
        String cur = module.getString(App.KEY_SHARE_CHANNEL, "QQ");
        final int checked = "WECHAT".equals(cur) ? 1 : ("WEIBO".equals(cur) ? 2 : 0);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = module.dp(activity, 8);
        list.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView row = new TextView(activity);
            row.setText(labels[i]);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, module.dp(activity, 14), pad, module.dp(activity, 14));
            int rowColor = index == checked ? 0xFF1677FF : 0xFF333333;
            try {
                int colorId = activity.getResources().getIdentifier(
                        index == checked ? "color_text_link_day_night" : "color_text_primary_day_night",
                        "color", MainModule.TARGET_PKG);
                if (colorId != 0) {
                    rowColor = activity.getResources().getColor(colorId);
                }
            } catch (Throwable ignored) {
            }
            row.setTextColor(rowColor);
            row.setClickable(true);
            row.setFocusable(true);
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Dialog dialog = spec.buildAndShow(activity, "分享渠道", list, null, null,
                "取消", (d, w) -> d.dismiss());
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            View row = list.getChildAt(i);
            row.setOnClickListener(v -> {
                try {
                    HeyboxPrefs.init(activity);
                    HeyboxPrefs.setString(App.KEY_SHARE_CHANNEL, channels[index]);
                    LogRecorder.recordEvent("分享渠道已选择: " + channels[index]);
                    Toast.makeText(activity, "分享渠道已设为 " + labels[index],
                            Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "保存分享渠道失败: " + t);
                }
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) {
                }
            });
        }
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗选择分享渠道");
    }
    private void showChannelDialogFallback(final Activity activity, final SwitchDef def) {
        final String[] channels = {"QQ", "WECHAT", "WEIBO"};
        final String[] labels = {"QQ / QQ空间", "微信 / 朋友圈", "微博"};
        String cur = module.getString(App.KEY_SHARE_CHANNEL, "QQ");
        int checked = "WECHAT".equals(cur) ? 1 : ("WEIBO".equals(cur) ? 2 : 0);
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("分享渠道")
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        try {
                            HeyboxPrefs.init(activity);
                            HeyboxPrefs.setString(App.KEY_SHARE_CHANNEL, channels[which]);
                            LogRecorder.recordEvent("分享渠道已选择: " + channels[which]);
                            Toast.makeText(activity, "分享渠道已设为 " + labels[which],
                                    Toast.LENGTH_SHORT).show();
                        } catch (Throwable t) {
                            module.logd(Log.WARN, module.TAG, "保存分享渠道失败: " + t);
                        }
                        dialog.dismiss();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "分享渠道选择弹框失败: " + t);
        }
    }

    private void showEditLinkDialog(final Activity activity, final String title, final String key) {
        DexKitResolver.getHeyboxDialogSpec(module, activity, new DexKitResolver.SpecCallback() {
            @Override
            public void onReady(DexKitResolver.HeyboxDialogSpec spec) {
                try {
                    showEditLinkDialogNative(activity, title, key, spec);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t);
                    showEditLinkDialogFallback(activity, title, key);
                }
            }

            @Override
            public void onFailed(String reason) {
                module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗解析失败(" + reason + ")，回退系统弹窗");
                showEditLinkDialogFallback(activity, title, key);
            }
        });
    }

    private void showEditLinkDialogNative(final Activity activity, final String title, final String key,
                                          DexKitResolver.HeyboxDialogSpec spec) throws Exception {
        final EditText input = new EditText(activity);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        input.setLayoutParams(lp);
        input.setPadding(pad, pad, pad, pad);
        input.setGravity(Gravity.CENTER_VERTICAL);
        try {
            int bgId = activity.getResources().getIdentifier(
                    "bg_dialog_edit", "drawable", MainModule.TARGET_PKG);
            if (bgId != 0) {
                input.setBackgroundResource(bgId);
            }
        } catch (Throwable ignored) {
        }
        try {
            int colorId = activity.getResources().getIdentifier(
                    "color_text_primary_day_night", "color", MainModule.TARGET_PKG);
            if (colorId != 0) {
                input.setTextColor(activity.getResources().getColor(colorId));
            }
        } catch (Throwable ignored) {
        }
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456");
        String cur = HeyboxPrefs.getString(key, "");
        input.setText(cur == null ? "" : cur);
        input.setSelection(input.getText().length());
        DialogInterface.OnClickListener saveListener = (d, w) -> {
            try {
                HeyboxPrefs.setString(key, input.getText().toString().trim());
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
                module.logd(Log.INFO, module.TAG, "分享链接已保存: " + key);
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "保存分享链接失败: " + t);
            }
            d.dismiss();
        };
        spec.buildAndShow(activity, title, input, "保存", saveListener, "取消", (d, w) -> d.dismiss());
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗编辑链接: " + key);
    }
    private void showEditLinkDialogFallback(final Activity activity, final String title, final String key) {
        try {
            final EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setSingleLine(true);
            input.setHint("例如：https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456");
            String cur = HeyboxPrefs.getString(key, "");
            input.setText(cur == null ? "" : cur);
            input.setSelection(input.getText().length());
            new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(input)
                    .setPositiveButton("保存", (dialog, which) -> {
                        try {
                            HeyboxPrefs.setString(key, input.getText().toString().trim());
                            Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
                            module.logd(Log.INFO, module.TAG, "分享链接已保存: " + key);
                        } catch (Throwable t) {
                            module.logd(Log.WARN, module.TAG, "保存分享链接失败: " + t);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "打开链接编辑框失败: " + t);
        }
    }

    private void showRestartAppDialog(Activity activity, ClassLoader cl) {
        try {
            Class<?> ktCls = Class.forName(
                    "com.max.xiaoheihe.accelworld.AccelWorldWebkitKt", false, cl);
            Method x = ktCls.getDeclaredMethod("x", Context.class, String.class);
            x.invoke(null, activity, "底栏改动需重启小黑盒后生效");
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "复用小黑盒重启 Dialog 失败，回退系统 AlertDialog: " + t);
            try {
                new AlertDialog.Builder(activity)
                        .setTitle("重新启动APP生效")
                        .setMessage("底栏改动需重启小黑盒后生效")
                        .setPositiveButton("我知道了", null)
                        .show();
            } catch (Throwable t2) {
                module.logd(Log.ERROR, module.TAG, "回退弹窗也失败", t2);
            }
        }
    }

    /** 内嵌面板导出配置：打开系统「保存到」选择器（免存储权限），结果经 onActivityResult Hook 回调写入 */
    private void startEmbeddedExport(final Activity activity) {
        try {
            // 导出的值 = 当前生效值（本地 HeyboxPrefs 优先，其次 RemotePreferences），与模块设置页文件格式一致
            String json = ConfigBackup.buildJson(module::isEnabled, module::getString);
            if (json == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            final String content = json;
            sPendingPick = uri -> writeEmbeddedExport(activity, uri, content);
            String fileName = "BetterHeybox配置_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".json";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_EXPORT);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开导出选择器失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeEmbeddedExport(Activity activity, Uri uri, String json) {
        try {
            ContentResolver resolver = activity.getContentResolver();
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream out = os) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            LogRecorder.recordEvent("内嵌面板配置已导出: " + uri);
            Toast.makeText(activity, "配置已导出", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "写入导出文件失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void startEmbeddedLogExport(final Activity activity) {
        String logPath = LogRecorder.getLogFilePath();
        File logFile = logPath != null ? new File(logPath) : null;
        if (logFile == null || !logFile.exists() || logFile.length() == 0) {
            Toast.makeText(activity, "暂无日志文件：请先开启「记录日志」，再打开一次小黑盒，然后回来导出",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            sPendingPick = uri -> writeEmbeddedLogExport(activity, uri);
            String fileName = "BetterHeybox日志_" + new SimpleDateFormat("yyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".txt";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            activity.startActivityForResult(intent, REQUEST_EMBEDDED_LOG_EXPORT);
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "打开日志导出选择器失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void writeEmbeddedLogExport(Activity activity, Uri uri) {
        try {
            String content = LogExport.buildExportText(activity);
            ContentResolver resolver = activity.getContentResolver();
            OutputStream os = resolver.openOutputStream(uri);
            if (os == null) {
                Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            try (OutputStream out = os) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            LogRecorder.recordEvent("内嵌面板日志已导出: " + uri);
            Toast.makeText(activity, "日志已导出", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "写入日志导出文件失败: " + t);
            Toast.makeText(activity, "导出失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void showEmbeddedRuntimeStatus(Activity activity) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("构建类型: ").append(BuildFlags.DEBUG ? "debug" : "release").append('\n');
            sb.append('\n').append("—— 本进程（小黑盒）运行检查点 ——\n")
                    .append(Checkpoint.dump(150));
            new AlertDialog.Builder(activity)
                    .setTitle("运行状态")
                    .setMessage(sb.toString())
                    .setPositiveButton("确定", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "运行状态弹窗失败: " + t);
        }
    }
    private void startEmbeddedImport(final Activity activity) {
        DexKitResolver.getHeyboxDialogSpec(module, activity, new DexKitResolver.SpecCallback() {
            @Override
            public void onReady(DexKitResolver.HeyboxDialogSpec spec) {
                try {
                    startEmbeddedImportNative(activity, spec);
                } catch (Throwable t) {
                    module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t);
                    showEmbeddedImportConfirmFallback(activity);
                }
            }

            @Override
            public void onFailed(String reason) {
                module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗解析失败(" + reason + ")，回退系统弹窗");
                showEmbeddedImportConfirmFallback(activity);
            }
        });
    }

    private void startEmbeddedImportNative(final Activity activity, DexKitResolver.HeyboxDialogSpec spec)
            throws Exception {
        TextView message = new TextView(activity);
        int pad = module.dp(activity, 10);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, pad, 0, pad * 2);
        message.setLayoutParams(lp);
        message.setPadding(pad, pad, pad, pad);
        message.setText("导入将覆盖当前所有设置（开关、分享链接、分享渠道等），确定继续？");
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        try {
            int colorId = activity.getResources().getIdentifier(
                    "color_text_primary_day_night", "color", MainModule.TARGET_PKG);
            if (colorId != 0) {
                message.setTextColor(activity.getResources().getColor(colorId));
            }
        } catch (Throwable ignored) {
        }
        DialogInterface.OnClickListener importListener = (d, w) -> {
            try {
                sPendingPick = uri -> readEmbeddedImport(activity, uri);
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/json");
                activity.startActivityForResult(intent, REQUEST_EMBEDDED_IMPORT);
            } catch (Throwable t) {
                module.logd(Log.ERROR, module.TAG, "打开导入选择器失败: " + t);
                Toast.makeText(activity, "导入失败，请重试", Toast.LENGTH_SHORT).show();
            }
            d.dismiss();
        };
        spec.buildAndShow(activity, "导入配置", message, "导入", importListener,
                "取消", (d, w) -> d.dismiss());
        module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗确认导入配置");
    }

    private void showEmbeddedImportConfirmFallback(final Activity activity) {
        try {
            new AlertDialog.Builder(activity)
                    .setTitle("导入配置")
                    .setMessage("导入将覆盖当前所有设置（开关、分享链接、分享渠道等），确定继续？")
                    .setPositiveButton("导入", (dialog, which) -> {
                        try {
                            sPendingPick = uri -> readEmbeddedImport(activity, uri);
                            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("application/json");
                            activity.startActivityForResult(intent, REQUEST_EMBEDDED_IMPORT);
                        } catch (Throwable t) {
                            module.logd(Log.ERROR, module.TAG, "打开导入选择器失败: " + t);
                            Toast.makeText(activity, "导入失败，请重试", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "导入确认弹框失败: " + t);
        }
    }

    private void readEmbeddedImport(final Activity activity, Uri uri) {
        try {
            ContentResolver resolver = activity.getContentResolver();
            InputStream is = resolver.openInputStream(uri);
            if (is == null) {
                Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show();
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = is) {
                byte[] chunk = new byte[8192];
                int len;
                while ((len = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, len);
                }
            }
            String json = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            HeyboxPrefs.init(activity);
            ConfigBackup.ApplyResult result = ConfigBackup.applyJson(
                    json,
                    (key, value) -> writeEmbeddedBoolean(activity, key, value),
                    (key, value) -> HeyboxPrefs.setString(key, value));
            if (result == null) {
                Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show();
                return;
            }
            LogRecorder.recordEvent("内嵌面板配置已导入: " + result.applied + " 项, uri=" + uri);
            Toast.makeText(activity, "配置已导入（" + result.applied + " 项）", Toast.LENGTH_SHORT).show();
            View panel = mSettingsPanel == null ? null : mSettingsPanel.get();
            if (panel != null && panel.getParent() != null) {
                showEmbeddedSettings(activity);
            }
            if (result.restartRequired) {
                showRestartAppDialog(activity, activity.getClassLoader());
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "读取导入文件失败: " + t);
            Toast.makeText(activity, "导入失败：文件格式无效或已损坏", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean readEmbeddedBoolean(String key, boolean defaultValue) {
        return module.isEnabled(key, defaultValue);
    }

    private boolean writeEmbeddedBoolean(Activity activity, String key, boolean value) {
        LogRecorder.setContext(activity);
        HeyboxPrefs.init(activity);
        module.logd(Log.INFO, module.TAG, "设置写入开始: key=" + key + ", value=" + value
                + ", pid=" + android.os.Process.myPid());
        boolean localOk = HeyboxPrefs.setBoolean(key, value);
        module.logd(Log.INFO, module.TAG, "本地配置写入: key=" + key + ", value=" + value + ", ok=" + localOk);
        LogRecorder.recordEvent("内嵌面板开关已写入小黑盒本地配置: key=" + key
                + ", value=" + value + ", ok=" + localOk);
        try {
            Intent request = new Intent(PreferenceReceiver.ACTION_SET_BOOLEAN)
                    .setComponent(new android.content.ComponentName(
                            "com.better.heybox", "com.better.heybox.PreferenceReceiver"))
                    .putExtra(PreferenceReceiver.EXTRA_KEY, key)
                    .putExtra(PreferenceReceiver.EXTRA_VALUE, value)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            activity.sendBroadcast(request);
            module.logd(Log.INFO, module.TAG, "远程镜像广播已发送: key=" + key + ", value=" + value);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "远程镜像广播失败（本地配置已生效，不影响使用）: " + key, t);
        }
        // 文本选择相关开关（含配置导入路径）：对已展示的帖子立即重放，运行时生效
        if (App.KEY_CUSTOM_TEXT_SELECT.equals(key) || App.KEY_COPY_POST.equals(key)) {
            TextSelectHook.refresh();
        }
        return localOk;
    }

    /* ==== 下载管理页复用的宿主组件工厂（包内可见） ==== */

    /** 解析宿主资源 id */
    static int hostResId(Context context, String name, String type, int fallback) {
        try {
            int id = context.getResources().getIdentifier(name, type, MainModule.TARGET_PKG);
            return id != 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 解析宿主 day_night 颜色资源（跟随深浅色） */
    static int hostColor(Context context, String name, int fallback) {
        int id = hostResId(context, name, "color", 0);
        if (id != 0) {
            try {
                return context.getColor(id);
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    /**
     * 创建宿主卡片（8dp 圆角、无阴影），返回卡片本体；行内容通过
     * {@code ((ViewGroup) card.getTag())} 取出后 add 进去（content 已挂进卡片，
     * 直接返回 content 会让调用方二次挂载时触发 child already has a parent）。
     */
    static ViewGroup hostCard(Context context) {
        try {
            ClassLoader cl = context.getClassLoader();
            Class<?> cardCls = Class.forName("androidx.cardview.widget.CardView", false, cl);
            Object card = cardCls.getConstructor(Context.class).newInstance(context);
            float density = context.getResources().getDisplayMetrics().density;
            cardCls.getMethod("setRadius", float.class).invoke(card, 8f * density);
            cardCls.getMethod("setCardElevation", float.class).invoke(card, 0f);
            try {
                cardCls.getMethod("setMaxCardElevation", float.class).invoke(card, 0f);
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = ThemeUtils.dp(context, 12);
            cardLp.setMargins(m, ThemeUtils.dp(context, 8), m, 0);
            ((View) card).setLayoutParams(cardLp);
            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((ViewGroup) card).addView(content);
            content.setTag(card);
            return (ViewGroup) card;
        } catch (Throwable t) {
            // 宿主组件不可用：退化为普通容器（自挂载，行为与上面一致）
            LinearLayout fallback = new LinearLayout(context);
            fallback.setOrientation(LinearLayout.VERTICAL);
            fallback.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fallback.setTag(fallback);
            return fallback;
        }
    }

    /** 创建宿主设置行（Text 型：标题 + 描述 + 右侧状态文本） */
    static View hostTextItem(Context context, String title, String titleDesc,
                             String rightDesc, boolean divider) throws Throwable {
        ClassLoader cl = context.getClassLoader();
        Class<?> itemCls = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
        Object item = itemCls.getConstructor(Context.class).newInstance(context);
        itemCls.getMethod("setTitle", String.class).invoke(item, title);
        if (titleDesc != null) {
            itemCls.getMethod("setTitleDesc", String.class).invoke(item, titleDesc);
        }
        Class<?> typeEnum = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
        itemCls.getMethod("setRightType", typeEnum)
                .invoke(item, Enum.valueOf((Class) typeEnum, "Text"));
        if (rightDesc != null) {
            try {
                itemCls.getMethod("setRightDesc", String.class).invoke(item, rightDesc);
            } catch (Throwable ignored) {
            }
        }
        itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, divider);
        int itemH = ThemeUtils.dp(context, titleDesc != null ? 58 : 48);
        ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemH));
        return (View) item;
    }

    /** 原地更新 Text 型行（不重建 View，保证点击不因列表刷新而丢失） */
    static void updateTextItem(View row, String title, String titleDesc, String rightDesc) {
        try {
            Class<?> cls = row.getClass();
            cls.getMethod("setTitle", String.class).invoke(row, title);
            if (titleDesc != null) {
                cls.getMethod("setTitleDesc", String.class).invoke(row, titleDesc);
            }
            if (rightDesc != null) {
                try {
                    cls.getMethod("setRightDesc", String.class).invoke(row, rightDesc);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 原地更新 CheckBox 型行的勾选态 */
    static void updateCheckItem(View row, boolean checked) {
        try {
            row.getClass()
                    .getMethod("setCheckBoxChecked", boolean.class, boolean.class)
                    .invoke(row, checked, false);
        } catch (Throwable ignored) {
        }
    }

    /** 创建宿主多选行（CheckBox 型），checked 为当前勾选态 */
    static View hostCheckItem(Context context, String title, String titleDesc, boolean checked,
                              CompoundButton.OnCheckedChangeListener listener, boolean divider)
            throws Throwable {
        ClassLoader cl = context.getClassLoader();
        Class<?> itemCls = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
        Object item = itemCls.getConstructor(Context.class).newInstance(context);
        itemCls.getMethod("setTitle", String.class).invoke(item, title);
        if (titleDesc != null) {
            itemCls.getMethod("setTitleDesc", String.class).invoke(item, titleDesc);
        }
        Class<?> typeEnum = Class.forName(
                "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
        itemCls.getMethod("setRightType", typeEnum)
                .invoke(item, Enum.valueOf((Class) typeEnum, "CheckBox"));
        itemCls.getMethod("setCheckBoxChecked", boolean.class, boolean.class)
                .invoke(item, checked, false);
        Class<?> listenerCls = Class.forName(
                "android.widget.CompoundButton$OnCheckedChangeListener", false, cl);
        itemCls.getMethod("setOnCheckButtonCheckedChangeListener", listenerCls)
                .invoke(item, listener);
        itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, divider);
        int itemH = ThemeUtils.dp(context, titleDesc != null ? 58 : 48);
        ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, itemH));
        return (View) item;
    }

    private View buildEntryCard(final Activity activity) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> cardCls = Class.forName("androidx.cardview.widget.CardView", false, cl);
            Object card = cardCls.getConstructor(Context.class).newInstance(activity);
            float density = activity.getResources().getDisplayMetrics().density;
            cardCls.getMethod("setRadius", float.class).invoke(card, 8f * density);
            cardCls.getMethod("setCardElevation", float.class).invoke(card, 0f);
            try {
                cardCls.getMethod("setMaxCardElevation", float.class).invoke(card, 0f);
            } catch (Throwable ignored) {
            }
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = module.dp(activity, 12);
            cardLp.setMargins(m, module.dp(activity, 8), m, 0);
            ((View) card).setLayoutParams(cardLp);
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((ViewGroup) card).addView(content);
            Class<?> itemCls = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView", false, cl);
            Object item = itemCls.getConstructor(Context.class).newInstance(activity);
            itemCls.getMethod("setTitle", String.class).invoke(item, "BetterHeybox 设置");
            try {
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, "广告过滤与界面增强");
            } catch (Throwable ignored) {
            }
            Class<?> typeEnum = Class.forName(
                    "com.max.xiaoheihe.module.account.component.SettingItemView$Type", false, cl);
            Object arrow = Enum.valueOf((Class) typeEnum, "Arrow");
            itemCls.getMethod("setRightType", typeEnum).invoke(item, arrow);
            try {
                itemCls.getMethod("setShowBottomDivider", boolean.class).invoke(item, true);
            } catch (Throwable ignored) {
            }
            int itemH = module.dp(activity, 48);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            content.addView((View) item);
            return (View) card;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "构建原生入口卡片失败: " + t);
            return null;
        }
    }
}
