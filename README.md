# Salt-ASMR

基于 Salt Player for Windows Workshop 的本地 RJ 作品收藏与作品歌单插件。

**当前版本：0.1.0-alpha.1。已完成独立窗口、数据与打包验证；尚未在 Windows Salt 宿主内完成安装验收。**

## 这一版能做什么

- 导入 RJ 目录，或包含多个 RJ 目录的父目录；识别目录名中的 RJ 编号。
- 以作品目录为单位查看音轨、搜索、喜欢 / 取消喜欢。
- 创建、重命名、删除作品歌单；添加整部作品、移出、上移和下移。
- SQLite 保存收藏、歌单与顺序；重新扫描、退出后重启、媒体目录离线都保留记录。
- 导出一致的 SQLite 备份，打开作品所在目录。

**收藏和歌单属于本插件，不会同步为 Salt 原生“喜欢”或原生歌单。**

本版仅读取音频文件名与大小，不写 Tag，不改名、不移动、不删除音频；不写 Salt 曲库，不使用 Hook，也不控制播放。播放仍需用户在 Salt 原生界面选择作品。VTT / LRC 适配、Tag 整理和作品歌单一键连续播放尚未实现。

同一 RJ 的不同目录分别保留，避免把原版、翻译版或不同音效版本错误合并。音轨列表仅用于查看，不代表已选定播放分支或播放队列。改名 / 移动后的自动重新关联尚未实现。

## 安装与使用

1. 从本仓库 [Build 工作流](https://github.com/5788324/Salt-ASMR/actions/workflows/build.yml) 的成功运行下载 `salt-asmr-plugin` artifact，解压获得 `.spmod`；也可自行构建。
2. 按 [Salt 官方安装说明](https://saltplayer.com/workshop/usage) 本地导入并启用。
3. 在插件配置中点击 **打开作品收藏与歌单**。
4. 点击 **导入 RJ 目录**，先用 3 部作品验证；目录名示例：`[RJ01234567] 作品名称`。
5. 选择作品点“喜欢 / 取消喜欢”，或创建歌单后点“加入歌单”。顶部“查看”切换全部、喜欢和各个歌单。

本版以 API `0.1.0-dev23` 构建，需要提供对应 API、JDK 25 的 Salt 宿主；未承诺所有 1.19.x 版本兼容。首次使用请按 [Windows 验收清单](docs/USAGE.md#windows-salt-验收) 测试。

## 构建与独立预览

需要 JDK 25，使用仓库自带的 Gradle 9.8.0 Wrapper。

```powershell
.\gradlew.bat test plugin
.\gradlew.bat runDesktop
```

Linux：

```sh
./gradlew test plugin
./gradlew runDesktop
```

产物：`build/libs/plugin-io.github.saltasmr-0.1.0-alpha.1.spmod`。独立预览默认使用 `build/desktop-data`，不会读取正式插件的数据；可通过 `-PdataDir=路径` 指定预览数据目录。

在有图形显示的环境执行窗口交互测试：

```sh
./gradlew test -Dsaltasmr.uiTest=true
```

自动测试覆盖收藏 / 歌单持久化、顺序与去重、重扫和离线保留、事务回滚、损坏 / 高版本数据库保护、备份、独占锁、只读扫描、窗口交互与重新打开。它们不能代替 Windows Salt 宿主测试。

## 数据与范围

Windows 正式数据位置：`%APPDATA%\Salt-ASMR\library.sqlite`，独立于插件安装目录。卸载插件不主动删除数据。升级或批量整理前请先备份；恢复方法见 [使用说明](docs/USAGE.md)。

单次扫描上限 20,000 个音频文件，面向小规模起步，不面向一次性导入整个 10TB 资源库。使用自有作品 ID，Salt 歌曲 ID 不作为数据主键。

## 文档

- [使用、备份与验收](docs/USAGE.md)
- [当前路线图](docs/ROADMAP.md)
- [此前播放 API 技术评审与源码证据](docs/TECHNICAL_REVIEW.md)
- [第三方组件说明](THIRD_PARTY_NOTICES.md)

此前“完整队列与续播先验证”的结论仍适用于未来播放控制功能，不阻塞本版独立收藏与歌单管理。

本项目与 Salt Player 官方团队无关联。
