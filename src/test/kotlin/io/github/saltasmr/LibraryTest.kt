package io.github.saltasmr

import io.github.saltasmr.catalog.*
import io.github.saltasmr.store.LibraryStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class LibraryTest {
    @TempDir lateinit var temp: Path

    private fun audio(relative: String): Path {
        val file = temp.resolve("media").resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(0, 1, 2, 3, 4, 5))
        return file
    }

    @Test fun `scan keeps same RJ directories separate and sorts without touching media`() {
        val files = listOf(audio("[RJ01234567] 原版/Disc 1/10.wav"),
            audio("[RJ01234567] 原版/Disc 1/2.mp3"), audio("[RJ01234567] 翻译/01.wav"),
            audio("普通音乐/01.mp3"))
        Files.writeString(files[0].parent.resolve("10.vtt"), "WEBVTT\n")
        val before = files.associateWith { MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList() }
        val result = WorkScanner().scan(temp.resolve("media"))
        assertTrue(result.warnings.isEmpty())
        assertEquals(2, result.works.size)
        val original = result.works.single { it.title.endsWith("原版") }
        assertEquals(listOf("Disc 1/2.mp3", "Disc 1/10.wav"), original.tracks.map { it.relativePath })
        files.forEach { assertEquals(before[it], MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).toList()) }
    }

    @Test fun `nested RJ owns its files and symlinks are not followed`() {
        audio("RJ123456/01.mp3")
        audio("RJ123456/RJ234567/02.wav")
        val external = temp.resolve("outside/RJ345678")
        Files.createDirectories(external)
        Files.write(external.resolve("03.mp3"), byteArrayOf(1))
        if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
            Files.createSymbolicLink(temp.resolve("media/linked"), external)
        }
        val result = WorkScanner().scan(temp.resolve("media"))
        assertEquals(setOf("RJ123456", "RJ234567"), result.works.map { it.rj }.toSet())
        assertTrue(result.works.all { it.tracks.size == 1 })
    }

    @Test fun `cancelled scan yields no partial import and rejects invalid RJ substrings`() {
        audio("RJ123456/01.mp3")
        assertFailsWith<CancellationException> { WorkScanner().scan(temp.resolve("media")) { true } }
        assertNull(WorkScanner.rj("XRJ123456"))
        assertNull(WorkScanner.rj("RJ12345678901"))
        assertEquals("RJ01234567", WorkScanner.rj("[rj01234567] test"))
        assertTrue(WorkScanner.naturalCompare("99999999999999999999", "100000000000000000000") < 0)
    }

    @Test fun `favorites and ordered unique work playlists survive restart rescan and offline media`() {
        audio("RJ123456/01.mp3"); audio("RJ234567/01.wav"); audio("RJ345678/01.mp3")
        val scan = WorkScanner().scan(temp.resolve("media"))
        val directory = temp.resolve("data")
        var savedWorkId = ""; var playlistId = ""
        LibraryStore.open(directory).use { db ->
            db.importWorks(scan.works)
            val works = db.works()
            savedWorkId = works[0].id
            playlistId = db.createPlaylist("睡前")
            db.setFavorite(savedWorkId, true)
            works.forEach { db.addToPlaylist(playlistId, it.id) }
            db.addToPlaylist(playlistId, savedWorkId)
            assertEquals(3, db.playlistWorks(playlistId).size)
            db.moveInPlaylist(playlistId, works[2].id, -1)
            assertEquals(listOf(works[0].id, works[2].id, works[1].id), db.playlistWorks(playlistId).map { it.id })
            db.renamePlaylist(playlistId, "睡前作品")
            db.importWorks(scan.works)
            // Partial and empty rescans never remove previous inventories or user state.
            db.importWorks(listOf(scan.works.first().copy(tracks = emptyList())))
            db.importWorks(emptyList())
            assertEquals(1, db.works().single { it.id == savedWorkId }.tracks.size)
        }
        Files.delete(temp.resolve("media/RJ123456/01.mp3"))
        LibraryStore.open(directory).use { db ->
            assertTrue(db.works().single { it.id == savedWorkId }.favorite)
            assertEquals("睡前作品", db.playlists().single().name)
            assertEquals(3, db.playlistWorks(playlistId).size)
            db.removeFromPlaylist(playlistId, savedWorkId)
            assertTrue(db.works().single { it.id == savedWorkId }.favorite)
            db.deletePlaylist(playlistId)
            assertTrue(db.playlists().isEmpty())
            assertEquals(3, db.works().size)
            assertTrue(Files.exists(temp.resolve("media/RJ234567/01.wav")))
        }
    }

    @Test fun `failed import rolls back and schema prevents orphan entries`() {
        val file = audio("RJ123456/01.mp3")
        val work = WorkScanner().scan(file.parent).works.single()
        LibraryStore.open(temp.resolve("data")).use { db ->
            assertFails { db.importWorks(listOf(work, work.copy(folder = "different", tracks = listOf(Track("../escape.mp3", 1))))) }
            assertTrue(db.works().isEmpty())
            val playlist = db.createPlaylist("睡前")
            assertFails { db.addToPlaylist(playlist, "missing-work") }
            assertTrue(db.playlistWorks(playlist).isEmpty())
            assertFails { db.createPlaylist(" ") }
            assertFails { db.createPlaylist("睡前") }
            assertEquals(1, db.playlists().size)
        }
    }

    @Test fun `backup includes committed WAL data and refuses overwrite`() {
        audio("RJ123456/01.mp3")
        val backupDir = temp.resolve("backup")
        Files.createDirectories(backupDir)
        val target = backupDir.resolve("library.sqlite")
        LibraryStore.open(temp.resolve("data")).use { db ->
            db.importWorks(WorkScanner().scan(temp.resolve("media")).works)
            db.setFavorite(db.works().single().id, true)
            db.backup(target)
            assertFails { db.backup(target) }
        }
        LibraryStore.open(backupDir).use { assertTrue(it.works().single().favorite) }
    }

    @Test fun `exclusive lock prevents concurrent writers and releases on close`() {
        val directory = temp.resolve("data")
        LibraryStore.open(directory).use { assertFails { LibraryStore.open(directory) } }
        LibraryStore.open(directory).close()
    }

    @Test fun `corrupt database is not silently replaced`() {
        val directory = temp.resolve("data")
        Files.createDirectories(directory)
        val database = directory.resolve("library.sqlite")
        val content = "broken database keep me".toByteArray()
        Files.write(database, content)
        assertFails { LibraryStore.open(directory) }
        assertContentEquals(content, Files.readAllBytes(database))
    }

    @Test fun `newer schema is refused without overwriting user data`() {
        val directory = temp.resolve("data")
        LibraryStore.open(directory).use { it.createPlaylist("保留此歌单") }
        org.sqlite.JDBC().connect("jdbc:sqlite:${directory.resolve("library.sqlite")}", java.util.Properties()).use { db ->
            db.createStatement().use { it.execute("PRAGMA user_version=99") }
        }
        assertFails { LibraryStore.open(directory) }
        org.sqlite.JDBC().connect("jdbc:sqlite:${directory.resolve("library.sqlite")}", java.util.Properties()).use { db ->
            db.createStatement().use { s ->
                s.executeQuery("SELECT name FROM playlists").use { rows ->
                    assertTrue(rows.next()); assertEquals("保留此歌单", rows.getString(1))
                }
            }
        }
    }
}
