# BetterHeybox Rootless

BetterHeybox 的核心功能是 **进程内 Hook**。无 Root 模式使用 NPatch 提供 libxposed 运行环境；Shizuku / Shizuku+ 只作为进程控制增强，不替代 Xposed Hook。

## 推荐架构

```text
BetterHeybox Manager（桌面入口）
        │
        ├─ 设置：libxposed service / NPatch Remote API
        ├─ 进程控制：Shizuku+（可选）
        │
        └─ Hook：NPatch（无 Root）或 LSPosed（Root）
                         │
                         └─ com.max.xiaoheihe
```

## 无 Root 安装

NPatch 无 Root 模式要求 Android 9（API 28）及以上；Android 8/8.1 仍可在 Root 环境使用 LSPosed 路线。

1. 安装 NPatch。
2. 在 NPatch 中选择小黑盒 `com.max.xiaoheihe` 进行本地修补。
3. 把 BetterHeybox 模块加入本次修补。
4. **破解签名校验选择 `Extreme`**。BetterHeybox 已知在低级别签名绕过下可能出现参数缺失/闪退。
5. 安装修补后的小黑盒。
6. 启动小黑盒一次，再打开桌面的 BetterHeybox。
7. 管理器应显示：
   - 小黑盒 NPatch 注入：已检测到
   - 签名绕过：Extreme（或更高模式）
   - 设置服务：libxposed 或 NPatch Remote
   - 小黑盒运行时可看到 `实际 Hook` 与 API 102 目标进程状态

如果使用 NPatch Manager 模式而不是把模块内嵌到 APK，管理器可能显示“未检测到内嵌模块”；此时只需在 NPatch 中确认 BetterHeybox 已对小黑盒启用。

## Shizuku+

Shizuku+ 是可选项，主要解决无 Root 下“可靠重启小黑盒”的问题。

1. 安装并启动 Shizuku+ 服务。
2. 如果使用 Shizuku+ 独立包名版本，安装其 Compat Hub，让普通 Shizuku 客户端可以取得 Binder。
3. 打开 BetterHeybox → `授权 Shizuku+` → 允许。
4. 此后 `可靠重启小黑盒` 会优先执行 `am force-stop`，然后重新启动小黑盒。

权限后端优先级：

```text
Shizuku / Shizuku+ > Root su > Android 13- 兼容后台结束
```

Android 14 开始，第三方 App 的 `killBackgroundProcesses()` 只能结束自身进程。因此 BetterHeybox 在 Android 14+ **不会假装普通权限重启成功**：没有 Shizuku 或 Root 时会直接提示需要高权限后端。

## Root 用户

Root 用户无需 NPatch，可继续：

1. 安装 BetterHeybox。
2. 在支持 libxposed API 102 的 LSPosed 中启用模块。
3. 作用域保持小黑盒。
4. 通过桌面 BetterHeybox 管理设置，或继续使用小黑盒内嵌设置页。

## 设置同步

现在存在两个设置入口：

- 桌面 BetterHeybox Manager
- 小黑盒 → 设置 → BetterHeybox 设置

两边每次写入都会记录时间戳，Hook 侧比较本地与 RemotePreferences 的更新时间，**最后一次修改的值生效**。这样既保留内嵌设置在目标进程内直接写入的可靠性，也让无 Root 桌面管理器可以正常控制功能。

桌面管理器写入 RemotePreferences 使用同步 `commit()`；后端不可用或提交失败时会保存到本地待提交队列，等 libxposed / NPatch Remote 建立后再补交，避免切换开关后静默丢配置。

## NPatch Remote API

NPatch Local 模式下，模块 App 有时不会收到标准 `XposedServiceHelper` 回调。BetterHeybox 会自动尝试 `top.nkbe.npatch.remote` Provider，取得同一份 libxposed API 102 `IXposedService`，用于：

- RemotePreferences 读写
- 查询当前实际 Hook 的目标进程
- 显示 NPatch 框架版本 / API 版本

连接失效后 BetterHeybox 会丢弃旧 Binder 并自动尝试重连。

如果管理器显示 `NPatch Remote Provider 可见` 但 `设置服务 未连接`，请：

1. 确认 NPatch 为支持 Remote API 的新版本。
2. 打开一次 NPatch Manager。
3. 再回 BetterHeybox 点击“重新连接设置服务”。
4. 如果 NPatch 拒绝模块身份请求，管理器会显示简短错误信息；核心 Hook 是否运行仍以 `实际 Hook` 状态与 NPatch 模块配置为准。

## NPatch 状态检测

管理器会读取已安装小黑盒 APK 的 NPatch 标记：

- manifest metadata `npatch`
- `assets/npatch/config.json`
- `assets/npatch/modules/`

它会解析 `sigBypassLevel` / `useManager`，但不会修改目标 APK。

## 安全边界

- Shizuku+ 不参与广告过滤、解除复制、视频 URL 捕获、UI 注入等功能，这些仍由 libxposed Hook 完成。
- BetterHeybox 不会用 Shizuku 修改小黑盒私有数据目录。
- Shizuku 只用于用户主动点击的进程控制操作。
- NPatch 状态检测只读取已安装 APK 的 NPatch manifest metadata / assets，不修改 APK。
