import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// 从 keystore.properties 读取签名信息（该文件不入库，本地手动创建；CI 中由 Secrets 生成）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.better.heybox"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.better.heybox"
        minSdk = 26          // LSPosed 最低支持 Android 8.0 (API 26)
        targetSdk = 37
        // 版本号统一在 gradle.properties 管理（versionCode 递增决定升级判定）
        versionCode = (project.findProperty("VERSION_CODE") as String? ?: "1").toInt()
        versionName = project.findProperty("VERSION_NAME") as String? ?: "0.2.0"
    }

    signingConfigs {
        // keystore.properties 存在才配置 release 签名；缺失时 release 产物为 unsigned
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Xposed 模块不要开混淆：会干扰 Hook 目标定位与入口类
            isMinifyEnabled = false
            // 挂上官方规则（供日后启用 R8 时使用）
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 把 META-INF/xposed/ 下的模块声明文件（java_init.list / module.prop / scope.list）
    // 合并进 APK，避免被 AGP 默认排除
    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }
}

dependencies {
    // 编译期引用，不打进 APK；运行时由 LSPosed 框架提供
    compileOnly(libs.libxposed.api)
    // 打包进 APK：模块 App 通过它与框架通信（RemotePreferences 跨进程共享开关）
    implementation(libs.libxposed.service)
}
