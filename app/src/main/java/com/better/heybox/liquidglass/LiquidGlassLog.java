package com.better.heybox.liquidglass;

import android.util.Log;

final class LiquidGlassLog {
    private LiquidGlassLog() { }
    static void log(int priority, String message) { Log.println(priority, "BetterHeybox", message); }
    static void logErr(String message, Throwable error) { Log.e("BetterHeybox", message, error); }
}
