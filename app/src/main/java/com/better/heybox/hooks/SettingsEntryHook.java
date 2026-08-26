package com.better.heybox.hooks;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
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
        SwitchDef(String title, String desc, String key, boolean def, boolean restart) {
            this.title = title;
            this.desc = desc;
            this.key = key;
            this.def = def;
            this.restart = restart;
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

        private static final SettingsGroup[] SETTINGS_GROUPS = new SettingsGroup[]{
            new SettingsGroup("广告过滤", new SwitchDef[]{
                    new SwitchDef("屏蔽开屏广告", null, App.KEY_OPEN_SCREEN, true, false),
                    new SwitchDef("屏蔽信息流广告", null, App.KEY_FEED_AD, true, false),
                    new SwitchDef("屏蔽气泡广告", null, App.KEY_BUBBLE_AD, true, false),
                    new SwitchDef("屏蔽角标广告", null, App.KEY_CORNER_AD, true, false),
                    new SwitchDef("屏蔽推广贴", null, App.KEY_PROMOTE_AD, true, false),
            }),
            new SettingsGroup("底部导航栏隐藏", new SwitchDef[]{
                    new SwitchDef("隐藏首页", null, App.KEY_HIDE_TAB_HOME, false, true),
                    new SwitchDef("隐藏热点", null, App.KEY_HIDE_TAB_HOT, false, true),
                    new SwitchDef("隐藏游戏库", null, App.KEY_HIDE_TAB_GAME, false, true),
                    new SwitchDef("隐藏加号", null, App.KEY_HIDE_ADD, false, true),
            }),
            new SettingsGroup("解除复制", new SwitchDef[]{
                    new SwitchDef("解除复制", "恢复系统标准文本选择", App.KEY_COPY_POST, true, false),
                    new SwitchDef("系统分享图片", "在图片长按菜单中打开系统分享", App.KEY_SYSTEM_SHARE, true, false),
            }),
            new SettingsGroup("通用", new SwitchDef[]{
                    new SwitchDef("屏蔽更新", "屏蔽小黑盒更新入口", App.KEY_BLOCK_UPDATE, false, false),
                    new SwitchDef("记录日志", "开启后自动记录模块日志到文件", App.KEY_LOG, false, false),
            }),
    };

    /**
     * 向小黑盒通用设置页（GeneralSettingsActivity）注入 BetterHeybox 入口。
     * 在 G1（onCreate 模板，每次进页恰好触发一次）返回后插入，此时布局已 setContentView。
     * 注入不能同步做：binding 字段与列表容器此时未必就绪（经验证会破坏设置项），
     * 故 post 到下一帧再试，未就绪则由 insertSettingsEntryWithRetry 短间隔重试。
     */
    private void hookSettingsEntry(ClassLoader cl) {
        try {
            // 通用设置页：GeneralSettingsActivity，ViewBinding = fi.r0（ActivityGeneralSettingsBinding）
            Class<?> clazz = Class.forName("com.max.xiaoheihe.module.account.GeneralSettingsActivity", false, cl);
            Method g1 = clazz.getDeclaredMethod("G1");
            module.hook(g1).intercept(chain -> {
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
            module.logd(Log.INFO, module.TAG, "✔ 设置页入口 Hook 已安装 (G1+retry)");
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

    /**
     * 尝试在通用设置页面插入自定义设置入口
     * @param activity 当前活动实例
     * @return 插入成功返回true，失败返回false
     */
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
                if ("fi.r0".equals(f.getType().getName())) {
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
            try {
                appbarBg = activity.getResources().getColor(0x7f060022);
            } catch (Throwable ignored) {
            }
            try {
                pageBg = activity.getResources().getColor(0x7f0600b9);
            } catch (Throwable ignored) {
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
            titleBarCls.getMethod("setNavigationIcon", int.class).invoke(titleBar, 0x7f08009b);
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

            for (SettingsGroup group : SETTINGS_GROUPS) {
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
                try {
                    footerColor = activity.getResources().getColor(0x7f06013a);
                } catch (Throwable ignored) {
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
            int titleSize;
            try {
                titleSize = activity.getResources().getDimensionPixelSize(0x7f07039b);
            } catch (Throwable ignored) {
                titleSize = module.dp(activity, 13);
            }
            groupTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, titleSize);
            int titleColor = 0xFF8A8A8A;
            try {
                titleColor = activity.getResources().getColor(0x7f06013a);
            } catch (Throwable ignored) {
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
            int itemH = activity.getResources().getDimensionPixelSize(0x7f0700ff);
            ((View) item).setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, itemH));
            return (View) item;
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "创建 SettingItemView 开关失败 (" + def.title + "): " + t);
            return null;
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
            int itemH = activity.getResources().getDimensionPixelSize(0x7f0700ff);
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
