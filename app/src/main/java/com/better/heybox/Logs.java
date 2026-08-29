package com.better.heybox;

import android.util.Log;

/**
 * 统一日志出口：正式版（Release）只放行 error 级，info/warn 一律丢弃，
 * 与 {@link MainModule#logd}、{@link LogRecorder} 的过滤策略保持一致；
 * Debug 构建全量输出。模块内请勿直接调用 {@link Log}。
 */
public final class Logs {

    private Logs() {
    }

    public static void i(String tag, String msg) {
        if (BuildFlags.DEBUG) {
            Log.i(tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (BuildFlags.DEBUG) {
            Log.w(tag, msg);
        }
    }

    public static void w(String tag, String msg, Throwable tr) {
        if (BuildFlags.DEBUG) {
            Log.w(tag, msg, tr);
        }
    }

    public static void e(String tag, String msg) {
        Log.e(tag, msg);
    }

    public static void e(String tag, String msg, Throwable tr) {
        Log.e(tag, msg, tr);
    }
}
