# Salt-ASMR

> 基于 Salt Player for Windows（SPW）Workshop 插件系统的本地 RJ / ASMR 音声专辑管理扩展。

**当前状态：规划 / P0 技术验证准备中。尚未提供可安装的 .spmod 文件。**

## 项目目标

- 将一个 RJ 作品视为一个**专辑**，而不是散落的独立音频。
- 利用 Salt Player 既有的解码、音频输出、基础播放和歌词能力；本项目只负责 **RJ 目录识别、专辑与章节、进度、字幕扩展和专用交互**。
- 本地优先、中文界面、尽量不改动原始音频或 Salt 原有媒体库。
- 不依赖云端服务，也不捆绑 AI 模型；日后可通过本地文件对接 ASR/翻译结果。

## 预期功能

1. 按 RJ 编号和目录结构识别作品，整理封面、音轨和作品元数据。
2. 按作品连续播放与切轨，并在重启后恢复到「作品 + 音轨 + 毫秒位置」。
3. 支持日语 / 中文 / 双语 LRC、SPL 等字幕文件。
4. 记录收听历史、收藏和时间书签。
5. 增量扫描大规模音频库，避免一次读取全部音频内容。

## 必须先解决的技术问题

SPW Workshop API 已公开播放控制（`play/pause/next/previous/seekTo`）、播放位置回调、媒体库查询和歌词扩展点，但**目前公开契约中未找到直接指定任意音轨播放、设置播放队列的稳定接口**。

因此 **P0 的验收关键是：从插件中打开一个指定 RJ 音轨，并按该 RJ 指定顺序自动切换到下一轨**。在这个问题得到实测解决前，不承诺完整的“专辑播放器”功能已经实现；必要 Hook 方案必须可禁用且版本隔离。

## 技术方案（拟）

- **语言/插件**：Kotlin、Gradle、PF4J、SPW Workshop API；生成 `.spmod`。
- **数据**：本地 SQLite 索引；逻辑作品 ID、独立音轨 ID 与 Salt 曲库 ID 分开管理。
- **对接**：`SaltPlaybackAdapter` 只调用官方 API；实验性 Hook 隔离在单独模块/适配器。
- **界面**：优先使用 Workshop 支持的宿主 UI 扩展；不做第二套播放器内核。
- **测试**：小样本 RJ → 多音轨作品 → 大型资料库，逐步验证。

## 范围与约束

- 第一阶段仅针对 **Salt Player for Windows**；Android 不在 P0 范围。
- 默认只读取用户自己指定的本地目录，不移动、不改名、不修改音频标签；允许独立数据库维护人工补充信息。
- 不引入在线抓取或云同步作为启动依赖。
- 不复制 Salt Player 的闭源实现；扩展层独立维护，遵循所用 API、依赖和资源的许可证。
- 与 Salt Player 官方团队无关联。

## 开发里程碑

详见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 资料

- [Salt Player Workshop 开发指南](https://saltplayer.com/workshop/getting-started)
- [官方 SPW Workshop API 与示例](https://github.com/Moriafly/spw-workshop-api)
- [项目仓库](https://github.com/5788324/Salt-ASMR)
