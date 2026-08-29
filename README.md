# BetterHeybox

![BetterHeybox](https://socialify.git.ci/Mrmiaomrzh/BetterHeybox/image?font=Source+Code+Pro&forks=1&issues=1&language=1&name=1&pattern=Floating+Cogs&pulls=1&stargazers=1&theme=Auto)

增强小黑盒（Heybox）的 libxposed 模块，支持 **Root + LSPosed** 与 **无 Root + NPatch** 两种运行方式。

当前版本同时提供独立桌面管理器：安装 BetterHeybox 后可以直接从桌面打开，查看 Hook/NPatch/Shizuku 环境、切换常用功能并重启小黑盒。

## 免责声明

本应用与清枫（北京）科技有限公司无关，仅用于学习研究小黑盒 App 的部分实现原理，请自行评估使用风险。

> [!NOTE]
> 当前主要适配小黑盒 `1.3.393` / `1.3.394`。项目带有 DexKit 自动定位能力，但目标 App 大版本变化后仍可能需要重新适配。

> [!IMPORTANT]
> 无 Root 使用 NPatch 时，请把 **破解签名校验**设置为 **`Extreme`**。低级别签名绕过已知可能导致小黑盒参数缺失或闪退。

## 运行方式

### 1. Root + LSPosed

传统方式保持兼容：

1. 安装 BetterHeybox。
2. 使用支持 libxposed API 102 的 LSPosed。
3. 启用 BetterHeybox；`staticScope=true` 时作用域固定为 `com.max.xiaoheihe`。
4. 重启小黑盒。

### 2. 无 Root + NPatch（推荐给普通设备）

BetterHeybox 的核心功能是**进程内 Hook**，因此 Shizuku 不能替代 Xposed。无 Root 模式由 NPatch 把 libxposed 环境注入小黑盒：

```text
BetterHeybox Manager
        │
        ├─ 设置：libxposed Service / NPatch Remote API
        ├─ 进程控制：Shizuku+（可选）
        │
        └─ Hook：NPatch
                  │
                  └─ com.max.xiaoheihe
```

快速步骤：

1. 安装 NPatch。
2. 在 NPatch 中选择小黑盒进行本地修补。
3. 把 BetterHeybox 加入本次修补/模块配置。
4. 签名绕过选择 **Extreme**。
5. 安装修补后的小黑盒并启动一次。
6. 打开桌面的 BetterHeybox 管理器检查状态。

详细说明见 [ROOTLESS.md](ROOTLESS.md)。

### Shizuku / Shizuku+

Shizuku+ 是**可选增强**，只负责无 Root 下的高权限进程操作，不参与 Hook。

管理器的进程控制优先级：

```text
Shizuku / Shizuku+ > Root su > KILL_BACKGROUND_PROCESSES 普通兜底
```

授权后，“可靠重启小黑盒”会优先使用 Shizuku 执行 `am force-stop`，再重新拉起小黑盒。

如果使用 Shizuku+ 的独立包名版本，请按 Shizuku+ 的说明安装 Compat Hub，使标准 Shizuku 客户端能够获取服务 Binder。

## 独立桌面管理器

BetterHeybox 现在包含 Launcher Activity，不再是“装完桌面完全看不到”的纯模块 APK。

管理器当前可以：

- 查看小黑盒版本和安装状态
- 检测 NPatch Manager
- 检测已安装小黑盒是否带 NPatch 修补标记
- 读取 NPatch `sigBypassLevel` 并提示是否达到 Extreme
- 检测 NPatch Remote API Provider
- 检测 libxposed / NPatch Remote 设置服务
- 检测 Shizuku+、Compat Hub、Binder 和授权状态
- 检测 Root / `su`
- 一键打开 NPatch / Shizuku+ / 小黑盒
- 可靠重启小黑盒
- 在桌面直接控制常用 BetterHeybox 开关

### 两套设置入口如何同步

仍然保留小黑盒内部：

`我的 → 设置 → 通用设置 → BetterHeybox 设置`

同时增加桌面 BetterHeybox Manager。

小黑盒内嵌设置继续写入：

`/data/user/0/com.max.xiaoheihe/shared_prefs/betterheybox.xml`

桌面管理器通过 libxposed RemotePreferences 或 NPatch Remote API 写入模块远端配置。两边写入都会附带时间戳，Hook 侧比较时间：**最后一次修改的值生效**。

这样既保留目标进程内本地设置的可靠性，也让无 Root 桌面管理器真正能够控制模块，而不是只显示一个无效开关页面。

## 功能

### 广告过滤

- 屏蔽开屏广告
- 屏蔽信息流广告
- 屏蔽气泡广告
- 屏蔽角标广告
- 屏蔽推广贴

信息流过滤在小黑盒进程内 Hook Gson 反序列化路径，不是简单的 DNS/域名屏蔽。

### 界面增强

- 隐藏底部导航栏指定 Tab
- 隐藏底部“加号”
- 需要重启小黑盒的配置可通过管理器快速重启

### 帖子增强

- **解除复制**：解除小黑盒 `TextSelectHandler` 的长按拦截，恢复标准文本选择
- **自绘制文本选择**：用于部分机型/页面原生选区异常时的兼容模式
- **跨行选择修复**
- **图片系统分享**：长按图片追加系统分享并优先保存到相册
- **净化分享链接**：移除 `h_camp`、`h_session_id`、`h_src`、`new_post_share_style` 等追踪参数，保留 `link_id` / `id` / `hkey` 等功能参数

### 视频下载

- 捕获小黑盒播放器真实视频 URL
- mp4 与 HLS/m3u8
- 后台下载
- 暂停 / 继续 / 取消
- 断点续传
- HLS 合并后自动无损转封装 MP4
- 默认保存到 `Movies/BetterHeybox`
- 支持系统文件选择器指定目录
- 通知栏进度、完成、失败与操作按钮
- 完成后一键播放 / 分享

### 每日任务

支持自动完成 3 类分享任务：

- 分享任意帖子
- 分享游戏详情
- 分享游戏评价

支持分别配置链接与分享渠道，并可清除当天状态后重新执行。

### 通用

- 小黑盒版本检测
- 伪装通知权限判断
- 屏蔽小黑盒更新入口
- 模块日志记录与导出
- 配置导入 / 导出

## 更新兼容

项目使用 DexKit 根据字节码特征重新定位部分混淆类/方法：

- HeyBoxDialog 原生弹窗定位
- 设置页入口兜底定位
- 部分 ViewBinding/生命周期路径兜底

DexKit 能降低小版本更新带来的维护成本，但不能保证任意新版本都无需适配。

## 技术栈

| 项 | 值 |
|---|---|
| 语言 | Java 17 |
| Hook API | `io.github.libxposed:api:102.0.0` |
| Service | `io.github.libxposed:service:102.0.0` |
| 字节码分析 | `org.luckypray:dexkit:2.2.0` |
| Rootless Hook | NPatch |
| Rootless 进程控制 | Shizuku API 13 / Shizuku+ Compat Hub |
| compileSdk / targetSdk | 37 |
| minSdk | 26 |
| AGP / Gradle | 9.2.1 / 9.7.1 |

## 工程结构

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/better/heybox/
│   ├── MainActivity.java          # 独立桌面管理器
│   ├── App.java                   # libxposed + NPatch Remote 设置后端
│   ├── MainModule.java            # libxposed 模块入口与 Hook 编排
│   ├── HeyboxPrefs.java           # 小黑盒进程本地设置 + 时间戳
│   ├── PreferenceReceiver.java    # 内嵌设置到 RemotePreferences 的镜像
│   ├── RootlessEnvironment.java   # NPatch/Rootless 环境检测
│   ├── ShizukuBridge.java         # 标准 Shizuku API 兼容层
│   ├── PrivilegedOps.java         # Shizuku / root / 普通权限降级链
│   ├── DexKitResolver.java
│   ├── VideoDownloadManager.java
│   └── hooks/
│       ├── GeneralHook.java
│       ├── AdFilterHook.java
│       ├── SettingsEntryHook.java
│       ├── BottomTabHook.java
│       ├── PromotePostHook.java
│       ├── TextSelectHook.java
│       ├── ImageShareHook.java
│       ├── ShareLinkPurifyHook.java
│       ├── VideoDownloadHook.java
│       └── DailyTaskHook.java
├── java/top/nkbe/npatch/remote/
│   └── NPatchRemoteClient.java    # NPatch Remote API 最小兼容客户端
├── res/
└── resources/META-INF/xposed/
    ├── java_init.list
    ├── module.prop
    └── scope.list
```

## 构建

环境：JDK 17+、Android SDK Platform 37。

```bash
./gradlew assembleDebug
```

Windows：

```powershell
gradlew.bat assembleDebug
```

Debug APK：

`app/build/outputs/apk/debug/app-debug.apk`

Release 构建启用 R8；`proguard-rules.pro` 保留模块入口和 Shizuku 反射调用所需符号。

## 模块声明

`META-INF/xposed/module.prop`：

```text
minApiVersion=101
targetApiVersion=102
staticScope=true
autoHotReload=true
```

作用域为小黑盒 `com.max.xiaoheihe`。

## 致谢

- [LSPosed](https://github.com/LSPosed/LSPosed)
- [libxposed API](https://github.com/libxposed/api)
- [libxposed service](https://github.com/libxposed/service)
- [DexKit](https://github.com/LuckyPray/DexKit)
- [NPatch](https://github.com/7723mod/NPatch)
- [NPatch Remote API](https://github.com/7723mod/NPatch-Remote-API)
- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [Shizuku+](https://github.com/thejaustin/ShizukuPlus)

第三方代码/依赖说明见 `THIRD_PARTY_NOTICES.md`。
