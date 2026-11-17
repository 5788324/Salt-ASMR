# Salt-ASMR 开发路线与验收

> 状态：2026-11-17 项目启动规划。以下为目标，不代表功能已实现。

## P0：可行性验证（最高优先级）

使用官方 [SPW Workshop API](https://github.com/Moriafly/spw-workshop-api) 的 example 工程作为参考，制作最小插件并打包 `.spmod`。

| 验收项 | 方法 | 通过标准 |
|---|---|---|
| 装载 / 卸载 | 本地安装 .spmod；启用、停用 | 不使 Salt 崩溃或影响普通播放 |
| 曲库读取 | 申请 LIBRARY_READ；分页调用 getTracks | 能获取 ID、路径、标题、时长 |
| RJ 归组 | 扫描用户指定目录内一个 RJ | 音轨自然排序，能展示封面/目录 |
| 指定音轨播放（关键阻塞） | 先找公开 API，必要时验证可选 Hook | 插件点击任意音轨，Salt 播放对应文件 |
| 连续播放（关键阻塞） | 有序队列／切轨机制 | 从第 1 轨自动进入第 2、3 轨，顺序正确 |
| 进度采集与恢复 | onPositionUpdated + seekTo | 重启后恢复到同一作品、同一轨、相近位置 |
| 字幕加载 | onAfterLoadLyrics / onBeforeLoadLyrics | 加载日/中/双语 LRC，原生歌词仍正常 |
| 故障回退 | 插件停用 / Hook 不兼容 | Salt 正常播放，用户数据不丢失 |

**退出条件：** 若“指定音轨播放”或“专辑队列”需要不可维护的深度 Hook，则不要继续扩展完整 UI；先重新评估产品范围。

## P1：可日用的单机版本

- RJ 编号/作品识别、自然顺序与嵌套目录支持。
- 作品详情页和逐轨播放、完整作品续播。
- 日语、中文和双语字幕切换；兼容 LRC、SPL。
- 基础进度库与作品封面。
- 中文界面；默认与 Salt 原 UI 共存。

## P2：音声体验

- 作品级历史/继续收听、已听完/未听完状态。
- 时间书签与快速定位。
- 文件和字幕的匹配报告（缺失/候选/已匹配）。
- 搜索、社团、CV、标签、收藏、自定义排序。
- 可选导入本地 ASR 翻译结果（只读取文件）。

## P3：大库优化

- SQLite 增量索引、扫描取消/恢复、文件变更与重复识别。
- 封面缩略图缓存，列表虚拟化和分页查询。
- 路径变更恢复（作品 RJ + 相对音轨路径 + 长度/大小等；不单独依赖 Salt song ID）。
- 大规模基准测试：扫描速度、内存、界面交互、恢复正确性。

## 关键架构约束

1. **不要实现第二个音频引擎。** 解码、输出及常规播放全由 Salt 承担。
2. **宿主 API 与业务代码分离。** `SaltPlaybackAdapter` 封装稳定 API，`HookPlaybackAdapter` 仅处理经过版本检测的实验能力。
3. **不要按时间回调每秒写库。** 进度内存合并，周期性或暂停/切轨/退出时写入；异常退出允许有限误差。
4. **不把 Salt 的歌曲 ID 当长期稳定主键。** 自有专辑与音轨身份独立维护。
5. **只采集元数据。** 扫描不解码大音频、不计算整库 SHA-256，使用分页/批处理。
6. **可降级运行。** 无 Hook 时仍能浏览作品、显示字幕与记录历史；不能假装完整队列已受控。
7. **数据可迁移。** 元数据、进度支持 JSON 导入导出，便于将来换宿主。
8. **先 3 部作品小样本再试大库。** 覆盖多层目录、不同命名、FLAC/MP3/WAV、缺字幕、双语字幕。

## 提议模块边界

```
plugin/
  SaltAsmrPlugin          # 生命周期、权限与配置
  host/
    SaltPlaybackAdapter   # 官方 Workshop API
    HookPlaybackAdapter   # 实验 Hook（必须可关闭）
  catalog/
    AlbumScanner          # RJ 识别与目录遍历
    TrackOrder            # 自然排序、音轨归组
    CatalogRepository     # SQLite，元数据/索引
  listening/
    ResumeRepository      # 作品/音轨/位置
    PlaybackCoordinator   # 专辑播放状态机
  lyrics/
    LyricsLocator         # LRC/SPL 匹配
  ui/
    AlbumLibrary
    AlbumDetail
```

此目录仅为架构草案；P0 前不需要全部创建。

## 上游依赖与兼容策略

- 宿主：Salt Player for Windows 1.19.x 或经实测确认的兼容版本。
- SDK：以实际构建时可用版本为准，锁定构建依赖，记录测试过的宿主版本。
- 上游 Workshop API 标记为实验性：所有 Host 适配代码需单独测试，严禁静默吞掉失败后宣称已播放。
- 对闭源宿主不做二进制替换、解包修改或将原始文件打包到插件。
