package com.better.heybox.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.HeyboxPrefs;
import com.better.heybox.LogRecorder;
import com.better.heybox.MainModule;
import com.better.heybox.PreferenceReceiver;

/**
 * 小黑盒设置页入口注入 + 内嵌原生风格设置面板（TitleBar + 分组卡片 + SettingItemView 开关，深浅色跟随小黑盒主题）。
 */
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
    private WeakReference<View> mSettingsPanel;

        private static class SwitchDef {
        final String title;
        final String desc;
        final String key;
        final boolean def;
        final boolean restart;
        final boolean clickRow;
        final String editKey; // clickRow 时编辑的字符串配置 key（null 则不弹编辑框）
        final boolean actionClearDaily;
        SwitchDef(String title, String desc, String key, boolean def, boolean restart) {
            this(title, desc, key, def, restart, false, null);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart, boolean clickRow) {
            this(title, desc, key, def, restart, clickRow, null);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart, boolean clickRow, String editKey) {
            this(title, desc, key, def, restart, clickRow, editKey, false);
        }
        SwitchDef(String title, String desc, String key, boolean def, boolean restart,
                  boolean clickRow, String editKey, boolean actionClearDaily) {
            this.title = title;
            this.desc = desc;
            this.key = key;
            this.def = def;
            this.restart = restart;
            this.clickRow = clickRow;
            this.editKey = editKey;
            this.actionClearDaily = actionClearDaily;
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
            new SettingsGroup("解除复制", new SwitchDef[]{
                    new SwitchDef("解除复制", "恢复系统标准文本选择", App.KEY_COPY_POST, true, false),
                    new SwitchDef("系统分享图片", "在图片长按菜单中打开系统分享", App.KEY_SYSTEM_SHARE, true, false),
            }),
            new SettingsGroup("每日任务", new SwitchDef[]{
                    new SwitchDef("自动完成每日分享任务", "自动完成 3 种分享任务：分享任意帖子 / 分享游戏详情 / 分享游戏评价（不拦截 QQ 分享）", App.KEY_DAILY_TASK_ENABLED, false, false),
                    new SwitchDef("帖子链接", "任务一：分享任意帖子", null, false, false, true, App.KEY_DAILY_TASK_PICTURE),
                    new SwitchDef("游戏详情链接", "任务二：分享游戏详情", null, false, false, true, App.KEY_DAILY_TASK_NORMAL),
                    new SwitchDef("游戏评价链接", "任务三：分享游戏评价", null, false, false, true, App.KEY_DAILY_TASK_CHANNEL),
                    new SwitchDef("清除今日打卡", "清除今日已完成状态，立即重新尝试打卡（失败后用于重试）", null, false, false, true, null, true),
            }),
            new SettingsGroup("通用", new SwitchDef[]{
                    new SwitchDef("伪装通知权限", "让小黑盒认为通知已开启，获得签到加成（不真正申请权限）", App.KEY_FAKE_NOTIFICATION, false, false),
                    new SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
                    new SwitchDef("记录日志", "开启后自动记录模块日志到文件", App.KEY_LOG, false, false),
            }),
    };

    /** 底部导航栏隐藏分组：tab 名称按小黑盒字符串资源动态解析（版本自适应：发现/游戏库/社区/加号） */
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

    /** 完整分组列表（含动态底栏组） */
    private static SettingsGroup[] getSettingsGroups(Activity activity) {
        SettingsGroup[] base = BASE_GROUPS;
        SettingsGroup[] all = new SettingsGroup[base.length + 1];
        all[0] = buildBottomTabGroup(activity);
        System.arraycopy(base, 0, all, 1, base.length);
        return all;
    }
    private void hookSettingsEntry(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl);
            Method setupMethod;
            try {
                setupMethod = clazz.getDeclaredMethod("G1");
            } catch (NoSuchMethodException ignored) {
                setupMethod = clazz.getDeclaredMethod("L1");
            }
            module.hook(setupMethod).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thisObj = chain.getThisObject();
                    if (thisObj instanceof Activity) {
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
            module.logd(Log.INFO, module.TAG, "✔ 设置页入口 Hook 已安装 (" + setupMethod.getName() + "+retry)");
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 设置页入口 Hook 失败", t);
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
            Object listObj = binding.getClass().getMethod("b").invoke(binding);
            if (!(listObj instanceof LinearLayout)) {
                return false;
            }
            LinearLayout list = (LinearLayout) listObj;
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
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "查找 GeneralSettings binding 失败: " + t);
        }
        return null;
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
                itemCls.getMethod("setTitleDesc", String.class).invoke(item, def.desc);
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
    private int resId(Activity activity, String name, String type, int fallback) {
        try {
            int id = activity.getResources().getIdentifier(name, type, MainModule.TARGET_PKG);
            return id != 0 ? id : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 关闭原生弹窗：按钮回调参数 [0] 为 DialogInterface，小黑盒 HeyBoxDialog 需手动 dismiss */
    private static void dismissDialog(Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        try {
            if (args[0] instanceof DialogInterface) {
                ((DialogInterface) args[0]).dismiss();
            }
        } catch (Throwable ignored) {
        }
    }

    private void showEditLinkDialog(final Activity activity, final String title, final String key) {
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> dialogCls = Class.forName("com.max.hbcommon.view.d", false, cl);
            Class<?> builderCls = Class.forName("com.max.hbcommon.view.d$i", false, cl);
            Object builder = builderCls.getConstructor(Context.class).newInstance(activity);
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
            builderCls.getMethod("B", CharSequence.class).invoke(builder, title);
            builderCls.getMethod("i", View.class).invoke(builder, input);
            Class<?> onClickCls = DialogInterface.OnClickListener.class;
            Object saveListener = java.lang.reflect.Proxy.newProxyInstance(
                    cl, new Class<?>[]{onClickCls}, (proxy, method, args) -> {
                        if ("onClick".equals(method.getName())) {
                            try {
                                HeyboxPrefs.setString(key, input.getText().toString().trim());
                                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show();
                                module.logd(Log.INFO, module.TAG, "分享链接已保存: " + key);
                            } catch (Throwable t) {
                                module.logd(Log.WARN, module.TAG, "保存分享链接失败: " + t);
                            }
                            dismissDialog(args);
                        }
                        return null;
                    });
            Object cancelListener = java.lang.reflect.Proxy.newProxyInstance(
                    cl, new Class<?>[]{onClickCls}, (proxy, method, args) -> {
                        if ("onClick".equals(method.getName())) {
                            dismissDialog(args);
                        }
                        return null;
                    });
            builderCls.getMethod("x", CharSequence.class, onClickCls).invoke(builder, "保存", saveListener);
            builderCls.getMethod("r", CharSequence.class, onClickCls).invoke(builder, "取消", cancelListener);
            builderCls.getMethod("J").invoke(builder);
            module.logd(Log.INFO, module.TAG, "✔ 使用小黑盒原生弹窗编辑链接: " + key);
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "小黑盒原生弹窗不可用，回退系统弹窗: " + t);
            showEditLinkDialogFallback(activity, title, key);
        }
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
        return localOk;
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
