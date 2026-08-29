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

如果使用 NPatch Manager 模式而不是把模块内嵌到 APK，管理器可能显示“未检测到内嵌模块”；此时只需在 NPatch 中确认 BetterHeybox 已对小黑盒启用。

## Shizuku+

Shizuku+ 是可选项，主要解决无 Root 下“可靠重启小黑盒”的问题。

1. 安装并启动 Shizuku+ 服务。
2. 如果使用 Shizuku+ 独立包名版本，安装其 Compat Hub，让普通 Shizuku 客户端可以取得 Binder。
3. 打开 BetterHeybox → `授权 Shizuku+` → 允许。
4. 此后 `可靠重启小黑盒` 会优先执行 `am force-stop`，然后重新启动小黑盒。

权限后端优先级：

```text
Shizuku / Shizuku+ > Root su > KILL_BACKGROUND_PROCESSES 普通兜底
```

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

## NPatch Remote API

NPatch Local 模式下，模块 App 有时不会收到标准 `XposedServiceHelper` 回调。BetterHeybox 会自动尝试 `top.nkbe.npatch.remote` Provider，取得同一份 libxposed API 102 `IXposedService`，用于 RemotePreferences。

如果管理器显示 `NPatch Remote Provider 可见` 但 `设置服务 未连接`，请：

1. 确认 NPatch 为支持 Remote API 的新版本。
2. 打开一次 NPatch Manager。
3. 再回 BetterHeybox 点击“重新连接设置服务”。
4. 如果 NPatch 拒绝模块身份请求，管理器会显示简短错误信息；核心 Hook 是否运行仍以 NPatch 中的模块启用状态为准。

## 安全边界

- Shizuku+ 不参与广告过滤、解除复制、视频 URL 捕获、UI 注入等功能，这些仍由 libxposed Hook 完成。
- BetterHeybox 不会用 Shizuku 修改小黑盒私有数据目录。
- NPatch 状态检测只读取已安装 APK 的 NPatch manifest metadata / assets，不修改 APK。
