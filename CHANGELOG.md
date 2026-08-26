# Changelog

本项目版本号采用 `v主版本.次版本.修订版本` 格式。推送代码时，CI 会构建带日期和当日序号的版本并将 APK 保存在 Action 成品中；Release 工作流手动输入版本号后创建正式 Release，并在说明中列出 commit 更新内容。

## 0.4.1

### 修复：394 设置 UI 3 倍大小问题

- **根因**：内嵌设置面板与设置入口使用**硬编码资源 ID**（如 `0x7f0700ff` 作为行高），
  资源 ID 每个 APK 各自生成，394 中该 ID 指向 `item_touch_helper_swipe_escape_velocity=120dp`，
  导致行高约 3 倍异常
- **修复**：行高/字号改用 `module.dp()` 计算值；颜色/图标等资源改为**按资源名解析**
  （`getIdentifier`，失败回退默认值），彻底消除跨版本资源 ID 错位风险

### 每日任务自动化 + 图片分享修复

- 新增「自动完成每日分享任务」：自动完成小黑盒每日任务的 **3 种分享类型**——
  图片帖分享（PicturePostPageActivityV2）→ 普通帖分享（NormalPostPageActivity）→ 频道关注（ChannelsDetailActivity）；
  通过 Hook `ShareUtils.P/y` 触发 `HBShareData.shareListener.onResult` 完成，**不拦截 QQ 分享入口**
- 新增 3 个分享链接设置（图片帖 / 普通帖 / 频道，各自独立配置）：小黑盒内嵌设置面板与独立设置页均可编辑，
  支持 api.xiaoheihe.cn / xiaoheihe.cn / heybox:// 链接，经小黑盒 RouterActivity 自动路由；
  未配置的类型自动跳过；每日状态按日期记录，跨天重置
- 链接编辑弹窗复用小黑盒原生 **HeyBoxDialog**（`com.max.hbcommon.view.d$i`，仿 SettingActivity「修改版本号」输入弹窗，
  含 `bg_dialog_edit` 背景/主题色/14sp），393/394 双版本通用；小黑盒类不可用时自动回退系统 AlertDialog
- 修复图片系统分享：图片改为优先保存到系统相册（MediaStore，可真正查看/分享），
  失败回退 FileProvider（外部缓存目录）；自动识别 jpg/png/gif/webp/bmp 真实格式并修正 MIME；
  下载请求带 UA/Referer 防 CDN 防盗链

## 0.4.0

### 双版本兼容（1.3.393 / 1.3.394）

- 版本前置检测放行 `1.3.393` 与 `1.3.394` 两个版本（`MainModule.SUPPORTED_HEYBOX_VERSIONS`）
- 设置页入口：Hook 目标方法 `GeneralSettingsActivity.G1()`（393）自动回退 `L1()`（394）；
  ViewBinding 识别 `fi.r0`（393）与 `hi.r0`（394）
- 图片系统分享：本地分享回调接口 `un.a`（393）自动回退 `wn.a`（394）；
  `kotlin.b2` 单例字段 `f140421a`（393）自动回退 `f140881a`（394）；
  `HBShareDialog` 动作字段 `f83135h`（393）自动回退 `f83116h`（394）

## 0.3.1

### 工程重构：按功能拆分 Hook
- `MainModule.java` 精简为入口：生命周期 + Hook 安装编排 + 共享工具（开关读取 / 日志 / dp）
- 各功能 Hook 拆分到 `hooks/` 子包：
  - `GeneralHook`      通用（版本检测 / 屏蔽更新）
  - `AdFilterHook`     广告过滤（开屏 / 信息流 / 气泡 / 角标）
  - `SettingsEntryHook` 设置页入口注入 + 内嵌设置面板
  - `BottomTabHook`    底部导航栏隐藏
  - `PromotePostHook`  推广贴屏蔽
  - `TextSelectHook`   解除复制 / 标准文本选择 / 跨行选择
  - `ImageShareHook`   图片系统分享

### 界面
- 小黑盒内嵌设置面板底部新增版本号显示（读模块 APK versionName，与独立设置页一致）

### 日志开关
- 新增「记录日志」开关（内嵌设置面板与独立设置页均可切换，默认关闭）
- 开启后自动把模块运行日志写入文件：
  小黑盒进程 `/data/data/com.max.xiaoheihe/files/betterheybox/log.txt`，
  模块进程 `/data/data/com.better.heybox/files/betterheybox/log.txt`，
  单文件超 512KB 自动滚动为 log.1.txt
- 独立设置页开启开关后显示日志文件路径

### 架构调整：小黑盒内设置不再跨进程
- 内嵌设置面板开关改由**本进程直读直写**，配置文件存放在小黑盒应用目录：
  `/data/user/0/com.max.xiaoheihe/shared_prefs/betterheybox.xml`
- 保留可用通道：小黑盒内面板走本进程本地配置；模块独立设置页继续直连框架
  RemotePreferences（实测可用），两条通道各自即时生效
- 不再依赖「广播 → 模块进程 → RemotePreferences」写回链

## 0.2.1

### 内嵌设置与主题
- 小黑盒设置页入口改为直接渲染 BetterHeybox 面板，不再跳转独立设置 Activity
- 设置界面版本号改为读取 APK Manifest，与 CI/Release 工作流构建版本自动同步
- 修复内嵌面板因跨应用 Theme 资源混用导致的 `Resources$NotFoundException`
- 支持小黑盒深色模式「打开 / 关闭 / 跟随系统」三态读取
- 深色模式使用黑底白字，浅色模式使用白底黑字；切换后自动刷新入口和面板颜色
- 入口注入前读取当前主题状态，避免设置页重载时出现颜色闪烁
- 开关与退出按钮改用 Android 系统 Monet 色板取色

### 通用
- 增加小黑盒目标版本 `1.3.393` 检测提示
- 增加可选的「屏蔽更新」开关

## [0.2.0]

首个可分发版本。

### 广告过滤（开关即时生效）
- 开屏广告：拦截 `module.ads.e.g()`
- 信息流广告：Gson 反序列化阶段过滤 `content_type=23` 条目
- 气泡广告：拦截 `module.ads.h.l()` 展示检查
- 角标广告：阻断 `module.ads.h.h()` 广告拉取
- 推广贴：屏蔽 `content_type` 28/29 及指定官方账号帖子

### 界面增强
- 底部导航栏屏蔽（需重启生效）：首页 / 热点 / 游戏库 / 加号，隐藏加号联动隐藏推荐占位
- 小黑盒「设置」页注入 BetterHeybox 设置入口（复用 SettingItemView 样式）

### 帖子增强
- 解除防复制，恢复系统标准文本选择（长按弹「复制/全选」）
- 修复拖动选择只能拉一行（选择时放行滚动容器触摸拦截）

### 通用
- 设置页「立即重启」：杀小黑盒后台进程 + LSPosed 热重载（无 root）
