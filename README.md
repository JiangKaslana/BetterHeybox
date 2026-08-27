# BetterHeybox

![BetterHeybox](https://socialify.git.ci/Mrmiaomrzh/BetterHeybox/image?font=Source+Code+Pro&forks=1&issues=1&language=1&name=1&pattern=Floating+Cogs&pulls=1&stargazers=1&theme=Auto)

增强小黑盒（Heybox）的 LSPosed 模块。

## Note
本应用兼容 [小黑盒 1.3.393](https://github.com/Mrmiaomrzh/BetterHeybox/releases/download/v0.2.0/heybox_1.3.393.apk) 及以上版本，
其他版本出现的问题不会进行处理

# 免责声明
本应用与清枫（北京）科技有限公司无关，仅学习研究小黑盒APP部分原理，请在下载后24h内删除

## 功能

所有功能开关均可在小黑盒「我的 → 设置 → 通用设置」中的 `BetterHeybox 设置` 入口直接打开模块面板，
开关配置存放在**小黑盒应用目录**
（`/data/data/com.max.xiaoheihe/shared_prefs/betterheybox.xml`），

### 广告过滤

| 类型 |
|------|
| 屏蔽开屏广告 |
| 屏蔽信息流广告 |
| 屏蔽气泡广告 |
| 屏蔽角标广告 |
| 屏蔽推广贴 |

### 界面增强

- **底部导航栏优化**（需重启小黑盒生效）：隐藏底栏tab项

### 帖子增强

- **解除复制**：Hook 小黑盒自定义 `TextSelectHandler` 的长按拦截，
  恢复安卓系统标准文本选择
- **拖动跨行选择修复**：文本选择激活时放行滚动容器的触摸拦截，选择手柄可跨行拖动
- **图片系统分享**：图片查看器中长按图片，在原有分享面板追加「系统分享」动作，
  下载当前图片后**优先保存到系统相册**（可被相册真正查看、可被任意 App 分享），
  自动识别 jpg/png/gif/webp/bmp 真实格式并修正 MIME；可通过「系统分享图片」开关关闭

### 每日任务

- **自动完成每日分享任务**：自动完成小黑盒每日任务的 **3 种分享任务**
  - 任务一：**分享任意帖子**（配置帖子链接）
  - 任务二：**分享游戏详情**（配置游戏详情链接）
  - 任务三：**分享游戏评价**（配置游戏评价链接）
- **3 个独立链接设置**：帖子链接 / 游戏详情链接 / 游戏评价链接，各自独立配置；
  未配置的任务自动跳过；每日状态按日期记录，跨天重置
- **分享渠道可配置**：内嵌面板/独立设置页「分享渠道」可选 **QQ / QQ空间**、**微信 / 朋友圈** 或 **微博**，
  自动分享按所选渠道在分享面板点击对应按钮并伪造成功回调（默认 QQ；抖音因无分享成功回调暂不支持）
- **清除今日打卡**：打卡失败或想重新执行时，点击「清除今日打卡」清除今日已完成状态并立即重新尝试

#### 链接格式（3 个分享链接均支持以下任意一种）

| 类型 | 示例 |
|------|------|
| 分享链接（带 link_id） | `https://api.xiaoheihe.cn/v3/bbs/app/api/web/share?link_id=123456` |
| 网页链接（xiaoheihe.cn） | `https://xiaoheihe.cn/a/123456` |
| 深链协议（heybox://） | `heybox://v3/bbs/app/api/web/share?link_id=123456` |

> 链接经小黑盒 RouterActivity 自动路由到对应帖子/游戏页；未配置的类型自动跳过。
> **获取方式**：在小黑盒 App 打开目标帖子 → 分享 → 复制链接，取分享链接或网页链接均可；
> 游戏/频道页同理复制分享链接  

### 通用

- **版本前置检测**：检测小黑盒版本是否为受支持版本 `1.3.393` / `1.3.394`，不匹配时显示提示
- **伪装通知权限**：让小黑盒认为通知权限已开启，获得**签到加成**  
- **屏蔽更新**：提供可选开关，屏蔽小黑盒更新   
- **记录日志**：提供「记录日志」开关，开启后自动把模块运行日志写入文件

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
│       ├── GeneralHook.java     #   通用：版本检测 / 屏蔽更新 / 伪装通知权限
│       ├── AdFilterHook.java    #   广告过滤：开屏 / 信息流 / 气泡 / 角标
│       ├── SettingsEntryHook.java # 设置页入口注入 + 内嵌设置面板（原生 HeyBoxDialog）
│       ├── BottomTabHook.java   #   底部导航栏隐藏（tab 名版本自适应）
│       ├── PromotePostHook.java #   推广贴屏蔽
│       ├── TextSelectHook.java  #   解除复制 / 标准文本选择 / 跨行选择
│       ├── ImageShareHook.java  #   图片系统分享（优先保存系统相册）
│       └── DailyTaskHook.java   #   每日任务：3 种分享类型自动完成
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

## 致谢
- [LSPosed](https://github.com/LSPosed/LSPosed)
- [Libxposed api](https://github.com/libxposed/api) — Apache-2.0，现代 Xposed 模块 API

### 部分功能灵感来源
- [假装开启小黑盒通知权限](https://github.com/Xposed-Modules-Repo/com.chrxw.justenablednotification)
- [SoulFrog](https://github.com/xmnh/SoulFrog)