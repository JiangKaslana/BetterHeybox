package com.better.heybox;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 前台跟踪（仅 Debug 构建生效）：每次小黑盒打开到前台记录一条检查点，
 * 划到后台不记录。
 *
 * <p>通过 {@link Application.ActivityLifecycleCallbacks} 统计活跃 Activity 数量：
 * 数量 0→1 即为一次「打开应用」（冷启动 / 从后台回到前台），此时打点；
 * 数量 1→0 即划到后台，只减计数、不产生记录。应用内页面跳转（数量始终 ≥1）不重复记录。</p>
 */
public final class ForegroundTracker {

    private static final AtomicBoolean sRegistered = new AtomicBoolean(false);
    private static final AtomicBoolean sFirstResume = new AtomicBoolean(false);
    private static int sActiveCount;

    private ForegroundTracker() {
    }

    /** 由 BaseActivity.onResume Hook 调用（首次触发时注册生命周期回调） */
    public static void onActivityResumed(Activity activity) {
        if (!BuildFlags.DEBUG) {
            return;
        }
        registerIfNeeded(activity);
        // 注册晚于首个 onActivityStarted 的情况：首个 resume 补记一次「打开应用」
        if (sActiveCount == 0 && sFirstResume.compareAndSet(false, true)) {
            sActiveCount = 1;
            Checkpoint.mark("应用打开（前台）: %s", activity.getClass().getSimpleName());
        }
    }

    private static void registerIfNeeded(Context context) {
        if (!sRegistered.compareAndSet(false, true)) {
            return;
        }
        try {
            Context app = context.getApplicationContext();
            if (!(app instanceof Application)) {
                return;
            }
            ((Application) app).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    if (++sActiveCount == 1) {
                        Checkpoint.mark("应用打开（前台）: %s", activity.getClass().getSimpleName());
                    }
                }

                @Override
                public void onActivityResumed(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    // 划到后台：只减计数，不记录
                    if (sActiveCount > 0) {
                        sActiveCount--;
                    }
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
            Checkpoint.mark("前台跟踪已注册");
        } catch (Throwable t) {
            Checkpoint.mark("前台跟踪注册失败: %s", t);
        }
    }
}
