# AMTool Next

AMTool 1.x 的现代化重写分支。目标宿主为 **Apple Music Android 6.5.3 (1599)**。

## 当前里程碑：2.0.0-alpha1

- libxposed API **102.0.0** 是唯一运行时 Hook 引擎。
- YukiHookAPI 更新到 **1.3.2**，只保留为旧 AMTool 规则迁移兼容层，避免与现代 API 双重接管生命周期。
- KavaRef **1.1.0**。
- DexKit **2.2.0** 预留给未知版本语义回退。
- 精确适配 Apple Music 6.5.3 / 1599。
- 保持 Türkiye storefront、账户、订阅与播放授权不变。
- 歌词候选优先 `zh-Hans-CN -> zh-Hans -> zh-CN`。
- 歌曲/专辑等目录元数据请求使用 `l=zh-CN`；未修改 storefront。
- API 102 RemotePreferences 热重载；Hook 本身只安装一次。

## 安全边界

本模块不会修改 DSID、entitlement、subscription、purchase、playback URL 或账户 storefront。

## 下一阶段

1. 把 AMTool 1.1 的歌词模糊/视觉逻辑迁到现代 Hook 生命周期。
2. 增加显式的中文标题 -> 英文标题 -> 土区原名三级 Metadata Bridge，而不是仅依赖 Apple 服务端默认回退。
3. DexKit 语义定位替代仅 1599 的硬 Profile，并保留精确 Profile 作为快速路径。
4. 当前歌词页配置变更后的主动无闪烁刷新。
