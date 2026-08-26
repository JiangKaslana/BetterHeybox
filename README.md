# BetterHeybox

增强小黑盒（Heybox）的 LSPosed 模块。

## Note
本应用基于 [小黑盒 1.3.393](https://github.com/Mrmiaomrzh/BetterHeybox/releases/download/v0.2.0/heybox_1.3.393.apk) 完成，低于此版本出现的问题不会进行处理

## 功能

所有功能开关均可在小黑盒「我的 → 设置 → 通用设置」中的 `BetterHeybox 设置` 入口直接打开模块面板，
也可以从模块独立设置页进行修改。开关配置存放在**小黑盒应用目录**
（`/data/data/com.max.xiaoheihe/shared_prefs/betterheybox.xml`），
小黑盒内的设置面板**直读直写本进程配置，不跨进程**——即使模块进程未运行、
部分系统拦截跨进程广播，开关也立即生效且重启保留；
模块独立设置页仍经框架 RemotePreferences 同步（直连框架，跨系统可用）。

### 广告过滤

| 类型 |
|------|
| 屏蔽开屏广告 |
| 屏蔽信息流广告 |
| 屏蔽气泡广告 |
| 屏蔽角标广告 |
| 屏蔽推广贴 |

### 界面增强

- **底部导航栏优化**（需重启小黑盒生效）：隐藏「首页 / 热点 / 游戏库 / 加号」；
  隐藏加号时联动隐藏「推荐」占位，剩余 tab 完全等分、无空白
- **设置页入口注入**：小黑盒「我的 → 设置」页顶部自动插入 "BetterHeybox 设置" 入口，
  点击后直接在当前页面渲染模块设置面板，不启动独立设置页
- **主题同步**：识别小黑盒深色模式的「打开 / 关闭 / 跟随系统」三种状态；
  深色为黑底白字，浅色为白底黑字，切换后自动刷新入口和面板
- **系统 Monet 取色**：开关和退出按钮使用 Android 系统 Monet 色板

### 帖子增强

- **解除复制**：Hook 小黑盒自定义 `TextSelectHandler` 的长按拦截，
  恢复安卓系统标准文本选择（长按正文弹「复制/全选」菜单，采用系统复制）
- **拖动跨行选择修复**：文本选择激活时放行滚动容器的触摸拦截，选择手柄可跨行拖动
- **图片系统分享**：图片查看器中长按图片，在原有分享面板追加「系统分享」动作，
  下载当前图片后唤起 Android 系统分享界面；可通过「系统分享图片」开关关闭

### 通用

- **版本前置检测**：检测小黑盒是否为目标版本 `1.3.393`，不匹配时显示提示
- **屏蔽更新**：提供可选开关，屏蔽小黑盒更新入口
- **记录日志**：提供「记录日志」开关，开启后自动把模块运行日志写入文件
  （小黑盒进程：`/data/data/com.max.xiaoheihe/files/betterheybox/log.txt`；
  模块进程：`/data/data/com.better.heybox/files/betterheybox/log.txt`），
  单文件超 512KB 自动滚动，便于离线排查问题

### 设置开关

- **广告过滤**：屏蔽开屏、信息流、气泡、角标广告和推广贴
- **底部导航栏隐藏**：隐藏首页、热点、游戏库或加号（需要重启小黑盒）
- **帖子增强**：解除正文复制限制、启用图片系统分享
- **通用**：屏蔽小黑盒更新入口、记录日志（开启后自动记录模块日志到文件）
- **小黑盒内设置面板不再跨进程**：开关直接读写小黑盒目录的配置文件
  （`shared_prefs/betterheybox.xml`），模块进程未运行 / 框架服务未连接 /
  系统拦截跨进程广播时同样即时生效并持久保留；
  同时尽力镜像到 RemotePreferences 供模块独立设置页读取（镜像失败不影响小黑盒内生效）

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
│   ├── MainModule.java          # 模块入口：生命周期 + Hook 安装编排 + 共享工具
│   ├── App.java                 # Application：连接框架服务、RemotePreferences 存取
│   ├── SettingsActivity.java    # 模块独立设置界面
│   ├── HeyboxPrefs.java         # 小黑盒进程本地配置存储（配置文件放小黑盒目录）
│   ├── LogRecorder.java         # 文件日志记录器（日志开关）
│   ├── PreferenceReceiver.java  # 设置写回广播接收（镜像同步 RemotePreferences）
│   └── hooks/                   # 各功能 Hook 按模块拆分
│       ├── GeneralHook.java     #   通用：版本检测 / 屏蔽更新
│       ├── AdFilterHook.java    #   广告过滤：开屏 / 信息流 / 气泡 / 角标
│       ├── SettingsEntryHook.java # 设置页入口注入 + 内嵌设置面板
│       ├── BottomTabHook.java   #   底部导航栏隐藏
│       ├── PromotePostHook.java #   推广贴屏蔽
│       ├── TextSelectHook.java  #   解除复制 / 标准文本选择 / 跨行选择
│       └── ImageShareHook.java  #   图片系统分享
├── res/                         # 设置页布局 / 字符串 / drawable
└── resources/META-INF/xposed/   # 模块声明
```

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
4. **看日志**：`adb logcat -s BetterHeybox`（每个 Hook 安装成功/失败均有 ✔/✘ 日志）；
   也可在小黑盒设置面板 / 独立设置页开启「记录日志」，日志自动写入文件便于离线排查

# 免责声明
本应用与清枫（北京）科技有限公司无关，仅学习研究小黑盒APP原理，请在下载后24h内删除
