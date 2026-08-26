package com.better.heybox.hooks;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.better.heybox.App;
import com.better.heybox.MainModule;

/**
 * 底部导航栏屏蔽：按开关隐藏首页 / 热点 / 游戏库 / 加号（需重启小黑盒生效）。
 */
public final class BottomTabHook {

    private final MainModule m;

    public BottomTabHook(MainModule module) {
        this.m = module;
    }
    public void install(ClassLoader cl) {
        hookBottomTabs(cl);
    }

    private void hookBottomTabs(ClassLoader cl) {
        try {
            Class<?> clazz = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            Method onCreate = clazz.getDeclaredMethod("onCreate", android.os.Bundle.class);
            m.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed(); // 先执行原 onCreate
                try {
                    applyBottomTabSettings(chain.getThisObject());
                } catch (Throwable t) {
                    m.logd(Log.ERROR, m.TAG, "应用底部导航栏设置异常", t);
                }
                return result;
            });
            m.logd(Log.INFO, m.TAG, "✔ 底部导航栏 Hook 已安装");

            // hook onResume：热重载后切回小黑盒立即重新应用底栏设置
            try {
                Method onResume = clazz.getDeclaredMethod("onResume");
                m.hook(onResume).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        applyBottomTabSettings(chain.getThisObject());
                    } catch (Throwable t) {
                        m.logd(Log.WARN, m.TAG, "onResume 应用底栏设置失败: " + t);
                    }
                    return result;
                });
                m.logd(Log.INFO, m.TAG, "✔ 底栏 onResume Hook 已安装");
            } catch (Throwable t) {
                m.logd(Log.WARN, m.TAG, "底栏 onResume Hook 失败: " + t);
            }

            // 加号/底栏会被 MainActivity$j.b(Boolean) 生命周期回调重新 setVisibility(0) 显示，
            // hook 该回调，显示后重新应用隐藏设置
            try {
                Class<?> observerCls = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                for (Method m : observerCls.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Boolean.class) {
                        m.hook(m).intercept(chain -> {
                            Object result = chain.proceed();
                            try {
                                Object mainActivity = findOuterInstance(chain.getThisObject(), cl);
                                if (mainActivity != null) {
                                    applyBottomTabSettings(mainActivity);
                                }
                            } catch (Throwable t) {
                                m.logd(Log.WARN, m.TAG, "底栏状态回调后重新隐藏失败: " + t);
                            }
                            return result;
                        });
                        m.logd(Log.INFO, m.TAG, "✔ 底栏状态回调 Hook 已安装");
                        break;
                    }
                }
            } catch (Throwable t) {
                m.logd(Log.WARN, m.TAG, "底栏状态回调 Hook 安装失败: " + t);
            }
        } catch (Throwable t) {
            m.logd(Log.ERROR, m.TAG, "✘ 底部导航栏 Hook 失败", t);
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
            m.logd(Log.WARN, m.TAG, "查找外部 MainActivity 实例失败: " + t);
        }
        return null;
    }

    /** 根据开关反射隐藏底部导航栏的 tab（首页/热点/游戏库）与加号 */
    private void applyBottomTabSettings(Object activityObj) {
        try {
            Object binding = findViewBinding(activityObj);
            if (binding == null) {
                m.logd(Log.WARN, m.TAG, "未找到 ViewBinding 字段（fi.i1）");
                return;
            }
            // 诊断：打印 hook 侧读到的开关值
            m.logd(Log.INFO, m.TAG, "开关值: home=" + m.isEnabled(App.KEY_HIDE_TAB_HOME, false)
                    + " hot=" + m.isEnabled(App.KEY_HIDE_TAB_HOT, false)
                    + " game=" + m.isEnabled(App.KEY_HIDE_TAB_GAME, false)
                    + " add=" + m.isEnabled(App.KEY_HIDE_ADD, false));
            boolean anyTabHidden = false;
            if (m.isEnabled(App.KEY_HIDE_TAB_HOME, false)) {
                hideTabField(binding, "j", "首页");
                anyTabHidden = true;
            }
            if (m.isEnabled(App.KEY_HIDE_TAB_HOT, false)) {
                hideTabField(binding, "k", "热点");
                anyTabHidden = true;
            }
            if (m.isEnabled(App.KEY_HIDE_TAB_GAME, false)) {
                hideTabField(binding, "m", "游戏库");
                anyTabHidden = true;
            }
            // 加号：独立开关，或隐藏了任意 tab 时联动隐藏（保持底栏布局对称）
            if (m.isEnabled(App.KEY_HIDE_ADD, false) || anyTabHidden) {
                hideTabField(binding, "r", "加号");
                // 同时去掉「推荐」占位（rb_3 默认 INVISIBLE 占位），让剩余 tab 完全等分
                hideTabField(binding, "l", "推荐占位");
            }
        } catch (Throwable t) {
            m.logd(Log.WARN, m.TAG, "底部导航栏设置应用失败: " + t);
        }
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
            m.logd(Log.WARN, m.TAG, "查找 ViewBinding 失败: " + t);
        }
        return null;
    }

    private void dumpFields(Object obj) {
        try {
            for (Field f : obj.getClass().getDeclaredFields()) {
                m.logd(Log.WARN, m.TAG, "  field: " + f.getName() + " : " + f.getType().getName());
            }
        } catch (Throwable t) {
            m.logd(Log.WARN, m.TAG, "转储字段失败: " + t);
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
                m.logd(Log.INFO, m.TAG, "隐藏 " + label + ": " + v.getVisibility());
            }
        } catch (Throwable t) {
            m.logd(Log.WARN, m.TAG, "隐藏 tab 失败 (" + label + ")，字段 " + fieldName + " 可能被 Robust 重命名，转储字段名：");
            dumpFields(binding);
        }
    }
}
