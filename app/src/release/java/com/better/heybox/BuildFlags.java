package com.better.heybox;

/**
 * Release 构建标志：关闭运行时检查点，避免日志噪音与运行时开销。
 */
public final class BuildFlags {

    /** 当前是否为 Debug 构建（Release 构建为 false，检查点全部空操作） */
    public static final boolean DEBUG = false;

    private BuildFlags() {
    }
}
