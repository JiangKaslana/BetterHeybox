package com.better.heybox;

/**
 * Debug 构建标志：启用运行时检查点（{@link Checkpoint}）与设置页「运行状态」入口，
 * 用于排查模块在目标进程内的实际运行情况。
 */
public final class BuildFlags {

    /** 当前是否为 Debug 构建（Release 构建为 false，检查点全部空操作） */
    public static final boolean DEBUG = true;

    private BuildFlags() {
    }
}
