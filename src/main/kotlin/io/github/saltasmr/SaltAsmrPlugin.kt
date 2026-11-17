package io.github.saltasmr

import com.xuncorp.spw.workshop.api.PluginContext
import com.xuncorp.spw.workshop.api.SpwPlugin
import io.github.saltasmr.store.LibraryStore
import io.github.saltasmr.ui.LibraryWindow
import javax.swing.SwingUtilities

class SaltAsmrPlugin(context: PluginContext) : SpwPlugin(context) {
    override fun start() {
        SwingUtilities.invokeLater { enabled = true }
    }

    override fun stop() {
        SwingUtilities.invokeLater {
            enabled = false
            window?.close()
            window = null
        }
    }

    companion object {
        // Accessed only on Swing EDT, including settings callbacks arriving on host UI thread.
        private var enabled = false
        private var window: LibraryWindow? = null

        @JvmStatic
        fun openLibrary() {
            SwingUtilities.invokeLater {
                if (!enabled) return@invokeLater
                val current = window
                if (current != null) current.show()
                else window = LibraryWindow(LibraryStore.defaultDirectory()) { window = null }
            }
        }
    }
}
