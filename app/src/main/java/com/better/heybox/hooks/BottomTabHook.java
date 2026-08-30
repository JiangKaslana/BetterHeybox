package com.better.heybox.hooks;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;
import com.better.heybox.liquidglass.LiquidGlassInstaller;

/**
 * 底部导航栏屏蔽：按开关隐藏首页 / 热点 / 游戏库 / 加号（需重启小黑盒生效）。
 */
public final class BottomTabHook {

    private final MainModule module;

    public BottomTabHook(MainModule module) {
        this.module = module;
    }

    /** 安装本模块的全部 Hook */
    public void install(ClassLoader cl) {
        hookBottomTabs(cl);
    }

    private void hookBottomTabs(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = clazz.getDeclaredMethod("onCreate", android.os.Bundle.class);
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed(); // 先执行原 onCreate
                try {
                    applyBottomTabSettings(chain.getThisObject());
                } catch (Throwable t) {
                    module.logd(Log.ERROR, module.TAG, "应用底部导航栏设置异常", t);
                }
                return result;
            });
            module.logd(Log.INFO, module.TAG, "✔ 底部导航栏 Hook 已安装");

            // hook onResume：热重载后切回小黑盒立即重新应用底栏设置
            try {
                Method onResume = clazz.getDeclaredMethod("onResume");
                module.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        applyBottomTabSettings(chain.getThisObject());
                    } catch (Throwable t) {
                        module.logd(Log.WARN, module.TAG, "onResume 应用底栏设置失败: " + t);
                    }
                    return result;
                });
                module.logd(Log.INFO, module.TAG, "✔ 底栏 onResume Hook 已安装");
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "底栏 onResume Hook 失败: " + t);
            }

            // 加号/底栏会被 MainActivity$j.b(Boolean) 生命周期回调重新 setVisibility(0) 显示，
            // hook 该回调，显示后重新应用隐藏设置
            try {
                Class<?> observerCls = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                for (Method m : observerCls.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Boolean.class) {
                        module.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object mainActivity = findOuterInstance(chain.getThisObject(), cl);
                                if (mainActivity != null) {
                                    applyBottomTabSettings(mainActivity);
                                }
                            } catch (Throwable t) {
                                module.logd(Log.WARN, module.TAG, "底栏状态回调后重新隐藏失败: " + t);
                            }
                            return result;
                        });
                        module.logd(Log.INFO, module.TAG, "✔ 底栏状态回调 Hook 已安装");
                        break;
                    }
                }
            } catch (Throwable t) {
                module.logd(Log.WARN, module.TAG, "底栏状态回调 Hook 安装失败: " + t);
            }
        } catch (Throwable t) {
            module.logd(Log.ERROR, module.TAG, "✘ 底部导航栏 Hook 失败", t);
        }
    }

    private Object findOuterInstance(Object innerObj, ClassLoader cl) {
        try {
            Class<?> mainCls = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            for (Field f : innerObj.getClass().getDeclaredFields()) {
                if (f.getType() == mainCls) {
                    f.setAccessible(true);
                    return f.get(innerObj);
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "查找外部 MainActivity 实例失败: " + t);
        }
        return null;
    }

    /** 根据开关反射隐藏底部导航栏的 tab（首页/热点/游戏库）与加号 */
    private void applyBottomTabSettings(Object activityObj) {
        try {
            Object binding = findViewBinding(activityObj);
            if (binding == null) {
                module.logd(Log.WARN, module.TAG, "未找到 ViewBinding 字段（fi.i1 / hi.i1）");
                return;
            }
            // 诊断：打印 hook 侧读到的开关值
            module.logd(Log.INFO, module.TAG, "开关值: home=" + module.isEnabled(App.KEY_HIDE_TAB_HOME, false)
                    + " hot=" + module.isEnabled(App.KEY_HIDE_TAB_HOT, false)
                    + " game=" + module.isEnabled(App.KEY_HIDE_TAB_GAME, false)
                    + " add=" + module.isEnabled(App.KEY_HIDE_ADD, false));
            boolean anyTabHidden = false;
            // tab 名称按小黑盒资源动态解析（版本自适应：发现/游戏库/社区）
            String labelHome = MainModule.getHeyboxTabLabel(
                    activityObj instanceof Activity ? (Activity) activityObj : null, "discover", "发现");
            String labelHot = MainModule.getHeyboxTabLabel(
                    activityObj instanceof Activity ? (Activity) activityObj : null, "game_store", "游戏库");
            String labelBbs = MainModule.getHeyboxTabLabel(
                    activityObj instanceof Activity ? (Activity) activityObj : null, "bbs", "社区");
            if (module.isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabField(binding, "j", labelHome);
                anyTabHidden = true;
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabField(binding, "k", labelHot);
                anyTabHidden = true;
            }
            if (module.isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabField(binding, "m", labelBbs);
                anyTabHidden = true;
            }
            // 加号：独立开关，或隐藏了任意 tab 时联动隐藏（保持底栏布局对称）
            if (module.isEnabled(App.KEY_HIDE_ADD, false) || anyTabHidden) {
                hideTabField(binding, "r", "加号");
                // 同时去掉「推荐」占位（rb_3 默认 INVISIBLE 占位），让剩余 tab 完全等分
                hideTabField(binding, "l", "推荐占位");
            }
            normalizeVisibleTabs(binding);
            ViewGroup group = findTabGroup(binding);
            if (group != null) {
                group.addOnLayoutChangeListener((v, left, top, right, bottom,
                        oldLeft, oldTop, oldRight, oldBottom) -> normalizeVisibleTabs(binding));
                group.postDelayed(() -> normalizeVisibleTabs(binding), 100);
                group.postDelayed(() -> normalizeVisibleTabs(binding), 500);
                group.postDelayed(() -> normalizeVisibleTabs(binding), 1500);
                group.postDelayed(() -> normalizeVisibleTabs(binding), 3000);
            }
            ensureVisibleTabSelected(binding);
            LiquidGlassInstaller.syncTabVisibility();
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "底部导航栏设置应用失败: " + t);
        }
    }

    private void normalizeVisibleTabs(Object binding) {
        try {
            Field groupField = binding.getClass().getDeclaredField("o");
            groupField.setAccessible(true);
            Object value = groupField.get(binding);
            if (!(value instanceof android.widget.RadioGroup)) return;
            android.widget.RadioGroup group = (android.widget.RadioGroup) value;
            int visible = 0;
            for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i).getVisibility() == View.VISIBLE) visible++;
            if (visible == 0) return;
            float weight = 1f / visible;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                android.widget.LinearLayout.LayoutParams lp = child.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams
                        ? (android.widget.LinearLayout.LayoutParams) child.getLayoutParams() : null;
                if (lp != null) { lp.width = 0; lp.weight = weight; child.setLayoutParams(lp); }
            }
            group.requestLayout();
        } catch (Throwable ignored) { }
    }

    private void ensureVisibleTabSelected(Object binding) {
        try {
            Field groupField = binding.getClass().getDeclaredField("o");
            groupField.setAccessible(true);
            Object value = groupField.get(binding);
            if (!(value instanceof android.widget.RadioGroup)) return;
            android.widget.RadioGroup group = (android.widget.RadioGroup) value;
            int checkedId = group.getCheckedRadioButtonId();
            if (checkedId != -1) {
                View checked = group.findViewById(checkedId);
                if (checked != null && checked.getVisibility() == View.VISIBLE) {
                    return;
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof android.widget.RadioButton
                        && child.getVisibility() == View.VISIBLE) {
                    int id = child.getId();
                    if (id != -1 && id != checkedId) {
                        group.check(id);
                        module.logd(Log.INFO, module.TAG, "选中 tab 已隐藏，切换到可见 tab id=" + id);
                    }
                    break;
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "纠正底栏选中项失败: " + t);
        }
    }

    private ViewGroup findTabGroup(Object binding) {
        try {
            Field f = binding.getClass().getDeclaredField("o"); f.setAccessible(true);
            Object value = f.get(binding); return value instanceof ViewGroup ? (ViewGroup) value : null;
        } catch (Throwable ignored) { return null; }
    }

    private Object findViewBinding(Object activity) {
        try {
            for (Field f : activity.getClass().getDeclaredFields()) {
                if (f.getType().getName().endsWith(".i1")) {
                    f.setAccessible(true);
                    return f.get(activity);
                }
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "查找 ViewBinding 失败: " + t);
        }
        return null;
    }

    private void dumpFields(Object obj) {
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                module.logd(Log.WARN, module.TAG, "  field: " + f.getName() + " : " + f.getType().getName());
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "转储字段失败: " + t);
        }
    }

    private void hideTabField(Object binding, String fieldName, String label) {
        try {
            Field field = binding.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object obj = field.get(binding);
            if (obj instanceof View) {
                final View v = (View) obj;
                v.setVisibility(View.GONE);
                // 小黑盒会在启动/生命周期回调中延迟重新显示 tab/加号，
                // 延迟多次重新隐藏以覆盖（否则出现 tab 与加号重合、布局错乱）
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 500);
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 1500);
                v.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.GONE);
                    }
                }, 3000);
                module.logd(Log.INFO, module.TAG, "隐藏 " + label + ": " + v.getVisibility());
            }
        } catch (Throwable t) {
            module.logd(Log.WARN, module.TAG, "隐藏 tab 失败 (" + label + ")，字段 " + fieldName + " 可能被 Robust 重命名，转储字段名：");
            dumpFields(binding);
        }
    }
}
