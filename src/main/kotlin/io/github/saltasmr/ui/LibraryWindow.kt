package io.github.saltasmr.ui

import io.github.saltasmr.catalog.*
import io.github.saltasmr.store.LibraryStore
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** Uses only JDK Swing: no host navigation, bytecode transformation or Compose ABI dependency. */
class LibraryWindow(private val dataDirectory: Path, private val onClosed: () -> Unit) {
    private data class View(val id: String?, val label: String, val favorites: Boolean = false) {
        override fun toString(): String = label
    }
    private val active = AtomicBoolean(true)
    private val cancelScan = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "salt-asmr-library").apply { isDaemon = true } }
    private var store: LibraryStore? = null // worker thread only
    private val frame = JFrame("Salt-ASMR · 作品收藏与歌单")
    private val scope = JComboBox<View>()
    private val search = JTextField(20)
    private val model = DefaultListModel<Work>()
    private val works = JList(model)
    private val details = JTextArea()
    private val status = JLabel("正在读取作品数据…")
    private val importButton = JButton("导入 RJ 目录")
    private val cancelButton = JButton("取消扫描")
    private val favoriteButton = JButton("喜欢 / 取消喜欢")
    private val addButton = JButton("加入歌单")
    private val removeButton = JButton("移出歌单")
    private val upButton = JButton("上移")
    private val downButton = JButton("下移")
    private val newButton = JButton("新建歌单")
    private val renameButton = JButton("重命名")
    private val deleteButton = JButton("删除歌单")
    private val folderButton = JButton("打开目录")
    private val backupButton = JButton("备份作品数据")
    private var allWorks = emptyList<Work>()
    private var playlists = emptyList<WorkPlaylist>()
    private var entries = emptyMap<String, List<Work>>()
    private var refreshing = false
    private var busy = true

    init {
        check(SwingUtilities.isEventDispatchThread())
        frame.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) { close(); onClosed() }
        })
        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(importButton); add(cancelButton); add(backupButton)
        }
        val filters = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("查看")); add(scope); add(JLabel("搜索")); add(search)
        }
        val heading = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel("  以整部 RJ 为单位收藏、整理作品歌单"))
            add(JLabel("  歌单保存在本插件；播放请使用 Salt 原生专辑页。本版不改 Tag、不控制播放。"))
            add(toolbar); add(filters)
        }
        works.selectionMode = ListSelectionModel.SINGLE_SELECTION
        works.visibleRowCount = 15
        details.isEditable = false
        details.lineWrap = true
        details.wrapStyleWord = true
        details.margin = java.awt.Insets(10, 10, 10, 10)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(works), JScrollPane(details)).apply {
            resizeWeight = 0.52
        }
        val actions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(favoriteButton); add(addButton); add(removeButton); add(upButton); add(downButton); add(folderButton)
            })
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(newButton); add(renameButton); add(deleteButton) })
            add(status)
        }
        frame.add(heading, BorderLayout.NORTH)
        frame.add(split, BorderLayout.CENTER)
        frame.add(actions, BorderLayout.SOUTH)
        frame.minimumSize = Dimension(850, 520)
        frame.setSize(1060, 680)
        frame.setLocationByPlatform(true)

        importButton.addActionListener { importDirectory() }
        cancelButton.addActionListener { cancelScan.set(true); cancelButton.isEnabled = false; status.text = "正在取消扫描…" }
        favoriteButton.addActionListener {
            selected()?.let { work -> mutate("收藏已更新", work.id) { it.setFavorite(work.id, !work.favorite) } }
        }
        newButton.addActionListener {
            askName("新建作品歌单", "")?.let { name -> mutate("歌单已创建") { it.createPlaylist(name) } }
        }
        renameButton.addActionListener {
            selectedPlaylist()?.let { view -> askName("重命名作品歌单", view.label)?.let { name ->
                mutate("歌单已重命名") { it.renamePlaylist(requireNotNull(view.id), name) }
            } }
        }
        deleteButton.addActionListener {
            selectedPlaylist()?.let { view ->
                if (JOptionPane.showConfirmDialog(frame, "删除歌单「${view.label}」？作品、喜欢和音频文件会保留。",
                        "删除作品歌单", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                    mutate("歌单已删除") { it.deletePlaylist(requireNotNull(view.id)) }
                }
            }
        }
        addButton.addActionListener {
            val work = selected() ?: return@addActionListener
            if (playlists.isEmpty()) { message("请先新建作品歌单"); return@addActionListener }
            val playlist = JOptionPane.showInputDialog(frame, "将整部作品加入哪个歌单？", "加入作品歌单",
                JOptionPane.QUESTION_MESSAGE, null, playlists.toTypedArray(), playlists.first()) as? WorkPlaylist
                ?: return@addActionListener
            mutate("作品已加入歌单（重复添加不会产生副本）", work.id) { it.addToPlaylist(playlist.id, work.id) }
        }
        removeButton.addActionListener {
            val id = selectedPlaylist()?.id ?: return@addActionListener
            selected()?.let { work -> mutate("已移出歌单；作品与喜欢保留") { it.removeFromPlaylist(id, work.id) } }
        }
        upButton.addActionListener { move(-1) }
        downButton.addActionListener { move(1) }
        folderButton.addActionListener {
            val work = selected() ?: return@addActionListener
            runTask("正在打开目录…", { _ ->
                val folder = Path.of(work.folder)
                require(Files.isDirectory(folder)) { "目录当前离线或已移动。作品收藏与歌单仍然保留：$folder" }
                require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "当前系统不支持打开目录" }
                Desktop.getDesktop().open(folder.toFile())
            }) { status.text = "已打开作品目录" }
        }
        backupButton.addActionListener {
            val chooser = JFileChooser().apply { selectedFile = java.io.File("salt-asmr-backup-${System.currentTimeMillis()}.sqlite") }
            if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
                val target = chooser.selectedFile.toPath()
                runTask("正在备份…", { it.backup(target) }) { status.text = "备份已保存：$target" }
            }
        }
        scope.addActionListener { if (!refreshing) renderWorks() }
        works.addListSelectionListener { if (!it.valueIsAdjusting) renderDetails() }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = renderWorks()
            override fun removeUpdate(e: DocumentEvent) = renderWorks()
            override fun changedUpdate(e: DocumentEvent) = renderWorks()
        })
        updateButtons()
        frame.isVisible = true
        worker.execute {
            try {
                if (!active.get()) return@execute
                store = LibraryStore.open(dataDirectory)
                val snapshot = snapshot(requireNotNull(store))
                SwingUtilities.invokeLater { if (active.get()) { busy = false; applySnapshot(snapshot); status.text = "数据位置：$dataDirectory" } }
            } catch (failure: Throwable) {
                if (failure !is Exception && failure !is LinkageError) throw failure
                SwingUtilities.invokeLater { if (active.get()) {
                    status.text = "无法打开数据，未覆盖已有文件"; showError(failure)
                    close(); onClosed()
                } }
            }
        }
    }

    fun show() { if (active.get()) { frame.isVisible = true; frame.toFront() } }

    fun close() {
        if (!active.compareAndSet(true, false)) return
        cancelScan.set(true)
        frame.dispose()
        // Queued tasks check active; close follows any transaction already in progress.
        worker.execute { store?.close(); store = null }
        worker.shutdown()
    }

    private data class Snapshot(val works: List<Work>, val playlists: List<WorkPlaylist>, val entries: Map<String, List<Work>>)
    private fun snapshot(db: LibraryStore): Snapshot {
        val lists = db.playlists()
        return Snapshot(db.works(), lists, lists.associate { it.id to db.playlistWorks(it.id) })
    }

    private fun applySnapshot(snapshot: Snapshot, selectId: String? = selected()?.id) {
        val oldView = scope.selectedItem as? View
        allWorks = snapshot.works; playlists = snapshot.playlists; entries = snapshot.entries
        refreshing = true
        scope.removeAllItems()
        scope.addItem(View(null, "全部作品")); scope.addItem(View(null, "喜欢的作品", true))
        playlists.forEach { scope.addItem(View(it.id, it.name)) }
        val next = (0 until scope.itemCount).firstOrNull {
            scope.getItemAt(it).id == oldView?.id && scope.getItemAt(it).favorites == (oldView?.favorites ?: false)
        } ?: 0
        scope.selectedIndex = next
        refreshing = false
        renderWorks(selectId)
    }

    private fun renderWorks(selectId: String? = selected()?.id) {
        val view = scope.selectedItem as? View
        val source = if (view?.id != null) entries[view.id].orEmpty()
            else allWorks.filter { view?.favorites != true || it.favorite }
        val query = search.text.trim()
        model.clear()
        source.filter { query.isEmpty() || "${it.rj} ${it.title} ${it.folder}".contains(query, ignoreCase = true) }
            .forEach(model::addElement)
        for (i in 0 until model.size()) if (model.getElementAt(i).id == selectId) works.selectedIndex = i
        if (works.selectedIndex < 0 && model.size() > 0) works.selectedIndex = 0
        renderDetails()
    }

    private fun renderDetails() {
        details.text = selected()?.let { work ->
            "${work.rj}\n${work.title}\n\n目录：${work.folder}\n\n" +
                "此目录作为一个作品条目；相同 RJ 的其他目录分别保留。\n" +
                "音轨仅供查看，尚未选择音效 / 编码分支，不代表播放队列。\n\n" +
                work.tracks.mapIndexed { i, track -> "${i + 1}. ${track.relativePath}" }.joinToString("\n")
        } ?: "选择目录导入 RJ 作品，再将整部作品加入喜欢或作品歌单。\n\n本版本只读取文件名与大小，不修改音频、标签、字幕或 Salt 曲库。"
        details.caretPosition = 0
        updateButtons()
    }

    private fun selected(): Work? = works.selectedValue
    private fun selectedPlaylist(): View? = (scope.selectedItem as? View)?.takeIf { it.id != null }
    private fun updateButtons() {
        val ready = active.get() && !busy
        listOf(importButton, backupButton, newButton, scope, search).forEach { it.isEnabled = ready }
        listOf(favoriteButton, addButton, folderButton).forEach { it.isEnabled = ready && selected() != null }
        listOf(renameButton, deleteButton).forEach { it.isEnabled = ready && selectedPlaylist() != null }
        removeButton.isEnabled = ready && selectedPlaylist() != null && selected() != null
        upButton.isEnabled = removeButton.isEnabled && search.text.isBlank() && works.selectedIndex > 0
        downButton.isEnabled = removeButton.isEnabled && search.text.isBlank() && works.selectedIndex < model.size() - 1
        if (!busy) cancelButton.isEnabled = false
    }

    private fun importDirectory() {
        val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
        val root = chooser.selectedFile.toPath()
        cancelScan.set(false)
        runTask("正在扫描；不会改动音频文件…", { db ->
            val result = WorkScanner().scan(root) { cancelScan.get() || !active.get() }
            if (cancelScan.get() || !active.get()) throw CancellationException("扫描已取消")
            // A partial scan must not replace a previously known directory inventory.
            require(result.warnings.isEmpty()) { result.warnings.joinToString("\n") + "\n本次未导入，请检查权限后重试。" }
            db.importWorks(result.works)
            result.works.size to snapshot(db)
        }) { (count, snapshot) ->
            applySnapshot(snapshot)
            status.text = if (count == 0) "未发现 RJ 目录下的音频；请检查目录命名（例如 RJ01234567）" else "已导入 / 更新 $count 个作品目录；收藏与歌单保留"
        }
        cancelButton.isEnabled = true
    }

    private fun move(offset: Int) {
        val playlist = selectedPlaylist()?.id ?: return
        val work = selected() ?: return
        mutate("作品顺序已保存", work.id) { it.moveInPlaylist(playlist, work.id, offset) }
    }

    private fun mutate(message: String, selectId: String? = selected()?.id, action: (LibraryStore) -> Unit) {
        runTask("正在保存…", { db -> action(db); snapshot(db) }) { applySnapshot(it, selectId); status.text = message }
    }

    private fun <T> runTask(message: String, action: (LibraryStore) -> T, success: (T) -> Unit) {
        if (busy || !active.get()) return
        busy = true; status.text = message; updateButtons()
        worker.execute {
            if (!active.get()) return@execute
            try {
                val result = action(requireNotNull(store))
                SwingUtilities.invokeLater { if (active.get()) { busy = false; success(result); updateButtons() } }
            } catch (_: CancellationException) {
                SwingUtilities.invokeLater { if (active.get()) { busy = false; status.text = "扫描已取消，已有收藏与歌单保留"; updateButtons() } }
            } catch (failure: Throwable) {
                if (failure !is Exception && failure !is LinkageError) throw failure
                SwingUtilities.invokeLater { if (active.get()) { busy = false; status.text = "操作未完成"; updateButtons(); showError(failure) } }
            }
        }
    }

    private fun askName(title: String, initial: String): String? = JOptionPane.showInputDialog(frame, title, initial)
    private fun message(text: String) = JOptionPane.showMessageDialog(frame, text)
    private fun showError(failure: Throwable) = JOptionPane.showMessageDialog(frame,
        failure.message ?: failure.javaClass.simpleName, "Salt-ASMR", JOptionPane.ERROR_MESSAGE)
}
