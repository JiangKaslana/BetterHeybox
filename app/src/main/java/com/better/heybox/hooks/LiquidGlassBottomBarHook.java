package com.better.heybox.hooks;

import android.app.Activity;
import android.os.Bundle;
import java.lang.reflect.Method;
import com.better.heybox.MainModule;
import com.better.heybox.liquidglass.LiquidGlassHookBridge;
import com.better.heybox.liquidglass.LiquidGlassInstaller;

/** Hooks host lifecycle and delegates to the complete ported glass installer. */
public final class LiquidGlassBottomBarHook {
    private final MainModule module;
    public LiquidGlassBottomBarHook(MainModule module) { this.module = module; }
    public void install(ClassLoader cl) {
        LiquidGlassHookBridge.setModule(module);
        LiquidGlassInstaller.installSettingsEntries(cl);
        try {
            Class<?> main = Class.forName("com.max.xiaoheihe.MainActivity", false, cl);
            hook(main.getDeclaredMethod("onCreate", Bundle.class));
            hook(main.getDeclaredMethod("onResume"));
            try {
                Class<?> observer = Class.forName("com.max.xiaoheihe.MainActivity$j", false, cl);
                for (Method m : observer.getDeclaredMethods()) {
                    if ("b".equals(m.getName()) && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == Boolean.class) {
                        hook(m, true, main); break;
                    }
                }
            } catch (Throwable ignored) { }
        } catch (Throwable t) {
            module.logd(android.util.Log.ERROR, module.TAG, "液态玻璃生命周期 Hook 安装失败", t);
        }
    }
    private void hook(Method method) { hook(method, false, null); }
    private void hook(Method method, boolean inner, Class<?> main) {
        module.hook(method).intercept(chain -> {
            Object result = chain.proceed();
            try {
                Activity activity = inner ? findOuter(chain.getThisObject(), main) :
                        (chain.getThisObject() instanceof Activity ? (Activity) chain.getThisObject() : null);
                if (activity != null) LiquidGlassInstaller.scheduleInstall(activity);
            } catch (Throwable t) {
                module.logd(android.util.Log.WARN, module.TAG, "液态玻璃安装调度失败", t);
            }
            return result;
        });
    }
    private Activity findOuter(Object object, Class<?> main) {
        for (java.lang.reflect.Field field : object.getClass().getDeclaredFields()) {
            try { if (field.getType() == main) { field.setAccessible(true); Object value = field.get(object); return value instanceof Activity ? (Activity)value : null; } }
            catch (Throwable ignored) { }
        }
        return null;
    }
}
