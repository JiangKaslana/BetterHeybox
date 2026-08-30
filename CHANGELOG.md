# Changelog

本项目版本号采用 `v主版本.次版本.修订版本` 格式。推送代码时，CI 会构建带日期和当日序号的版本并将 APK 保存在 Action 成品中；Release 工作流手动输入版本号后创建正式 Release，并在说明中列出 commit 更新内容。

## 0.6.0

### 新增：液态玻璃底栏

- 完整移植自 [sjtt2/HeyBox-LiquidGlass](https://github.com/sjtt2/HeyBox-LiquidGlass)：
  底部导航栏渲染为实时折射/色散的「液态玻璃」效果，带玻璃水滴选中动画
- **渲染链**：QWEA0 LiquidGlassTabBar+ AGSL shader；Android 13（API 33）及以上启用，Android 12 及以下自动回退 CPU 毛玻璃，外部库加载失败逐层降级，不拖垮其它 Hook
- **长按入口两处**：首页标题栏右上角图标、
  设置页「通用设置」行长按 → 打开**顶部锚定限高**的调节面板，面板打开期间底栏全程可见、调节实时生效
- **面板可调**：深/浅底色、不透明度、高度、距底部偏移、
  沉浸式小白条、自适应反色、宽度自适应开关
- **沉浸式手势条**：透明导航栏 + 移除导航 inset，玻璃条延伸进手势区，
  关闭时完整还原窗口状态
- **自适应反色**：标签/图标颜色随背景亮度实时反色  
- **与底栏隐藏联动**：隐藏 tab 后玻璃条同步收缩、剩余等分；
  宽度自适应开启时选中项加长  
  水滴动画预置落地，切换无回跳
- 开关需重启小黑盒生效

### 新增：网页 DevTools

- 新增「网页 DevTools」开关：为小黑盒内置 WebView 开启 Chrome 远程调试，电脑 chrome://inspect
  可调试内置网页

### 新增：打开网页

- 设置页新增「打开网页」行：输入 http/https 地址后用小黑盒内置浏览器打开，URL 存配置并纳入配置备份

### 变更：底部导航栏隐藏优化

- 隐藏 tab 后剩余 tab 自动等分填满整条（原生底栏与玻璃条同步重算，
  宿主异步改权重也按 100/500/1500/3000ms 重算兜底）
- 当前选中的 tab 被隐藏时自动切换到第一个可见 tab
- 与液态玻璃底栏联动：隐藏/开关切换热生效

### 变更：视频下载

- 液态玻璃调节面板打开期间自动隐藏窗口级下载悬浮按钮，
  面板关闭后立即恢复，避免遮挡调节

### 变更：配置备份

- 液态玻璃全部设置纳入配置导出/导入；
  玻璃相关开关纳入重启生效处理

## 0.5.0

### 新增：设置页自动分析（DexKit）

- 引入 [DexKit](https://github.com/LuckyPray/DexKit) 2.2.0（`org.luckypray:dexkit`），
  小黑盒更新打乱混淆名后，设置相关目标自动重新定位，无需等模块发版适配
- **原生弹窗自动定位**：以 HeyBoxDialog 内的品牌常量字符串为锚点定位对话框类；
  Builder 的标题/正文、顶/中/整替换 View 槽位、正向/负向按钮全是同签名混淆方法，
  用 alpha=0 的「隐形探针」构造临时弹窗、按各标记的实际渲染位置自动分类
  （394 实测解析出 title=B view=i pos=x neg=r build=J，与反编译一致）
- **解析结果按宿主版本缓存**（写入小黑盒 files 目录）：同版本二次启动零扫描、弹窗即开；
  首次解析约 1 秒（后台线程，不卡界面）
- 覆盖弹窗全部切换为自动解析通道：分享渠道、链接编辑（3 个）、导入确认、保存位置；
  任一层失败自动回退系统弹窗，核心开关不受影响
- **设置页启发式定位**：binding 字段按「实现仅含一个零参返回 View 方法的接口」形态判定
  （宿主 R8 会把 androidx.viewbinding.ViewBinding 重命名成 y3.c，不能依赖原名）、
  列表 getter 按签名解析（优先取已挂载到窗口树的实例）、入口混淆名（G1/L1）失效时
  自动回退沿继承链 hook `onResume` 并复用既有 20×50ms 重试机制

### 变更：APK 体积

- release 启用 R8 + 资源裁剪：自有代码整包 keep（Hook 定位、广播常量、入口清单不受影响），
  仅裁剪第三方依赖未用代码
- 剔除 x86(32位) native 库（-400KB）：32 位 x86 实机已绝迹，模拟器均用 x86_64；
  缺对应 ABI 时弹窗自动降级系统样式，核心功能不受影响
- jniLibs 保持不压缩 + 页对齐存储（LSPosed 以 InMemoryDexFile 加载模块时
  native 库需从 `base.apk!/lib` 直接 dlopen，压缩存储会 UnsatisfiedLinkError）

### 变更：设置面板文案与弹窗

- 开关行支持「标题 + 下方灰色小字介绍」的原生样式：`setTitleDesc` 只写文本，
  描述 TextView 默认 GONE，可见性开关（混淆方法 `f(boolean)`）用一次性探针视图
  运行时自动解析，混淆改名免疫
- 介绍文案按行取舍：视频下载 / 解除复制 / 伪装通知权限 / 屏蔽更新 保留介绍，
  其余行（广告过滤、隐藏底栏、日志、配置、链接净化、每日任务等）为纯标题
- 「保存位置」确认弹窗改用小黑盒原生弹窗（选择其他文件夹 / 恢复默认），
  失败回退系统弹窗；文件夹显示名优先 DocumentsProvider，回退取 URI 末段

## 0.4.5

### 新增：净化分享链接

- 新增「分享净化 → 净化分享链接」开关（默认开启，即时生效，无需重启小黑盒）：
  复制链接 / 分享到 QQ、微信等渠道时，自动去掉小黑盒链接上的追踪参数
  （实测携带的 h_camp、h_session_id、h_src、new_post_share_style，
  以及 share_app_id、share_strategys、sh_from、web_sign 等，含全部 utm_*），
  只保留内容本身：`...web/share?h_camp=link&h_session_id=xxx&link_id=abc&new_post_share_style=true`
  → `...web/share?link_id=abc`
- 仅处理 xiaoheihe.cn 域名链接，第三方链接原样放行；link_id / id / hkey 等功能参数
  保留，净化后的链接照常打开；实际去除的参数会记录到模块日志，便于核对
- 覆盖全部分享出口：复制链接、QQ/微信/微博等社交分享、系统分享统一经过的
  分享 model `getShareUrl()`
- 配置导入/导出补齐缺失的开关：视频下载、自动转存 MP4（随本次一并加入备份）

### 变更：正式版日志精简

- 正式版（Release）只保留 error 级日志：模块的 info/warn 日志不再输出到
  LSPosed 日志与 logcat，导出的文件日志同样只含错误；Debug 构建不受影响，
  仍全量输出（含运行状态检查点）
- 各处直接调用 `android.util.Log` 的日志统一收敛到新出口 `Logs`，应用同一过滤策略
- Hook 安装失败原本只写检查点（Release 下不可见），现补记 error 日志，正式版可排查

### 新增：小黑盒视频下载

- **下载入口**：视频帖右上角圆形 Monet 渐变悬浮按钮，跟随视频位置自适应；
  空闲（下载图标）→ 下载中（外圈实时进度环）→ 完成（✓）→ 失败（重试图标）四态切换；
  按压缩放 + Ripple 反馈，与播放器 UI 融合
- **底部下载面板**：顶部 28dp 圆角 + 拖动指示条 + Monet surface 背景，支持下拉关闭/点外部关闭；
  按任务状态自适应（准备 / 下载中 / 暂停 / 完成 / 失败），实时显示百分比、大小与速度；
  下载可随时转后台，悬浮按钮持续显示进度
- **全类型视频支持**：信息流 / 正文 / 故事 / 游戏卡片；mp4 直链与 HLS（m3u8）分片流均可下载；
  加密 HLS 自动拒绝；Steam 等第三方卡片预告片不显示入口
- **断点续传**：mp4 走 HTTP Range 续写，HLS 跳过已下载分片；进程被杀后任务恢复为已暂停可续传
- **自动转封装 MP4**：HLS 合并后无损转封装为 MP4（可关闭，失败保留 ts）
- **帖子标题命名**：文件名优先取帖子标题，HLS 通用名自动回退时间戳；重名自动 `(n)` 不覆盖
- **保存位置**：默认 `Movies/BetterHeybox`；设置「保存位置」调起系统文件选择器选择任意文件夹
  （SAF 免存储权限），完成通知显示实际保存路径
- **通知栏**：进度（暂停/取消）、完成（播放/分享/删除 + 保存路径）、失败（原因/重试）
- **设置项**：「视频下载」分组新增 下载开关 / 保存位置 / 自动转存 MP4

### 修复

- 修复单参数重载 Hook 因参数越界异常导致捕获全部丢失的问题（`getArgs()` 按实际个数取参）
- 修复通知缺少 smallIcon 被系统拒收、下载管理器未初始化导致下载静默失败的问题
- 修复暂停/继续竞态（双线程写断点引发「临时文件缺失」「cancelled」失败）：
  任务级运行锁 + 运行代际号，旧代线程静默退出
- 修复 HLS 合并文件误随分片目录删除导致必现「临时文件缺失」的问题
- 同一视频在不同入口（清晰度/页面）重复建任务：按 link_id 归并去重
- 下载进行中列表/面板点击失效：进度刷新改为原地更新，不再整列表重建

## 0.4.1(未公开)

### 新增：Debug 版运行检查点 + 导出模块日志

- **Debug 版检查点（检查运行情况）**：
  - 新增 BuildFlags（debug/release 源集隔离）与 Checkpoint 运行时检查点设施，
    Debug 构建在模块生命周期（模块进程启动 / 服务绑定 / 断开）、模块加载、
    目标进程命中、各功能 Hook 安装等关键节点打点，记录相对启动耗时、pid、进程与线程
  - 检查点同时输出到 logcat（tag=BHX-CKPT）与文件日志（「记录日志」开启时），
    Release 构建全部为空操作，零日志噪音
  - 小黑盒进程 Hook 安装完成后把检查点快照写入 RemotePreferences，
    设置页可跨进程查看「小黑盒进程内 Hook 装到哪一步、各模块耗时多少」
  - 设置页新增「运行状态」入口（仅 Debug 构建显示）：弹窗展示模块进程检查点
    + 小黑盒进程运行状态 + 框架服务连接情况

- **新增：导出模块日志**：
  - 设置页「通用」分组新增「导出日志」：系统「保存到」选择器导出为文本文件（免存储权限），
    文件名按 yyMMdd_HHmmss 时间戳生成（如 BetterHeybox日志_260827_235530.txt）
  - 导出内容 = 运行状态头部（版本 / 构建类型 / 设备 / 框架服务）+ 小黑盒进程检查点
    + 模块进程检查点 + 模块日志 log.txt（含滚动备份 log.1.txt），一份文件覆盖完整运行链路
  - **内嵌小黑盒设置页同步新增「导出日志」/「运行状态」**（通用分组）：
    内嵌页跑在小黑盒进程，导出的日志直接包含小黑盒进程检查点与小黑盒侧日志文件；
    「运行状态」仅 Debug 构建显示
  - **前台打开记录**：Debug 构建下每次小黑盒打开到前台（冷启动 / 从后台回到前台）
    记录「应用打开（前台）」检查点，划到后台不产生记录；应用内页面跳转不重复记录

### 工程调整

- CI 工作流（push 触发）改为构建 **Debug APK**（assembleDebug），
  产物自带运行检查点（BuildFlags.DEBUG），安装即排查运行情况；
  Debug 构建由 AGP 自动签名，不再依赖 keystore Secrets
- Release 工作流（手动触发）保持构建正式 Release 不变

## 0.4.0

### 新增：配置导入 / 导出

- 模块设置页新增「配置备份」分组（导出配置 / 导入配置）：
  - 导出：系统「保存到」选择器写 JSON 文件（免存储权限），默认文件名
    BetterHeybox配置_yyyyMMdd_HHmmss.json，内容带缩进便于人工查看/修改
  - 导入：覆盖确认后从 JSON 文件恢复全部设置，成功后自动刷新开关显示；
    若改动了底栏屏蔽开关，提示「重启小黑盒生效」
- 小黑盒内嵌设置面板同步新增「配置备份」分组（导出 / 导入），
  导入确认弹窗复用小黑盒原生 HeyBoxDialog，类不可用时自动回退系统弹窗
- 两处入口共用同一 JSON 格式（format: betterheybox-config，v1），
  模块设置页与内嵌面板导出的文件可互相导入
- 覆盖全部用户配置：15 个开关 + 4 项字符串（分享链接 / 分享渠道），
  空值原样导出保证「导出 → 导入」与当前值完全一致；今日打卡等运行态数据不参与导出
- 导入写回走双通道（模块进程 RemotePreferences + 待提交缓存 /
  小黑盒进程 HeyboxPrefs 本地 + 广播镜像），框架服务未连接也不丢设置

### 工程与依赖升级

- Gradle wrapper 9.5.1 → 9.7.1（与 AGP 9.2.1 兼容，支持 JDK 17–26）
- CI / Release 工作流 Action 全面升级，全部切换为 node24 运行时，
  消除 GitHub 弃用 Node 20 的警告：
  - actions/checkout v5 → v7
  - actions/setup-java v5 → v6（CI JDK 17 → 21 LTS）
  - gradle/actions/setup-gradle v5 → v6
  - actions/upload-artifact v5 → v7
  - softprops/action-gh-release v3 → v3.0.2

## 0.3.5

### 修复：394 设置 UI 3 倍大小问题

- **根因**：内嵌设置面板与设置入口使用**硬编码资源 ID**（如 `0x7f0700ff` 作为行高），
  资源 ID 每个 APK 各自生成，394 中该 ID 指向 `item_touch_helper_swipe_escape_velocity=120dp`，
  导致行高约 3 倍异常
- **修复**：行高/字号改用 `module.dp()` 计算值；颜色/图标等资源改为**按资源名解析**
  `getIdentifier`

### 每日任务自动化 + 图片分享修复

- 新增「自动完成每日分享任务」：自动完成小黑盒每日任务的 **3 种分享类型**——
  图片帖分享（PicturePostPageActivityV2）→ 普通帖分享（NormalPostPageActivity）→ 游戏分享（ChannelsDetailActivity）；
  通过 Hook `ShareUtils.P/y` 触发 `HBShareData.shareListener.onResult` 完成，**不拦截 QQ 分享入口**
- 新增 3 个分享链接设置（图片帖 / 普通帖 / 游戏，各自独立配置）：小黑盒内嵌设置面板与独立设置页均可编辑，
  支持 api.xiaoheihe.cn / xiaoheihe.cn / heybox:// 链接，经小黑盒 RouterActivity 自动路由；
  未配置的类型自动跳过；每日状态按日期记录，跨天重置
- 链接编辑弹窗复用小黑盒原生 **HeyBoxDialog**（`com.max.hbcommon.view.d$i`，仿 SettingActivity「修改版本号」输入弹窗，
  含 `bg_dialog_edit` 背景/主题色/14sp），393/394 双版本通用；小黑盒类不可用时自动回退系统 AlertDialog
- 新增「伪装通知权限」开关：Hook 框架 `NotificationManager.areNotificationsEnabled()` 恒返回 true，
  让小黑盒认为通知已开启，获得签到加成（不真正申请权限、不弹系统权限框）；双版本通用
- 修复图片系统分享：图片改为优先保存到系统相册（MediaStore，可真正查看/分享），
  失败回退 FileProvider（外部缓存目录）；自动识别 jpg/png/gif/webp/bmp 真实格式并修正 MIME；
  下载请求带 UA/Referer 防 CDN 防盗链
- 修复长文正文进入时**文字短暂消失**：解除复制的 `setTextIsSelectable(true)` 会重建 Editor 并触发长文
  Spannable 重排导致闪烁；改为「布局就绪（已显示且有尺寸）才应用 + 幂等跳过已开启的 TextView」
- 底栏 tab 名称改为**版本自适应**：按小黑盒字符串资源（discover/game_store/bbs）运行时动态解析
  （发现/游戏库/社区），内嵌面板/独立设置页/Hook 日志统一显示真实 tab 名
- 修复原生 HeyBoxDialog 链接编辑弹窗「保存/取消」不关闭：按钮回调需手动 `dialogInterface.dismiss()`

## 0.3.2

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
