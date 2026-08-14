# BetterHeybox

小黑盒（Heybox）增强的 LSPosed 模块（libxposed Modern API 102）。

## Note
本应用基于小黑盒1.3.393上完成，低于此版本出现的问题不会进行处理

## 功能

所有功能开关均可在模块自带设置界面（`SettingsActivity`）独立控制；
开关经 LSPosed **RemotePreferences** 跨进程同步，**广告过滤类开关即时生效**。

### 广告过滤

| 类型 |
|------|
| 开屏广告 |
| 信息流广告|
| 气泡广告 |
| 角标广告 | 
| 推广贴 |

### 界面增强

- **底部导航栏优化**（需重启小黑盒生效）：隐藏「首页 / 热点 / 游戏库 / 加号」；
  隐藏加号时联动隐藏「推荐」占位，剩余 tab 完全等分、无空白
- **设置页入口注入**：小黑盒「我的 → 设置」页顶部自动插入 "BetterHeybox 设置" 入口，
  优先复用小黑盒 `SettingItemView` 样式（失败回退纯代码构建，深浅色均清晰）

### 帖子增强

- **解除复制**：Hook 掉小黑盒自定义 `TextSelectHandler` 的长按拦截，
  恢复安卓系统标准文本选择（长按正文弹「复制/全选」菜单，采用系统复制）
- **拖动跨行选择修复**：文本选择激活时放行滚动容器的触摸拦截，选择手柄可跨行拖动

### 通用

- **不显示桌面图标**：组件级禁用 `LaunchActivity`（立即生效），
  隐藏后仍可从小黑盒设置页注入的入口进入模块设置

## 技术栈

| 项 | 值 |
|----|----|
| 语言 | Java 17 |
| Hook API | `io.github.libxposed:api:102.0.0` |
| Service | `io.github.libxposed:service:102.0.0` |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |
| AGP / Gradle | 9.2.1 / 9.5.1 |
| JDK | 17+ |

## 工程结构

```
app/src/main/
├── AndroidManifest.xml          # 模块名/描述 = android:label / android:description
├── java/com/better/heybox/
│   ├── MainModule.java          # 模块入口
│   ├── App.java                 # Application：连接框架服务、RemotePreferences 存取
│   ├── SettingsActivity.java    # 模块设置界面
│   └── LaunchActivity.java      # 桌面图标入口
├── res/                         # 设置页布局 / 字符串 / drawable
└── resources/META-INF/xposed/   # 模块声明
```

另有 `docs/`（开发前置准备文档）与 `analysis/`（逆向分析工作区，不入库）。

## 模块声明

不再使用 Manifest meta-data 与 `assets/xposed_init`，全部声明在 `META-INF/xposed/`：

```
app/src/main/resources/META-INF/xposed/
├── java_init.list      # 入口类
├── module.prop         # minApiVersion=101
└── scope.list          # 作用域
```

- 模块名称 / 描述：`android:label` / `android:description`（见 `res/values/strings.xml`）

## 构建与使用

1. **环境**：Android Studio 打开本目录（首次自动下载 Gradle 9.5.1 + 依赖，需网络；
   若提示缺 wrapper 让 AS 自动补全；SDK Manager 需装有 **Platform 37**）
2. **编译**：
   - Windows：`gradlew.bat assembleDebug`
   - 命令行/CI：`./gradlew assembleDebug`
   - 或 Android Studio `Build > Make Project`
   - 产物：`app/build/outputs/apk/debug/app-debug.apk`
3. **刷入**：
   - 模拟器/真机需 root + **支持 API 102 的 LSPosed**
   - 安装 APK → LSPosed Manager 启用模块。
     `staticScope=true` 时作用域固定为 scope.list 中的小黑盒，无需（也无法）手动勾选其它应用
   - 重启小黑盒进程
4. **看日志**：`adb logcat -s BetterHeybox`（每个 Hook 安装成功/失败均有 ✔/✘ 日志）

# 免责声明
本应用与清枫（北京）科技有限公司无关，仅学习研究小黑盒APP原理，请在下载后24h内删除