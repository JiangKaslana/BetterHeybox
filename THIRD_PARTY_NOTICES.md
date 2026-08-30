# Third-Party Notices

BetterHeybox uses or interoperates with the following open-source projects.

## libxposed API / service

- Projects: `libxposed/api`, `libxposed/service`
- License: Apache License 2.0
- Role: module Hook API and API-102 RemotePreferences/service contract.

## DexKit

- Project: `LuckyPray/DexKit`
- License: Apache License 2.0
- Role: bytecode feature analysis used to relocate selected obfuscated Heybox classes/methods.

## Liquid Glass

- Projects: `QWEA0/Liquid-Glass-Android`, `QmDeve/AndroidLiquidGlassView`, and the upstream BetterHeybox port based on `sjtt2/HeyBox-LiquidGlass`
- Maven artifact: `com.github.QWEA0:liquidglass:90f4ea28e3`
- License: MIT for the referenced/rendering projects as documented by upstream.
- Role: optional liquid-glass bottom navigation rendering and AGSL/CPU fallback implementation.

## Shizuku API

- Project: `RikkaApps/Shizuku`
- Maven artifacts: `dev.rikka.shizuku:api:13.1.5`, `dev.rikka.shizuku:provider:13.1.5`
- License: Apache License 2.0
- Role: optional rootless privileged process bridge.

BetterHeybox uses only the standard Shizuku client API. Shizuku+ compatibility is provided by Shizuku+'s standard-API compatibility layer / Compat Hub; BetterHeybox does not vendor private Shizuku+ APIs.

## NPatch

- Project: `7723mod/NPatch`
- License: GPL-3.0 for the NPatch application/framework itself
- Role: external rootless libxposed injection framework. NPatch is not embedded or redistributed by BetterHeybox.

## NPatch Remote API

- Project: `7723mod/NPatch-Remote-API`
- License: Apache License 2.0
- Role: authenticated bridge from a standalone module application to NPatch's API-102 `IXposedService`.

`app/src/main/java/top/nkbe/npatch/remote/NPatchRemoteClient.java` is a reduced and modified compatibility implementation adapted from the public NPatch Remote API client. It retains only the RemotePreferences functionality needed by BetterHeybox and has been adjusted for BetterHeybox's Android 8+ minimum SDK.

## License URLs

The authoritative license texts remain available in the corresponding upstream repositories. Apache-2.0 and MIT licensed code modified or vendored in this repository retains attribution in source comments and in this notice.
