package io.github.saltasmr

import io.github.saltasmr.store.LibraryStore
import io.github.saltasmr.ui.LibraryWindow
import javax.swing.SwingUtilities
import javax.swing.UIManager

/** Standalone preview for filesystem, storage and UI verification; does not emulate Salt. */
fun main() {
    SwingUtilities.invokeLater {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        LibraryWindow(LibraryStore.defaultDirectory()) {}
    }
}
