# libxposed API 官方推荐规则（即使未开混淆也建议保留，供日后启用 R8 时使用）
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keep class com.better.heybox.** { *; }

# Shizuku.newProcess is intentionally reached by reflection so it can survive
# API source revisions where the transitional method changes visibility.
-keep class rikka.shizuku.Shizuku {
    *** newProcess(...);
}
-keep class rikka.shizuku.ShizukuRemoteProcess { *; }

-dontwarn org.luckypray.**
-dontwarn com.google.flatbuffers.**
-dontwarn org.lsposed.**
-dontwarn hidden.**
-dontwarn dev.rikka.**
-dontwarn rikka.shizuku.**
