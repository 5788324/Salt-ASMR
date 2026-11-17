package io.github.saltasmr.catalog

data class Track(val relativePath: String, val sizeBytes: Long)

data class ScannedWork(
    val rj: String,
    val title: String,
    val folder: String,
    val tracks: List<Track>
)

data class Work(
    val id: String,
    val rj: String,
    val title: String,
    val folder: String,
    val favorite: Boolean,
    val tracks: List<Track>
) {
    override fun toString(): String = "${if (favorite) "♥ " else ""}$rj · $title (${tracks.size} 轨)"
}

data class WorkPlaylist(val id: String, val name: String) {
    override fun toString(): String = name
}

data class ScanResult(val works: List<ScannedWork>, val warnings: List<String>)
