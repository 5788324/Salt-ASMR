package io.github.saltasmr

import io.github.saltasmr.catalog.ScannedWork
import io.github.saltasmr.catalog.Track
import io.github.saltasmr.store.LibraryStore
import io.github.saltasmr.ui.LibraryWindow
import java.awt.Component
import java.awt.Container
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

/** Opt-in real AWT smoke test: run with a display and -Dsaltasmr.uiTest=true. */
@EnabledIfSystemProperty(named = "saltasmr.uiTest", matches = "true")
class WindowSmokeTest {
    @TempDir lateinit var temp: Path

    @Test fun `favorite filter playlist removal and reopen work through the window`() {
        val data = temp.resolve("data")
        var firstId = ""
        LibraryStore.open(data).use { db ->
            db.importWorks(listOf(
                ScannedWork("RJ01234567", "[RJ01234567] 雨夜耳语", temp.resolve("media/a").toString(), listOf(Track("01 开场.mp3", 123))),
                ScannedWork("RJ02345678", "[RJ02345678] 森林音声", temp.resolve("media/b").toString(), listOf(Track("02 森林.wav", 456)))
            ))
            firstId = db.works().first().id
            val playlist = db.createPlaylist("睡前作品")
            db.works().forEach { db.addToPlaylist(playlist, it.id) }
        }
        var ui: LibraryWindow? = null
        try {
            edt { ui = LibraryWindow(data) {} }
            await { button("喜欢 / 取消喜欢").isEnabled }
            edt { button("喜欢 / 取消喜欢").doClick() }
            await { button("喜欢 / 取消喜欢").isEnabled && list().model.getElementAt(0).toString().startsWith("♥") }
            edt { combo().selectedIndex = 1 }
            edt { assertEquals(1, list().model.size) }
            edt { combo().selectedIndex = 2 }
            edt { assertEquals(2, list().model.size); list().selectedIndex = 1; button("上移").doClick() }
            await { button("喜欢 / 取消喜欢").isEnabled && list().model.getElementAt(0).toString().contains("RJ02345678") }
            edt { button("移出歌单").doClick() }
            await { button("喜欢 / 取消喜欢").isEnabled && list().model.size == 1 }
            edt {
                val frame = frame()
                val picture = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
                val graphics = picture.createGraphics()
                try { frame.paintAll(graphics) } finally { graphics.dispose() }
                val file = Path.of("build", "ui-smoke.png").toFile()
                file.parentFile.mkdirs(); ImageIO.write(picture, "png", file)
                ui!!.close()
            }
            awaitStore(data) { db ->
                assertTrue(db.works().single { it.id == firstId }.favorite)
                assertEquals(listOf(firstId), db.playlistWorks(db.playlists().single().id).map { it.id })
            }
            edt { ui = LibraryWindow(data) {} }
            await { button("喜欢 / 取消喜欢").isEnabled }
            edt { combo().selectedIndex = 1; assertEquals(1, list().model.size) }
        } finally { edt { ui?.close() } }
    }

    private fun frame(): JFrame = Window.getWindows().filterIsInstance<JFrame>().single { it.isDisplayable && it.title.startsWith("Salt-ASMR") }
    private fun components(component: Component): List<Component> = listOf(component) +
        if (component is Container) component.components.flatMap(::components) else emptyList()
    private fun button(text: String): JButton = components(frame()).filterIsInstance<JButton>().single { it.text == text }
    private fun list(): JList<*> = components(frame()).filterIsInstance<JList<*>>().single()
    private fun combo(): JComboBox<*> = components(frame()).filterIsInstance<JComboBox<*>>().single()
    private fun edt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            var ready = false
            edt { ready = condition() }
            if (ready) return
            Thread.sleep(25)
        }
        fail("窗口状态未在 10 秒内就绪")
    }
    private fun awaitStore(data: Path, check: (LibraryStore) -> Unit) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (true) {
            val db = try { LibraryStore.open(data) } catch (failure: Exception) {
                if (System.nanoTime() >= deadline) throw failure
                Thread.sleep(25); continue
            }
            db.use(check); return
        }
    }
}
