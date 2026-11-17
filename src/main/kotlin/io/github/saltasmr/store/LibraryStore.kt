package io.github.saltasmr.store

import io.github.saltasmr.catalog.*
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.util.Properties
import java.util.UUID

/** One connection, serialized access, cross-process lock. All mutations commit or roll back together. */
class LibraryStore private constructor(
    val directory: Path,
    private val connection: Connection,
    private val lockChannel: FileChannel,
    private val lock: FileLock
) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun works(): List<Work> {
        checkOpen()
        val tracks = mutableMapOf<String, MutableList<Track>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT work_id,relative_path,size_bytes FROM tracks ORDER BY position").use { rows ->
                while (rows.next()) tracks.getOrPut(rows.getString(1)) { mutableListOf() }
                    .add(Track(rows.getString(2), rows.getLong(3)))
            }
        }
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id,rj,title,folder,favorite FROM works ORDER BY rj,title,folder").use { rows ->
                buildList {
                    while (rows.next()) add(Work(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getInt(5) != 0, tracks[rows.getString(1)].orEmpty()))
                }
            }
        }
    }

    /** Additive rescan: missing/offline files never delete tracks, favorites, or playlist membership. */
    @Synchronized
    fun importWorks(scanned: List<ScannedWork>) = transaction {
        for (work in scanned) {
            val id = connection.prepareStatement("SELECT id FROM works WHERE folder=?").use { q ->
                q.setString(1, work.folder)
                q.executeQuery().use { if (it.next()) it.getString(1) else null }
            } ?: UUID.randomUUID().toString().also { id ->
                update("INSERT INTO works(id,rj,title,folder) VALUES(?,?,?,?)", id, work.rj, work.title, work.folder)
            }
            val combined = linkedMapOf<String, Track>()
            connection.prepareStatement("SELECT relative_path,size_bytes FROM tracks WHERE work_id=?").use { q ->
                q.setString(1, id)
                q.executeQuery().use { rows ->
                    while (rows.next()) combined[rows.getString(1)] = Track(rows.getString(1), rows.getLong(2))
                }
            }
            for (track in work.tracks) {
                require(track.sizeBytes >= 0 && !Path.of(track.relativePath).isAbsolute &&
                    track.relativePath.split('/').none { it == ".." }) { "非法音轨路径" }
                combined[track.relativePath] = track
            }
            combined.values.sortedWith { a, b -> WorkScanner.naturalCompare(a.relativePath, b.relativePath) }
                .forEachIndexed { index, track ->
                    update("INSERT INTO tracks(work_id,relative_path,size_bytes,position) VALUES(?,?,?,?) " +
                        "ON CONFLICT(work_id,relative_path) DO UPDATE SET size_bytes=excluded.size_bytes,position=excluded.position",
                        id, track.relativePath, track.sizeBytes, index)
                }
        }
    }

    @Synchronized
    fun setFavorite(workId: String, favorite: Boolean) = transaction {
        require(update("UPDATE works SET favorite=? WHERE id=?", if (favorite) 1 else 0, workId) == 1) { "作品不存在" }
    }

    @Synchronized
    fun playlists(): List<WorkPlaylist> {
        checkOpen()
        return connection.createStatement().use { statement ->
            statement.executeQuery("SELECT id,name FROM playlists ORDER BY rowid").use { rows ->
                buildList { while (rows.next()) add(WorkPlaylist(rows.getString(1), rows.getString(2))) }
            }
        }
    }

    @Synchronized
    fun createPlaylist(name: String): String = transaction {
        val id = UUID.randomUUID().toString()
        update("INSERT INTO playlists(id,name) VALUES(?,?)", id, validName(name))
        id
    }

    @Synchronized
    fun renamePlaylist(id: String, name: String) = transaction {
        require(update("UPDATE playlists SET name=? WHERE id=?", validName(name), id) == 1) { "歌单不存在" }
    }

    @Synchronized
    fun deletePlaylist(id: String) = transaction { update("DELETE FROM playlists WHERE id=?", id) }

    @Synchronized
    fun playlistWorks(id: String): List<Work> {
        checkOpen()
        val all = works().associateBy { it.id }
        return entryIds(id).mapNotNull(all::get)
    }

    @Synchronized
    fun addToPlaylist(playlistId: String, workId: String) = transaction {
        update("INSERT INTO playlist_entries(playlist_id,work_id,position) " +
            "SELECT ?,?,COALESCE(MAX(position),-1)+1 FROM playlist_entries WHERE playlist_id=? " +
            "ON CONFLICT(playlist_id,work_id) DO NOTHING", playlistId, workId, playlistId)
    }

    @Synchronized
    fun removeFromPlaylist(playlistId: String, workId: String) = transaction {
        update("DELETE FROM playlist_entries WHERE playlist_id=? AND work_id=?", playlistId, workId)
        orderEntries(playlistId, entryIds(playlistId))
    }

    @Synchronized
    fun moveInPlaylist(playlistId: String, workId: String, offset: Int) = transaction {
        require(offset == -1 || offset == 1)
        val ids = entryIds(playlistId).toMutableList()
        val index = ids.indexOf(workId)
        require(index >= 0) { "作品不在此歌单中" }
        val destination = index + offset
        if (destination in ids.indices) {
            ids.removeAt(index); ids.add(destination, workId)
            orderEntries(playlistId, ids)
        }
    }

    /** SQLite creates a consistent, standalone snapshot including WAL data; no raw DB file copy. */
    @Synchronized
    fun backup(destination: Path) {
        checkOpen()
        require(!Files.exists(destination)) { "备份文件已存在，请使用新文件名" }
        connection.prepareStatement("VACUUM INTO ?").use {
            it.setString(1, destination.toAbsolutePath().toString()); it.execute()
        }
    }

    private fun validName(name: String): String = name.trim().also {
        require(it.isNotEmpty() && it.length <= 80) { "歌单名称需为 1～80 个字符" }
    }

    private fun entryIds(id: String): List<String> =
        connection.prepareStatement("SELECT work_id FROM playlist_entries WHERE playlist_id=? ORDER BY position").use { q ->
            q.setString(1, id)
            q.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }

    private fun orderEntries(playlistId: String, ids: List<String>) {
        ids.forEachIndexed { index, id ->
            update("UPDATE playlist_entries SET position=? WHERE playlist_id=? AND work_id=?", index, playlistId, id)
        }
    }

    private fun update(sql: String, vararg args: Any): Int = connection.prepareStatement(sql).use { q ->
        args.forEachIndexed { index, value -> q.setObject(index + 1, value) }
        q.executeUpdate()
    }

    private fun <T> transaction(action: () -> T): T {
        checkOpen()
        connection.autoCommit = false
        try {
            val result = action()
            connection.commit()
            return result
        } catch (failure: Throwable) {
            runCatching { connection.rollback() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally { connection.autoCommit = true }
    }

    private fun checkOpen() = check(!closed) { "作品数据库已关闭" }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { connection.close() } finally { try { lock.release() } finally { lockChannel.close() } }
    }

    companion object {
        fun defaultDirectory(): Path {
            System.getProperty("saltasmr.dataDir")?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            return if (appData != null) Path.of(appData, "Salt-ASMR")
                else Path.of(System.getProperty("user.home"), ".local", "share", "Salt-ASMR")
        }

        fun open(directory: Path): LibraryStore {
            Files.createDirectories(directory)
            val channel = FileChannel.open(directory.resolve("library.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try {
                channel.tryLock() ?: error("作品数据正由另一个窗口或进程使用")
            } catch (failure: Throwable) { channel.close(); throw failure }
            var connection: Connection? = null
            try {
                // Instantiate explicitly: JDBC ServiceLoader discovery can differ under a plugin ClassLoader.
                connection = org.sqlite.JDBC().connect("jdbc:sqlite:${directory.resolve("library.sqlite").toAbsolutePath()}", Properties())
                val db = requireNotNull(connection)
                db.createStatement().use { s ->
                    s.execute("PRAGMA busy_timeout=5000")
                    s.execute("PRAGMA foreign_keys=ON")
                    val version = s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) }
                    require(version in 0..1) { "数据库版本 $version 高于当前插件支持版本；请勿覆盖数据" }
                    s.execute("PRAGMA journal_mode=WAL")
                    s.execute("PRAGMA synchronous=FULL")
                    if (version == 0) {
                        db.autoCommit = false
                        try {
                            s.execute("CREATE TABLE works(id TEXT PRIMARY KEY,rj TEXT NOT NULL,title TEXT NOT NULL,folder TEXT NOT NULL UNIQUE,favorite INTEGER NOT NULL DEFAULT 0 CHECK(favorite IN (0,1)))")
                            s.execute("CREATE TABLE tracks(work_id TEXT NOT NULL REFERENCES works(id),relative_path TEXT NOT NULL,size_bytes INTEGER NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(work_id,relative_path))")
                            s.execute("CREATE TABLE playlists(id TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE)")
                            s.execute("CREATE TABLE playlist_entries(playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,work_id TEXT NOT NULL REFERENCES works(id),position INTEGER NOT NULL,PRIMARY KEY(playlist_id,work_id))")
                            s.execute("PRAGMA user_version=1")
                            db.commit()
                        } catch (failure: Throwable) { db.rollback(); throw failure }
                        finally { db.autoCommit = true }
                    }
                }
                return LibraryStore(directory, db, channel, lock)
            } catch (failure: Throwable) {
                runCatching { connection?.close() }; lock.release(); channel.close(); throw failure
            }
        }
    }
}
