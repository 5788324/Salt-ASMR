# Salt-ASMR 技术评审与证据记录

> 核查日期：2026-11-17。汇总本轮独立评审及交叉核查，不将模型回答本身作为技术证据。本次未运行 Windows Salt 宿主，未验证实际播放行为。

## 决策

继续到 P0，暂不投入完整作品库 UI。推荐独立 RJ 数据模型、官方 API 与范围受控的内部播放适配；首版独立窗口为候选，主导航注入后置。P0 通过标准见 [ROADMAP](ROADMAP.md)。

## 版本与取证范围

- [官方默认分支取证提交 cbf2037](https://github.com/Moriafly/spw-workshop-api/commit/cbf20375b95b42d95c3fb16f1ceb53f4befc205c)。
- [dev23 Release](https://github.com/Moriafly/spw-workshop-api/releases/tag/0.1.0-dev23) 已发布，说明主要涉及 Compose 1.12.1 与 Salt UI 3.0.0-beta02。
- 默认分支依赖配置仍标注 dev22，不代表最新发布版。另读 dev23 标签下两个关键文件，内容哈希与上述默认分支一致：
  - WorkshopApi.kt：757597b0dc448312f05436afd1421106e1694976。
  - PlaybackExtensionPoint.kt：bd9d564c0ffdafa9be999d54ecc5740ba4b0bf41。
- [开发指南](https://saltplayer.com/workshop/getting-started) 使用 JDK 25、Kotlin 2.4.20；Compose 编译与宿主版本匹配。构建产物、API jar 与宿主兼容仍需 P0 验证。

## 已核实的公开接口

以下链接固定到 dev23 源码；API 提供契约不代表 Salt-ASMR 已实现功能。

| 需求 | 源码结论 | 限制 |
|---|---|---|
| 基础控制 | Playback 有 play、pause、previous、next、seekTo | 没有路径 / ID 起播，没有队列读写 |
| 曲库查询 | Library 有 getTrackById、getAllTracks、getTracks、getCoverById | LIBRARY_READ；快照只读，分页跨页不一致 |
| 当前歌曲 | getCurrentMediaItem | 同步等待数据库，应后台调用；未入库行为待测 |
| 位置、结束状态 | onPositionUpdated 每秒一次；onStateChanged 有 Ended | 无音轨身份参数，无原子化曲目与位置快照，无独立切轨事件 |
| 歌词提供 | onBeforeLoadLyrics / onAfterLoadLyrics 返回 String?，IO 线程执行 | 默认优先用后备扩展；歌词加载不是可靠切轨事件 |
| 歌词读取 | getLyricsLines / onLyricsLinesUpdated | 空列表不区分加载中、无歌词和失败；不提供主动重载入口 |
| 歌曲 ID | MediaItem.id | 不承诺曲库重建或跨设备稳定；歌词回调新增元数据可能为默认值 |
| UI | Ui 仅 toast；HookRegistrar 可修改方法与 Compose 调用 | 无稳定页面 / 主导航注册接口 |
| 曲库写入 | LIBRARY_WRITE 预留 | 没有已提供的写入方法 |

源码：
- [WorkshopApi.kt](https://github.com/Moriafly/spw-workshop-api/blob/0.1.0-dev23/api/src/main/java/com/xuncorp/spw/workshop/api/WorkshopApi.kt)
- [PlaybackExtensionPoint.kt](https://github.com/Moriafly/spw-workshop-api/blob/0.1.0-dev23/api/src/main/java/com/xuncorp/spw/workshop/api/PlaybackExtensionPoint.kt)
- [PluginPermission.kt](https://github.com/Moriafly/spw-workshop-api/blob/cbf20375b95b42d95c3fb16f1ceb53f4befc205c/api/src/main/java/com/xuncorp/spw/workshop/api/PluginPermission.kt)
- [HookRegistrar.kt](https://github.com/Moriafly/spw-workshop-api/blob/cbf20375b95b42d95c3fb16f1ceb53f4befc205c/api/src/main/java/com/xuncorp/spw/workshop/api/hook/HookRegistrar.kt)
- [官方 example](https://github.com/Moriafly/spw-workshop-api/tree/cbf20375b95b42d95c3fb16f1ceb53f4befc205c/example)

[SPL 标准](https://moriafly.com/standards/spl) 定义 LRC 兼容、译文识别与显式结束时间，支持将离线双语字幕转换后提供给宿主；具体文件、语言切换和长音频仍需测试。当前规范分钟字段为 1～3 位，不能无限外推时长支持。

## 内部实现参考：存在代码，不等于已验证可复用

| 来源 | 已读的实现 | 证据边界 |
|---|---|---|
| [Listen Stats / HostSongPlayback](https://github.com/Jethuit/SPW-Listen-Stats/blob/7dd2822772ca7c0ad742d34576b5c3755691af73/plugin/src/main/kotlin/com/jethuit/spw/listenstats/ui/HostSongPlayback.kt) | 反射构造 PiscesMediaItem，调用 PlaybackController 的 addToPlay、prepare、play；另有 addToPlayNext | 该实现查询已入库歌曲，不能证明宿主只能播放入库文件，也不能证明完整队列已受控 |
| [网易云插件 / VoxzenBridge](https://github.com/FUFUTPR/spw-netease-plugin/blob/755818268684927b8a7e4ac37d24e75a4aa438d8/src/main/java/com/example/netease/host/VoxzenBridge.java#L940) | 反射调用 setPlaybackQueue(List,int)，随后 prepare、play | 在线歌曲实现；整单失败会退回单曲。Salt-ASMR 必须分别报告单轨与整单结果 |
| [Listen Stats / PlaybackTracker](https://github.com/Jethuit/SPW-Listen-Stats/blob/7dd2822772ca7c0ad742d34576b5c3755691af73/plugin/src/main/kotlin/com/jethuit/spw/listenstats/data/PlaybackTracker.kt) | 事件串行化、生命周期代次、过期结果和非原子快照保护 | 可借鉴身份关联设计，不照搬完整统计功能 |
| [标签工作台 / HostHookPoints](https://github.com/univers629/SPW-Tag-Workbench/blob/afde95fd7cd136a6fc9ddb1216645ab4c4c0c2d2/src/main/kotlin/io/github/univers629/spwtagworkbench/backend/host/HostHookPoints.kt) | 记录 1.19.2 到 1.19.3 的 UI 点位重排 | 第三方记录，证明存在升级维护案例，不能推导每次升级必崩溃 |
| [SPW-Lyrics / SpwLyricsExtension](https://github.com/GaBoron/SPW-Lyrics/blob/78a17ff6873bfc49005eed1e542c50e5adb6691e/src/main/kotlin/dev/gaboron/spwlyrics/integration/SpwLyricsExtension.kt) | 正式歌词回调接入 | 歌词路径可参考；内部主动重载实现仍非官方 API |

上述 Listen Stats、网易云插件、SPW-Lyrics 的仓库 LICENSE 为 MIT；标签工作台相关文件标注 GPL-3.0-only；官方 API / example 为 Apache-2.0。实际复制前重新核对具体文件及依赖声明，公开代码不等于可任意复制。

## 上游需求与 Hook 边界

- [Issue #19](https://github.com/Moriafly/spw-workshop-api/issues/19) 请求队列、模式及变更事件，作者说明插件依赖内部反射。Issue 中的方法是提案，不能列入现有 API。
- [PR #27](https://github.com/Moriafly/spw-workshop-api/pull/27) 引入方法与 UI Hook，不等于提供稳定播放队列接口。
- [Hook 官方文档](https://saltplayer.com/workshop/hook) 当前入口为 hookRegistrar，要求 CLASS_TRANSFORM；目标可能随版本与混淆变化，UI 异常可以向上传播。
- 反射调用与 Hook 拦截不同，但均可能依赖内部 ABI。方法存在也不保证行为不变。Hook 自动注销不撤销已发生的副作用。

## 尚未验证，禁止作为产品承诺

- 任意未入库本地文件起播，以及它的公开媒体项查询行为。
- 完整本地队列、任意起始索引、宿主按钮 / 媒体键与播放模式。
- 同轨装载完成后 seek 的时序、重启恢复、外部切歌和停用回退。
- M3U8 / 命令行 / 系统打开方式的支持、顺序、起始轨及已运行实例投递。
- 独立 Compose 窗口与宿主生命周期；主导航嵌入的维护成本。
- Salt 在实际大型 RJ 库的扫描、内存和归组表现。
- 局部哈希只能增加候选匹配证据，不保证唯一、跨编辑稳定或机械硬盘亚毫秒读取。
- Kura 已是 TypeScript / Electron，并已有 mpv 后端；回归 mpv 不是无需集成维护的新捷径，也不应未经评估迁回原有全部功能。
