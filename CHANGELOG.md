# Changelog

本项目版本号在 `gradle.properties` 统一管理（`VERSION_CODE` / `VERSION_NAME`）。
发布时递增 `VERSION_CODE` 并打 tag（如 `v0.2.0`），CI 会自动构建并发布 Release。

## [Unreleased]

## [0.2.0] - 未发布

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
- 不显示桌面图标（组件级禁用，可从小黑盒设置页入口进入）
- 设置页「立即重启」：杀小黑盒后台进程 + LSPosed 热重载（无 root）
