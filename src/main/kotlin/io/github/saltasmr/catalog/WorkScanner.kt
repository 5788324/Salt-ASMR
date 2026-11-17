package io.github.saltasmr.catalog

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CancellationException

/** Only enumerates filenames and sizes. Does not decode or write media; does not follow links. */
class WorkScanner {
    fun scan(root: Path, cancelled: () -> Boolean = { false }): ScanResult {
        require(Files.isDirectory(root)) { "目录不存在或不可读：$root" }
        val absolute = root.toRealPath()
        val tracks = linkedMapOf<Path, MutableList<Track>>()
        val warnings = mutableListOf<String>()
        var audioCount = 0
        fun checkCancelled() {
            if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("扫描已取消")
        }
        Files.walkFileTree(absolute, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                checkCancelled()
                if (!attrs.isRegularFile || file.fileName.toString().substringAfterLast('.', "").lowercase() !in AUDIO) {
                    return FileVisitResult.CONTINUE
                }
                require(++audioCount <= 20_000) { "本版本单次最多扫描 20,000 个音频，请选择较小目录" }
                val workFolder = generateSequence(file.parent) { it.parent }
                    .firstOrNull { rj(it.fileName?.toString().orEmpty()) != null }
                if (workFolder != null) {
                    tracks.getOrPut(workFolder) { mutableListOf() }
                        .add(Track(workFolder.relativize(file).toString().replace('\\', '/'), attrs.size()))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult {
                checkCancelled()
                if (warnings.size < 50) warnings += "无法读取：$file（${exc.message}）"
                return FileVisitResult.CONTINUE
            }
        })
        checkCancelled()
        val works = tracks.map { (folder, entries) ->
            val name = folder.fileName.toString()
            ScannedWork(requireNotNull(rj(name)), name, folder.toString(),
                entries.sortedWith { a, b -> naturalCompare(a.relativePath, b.relativePath) })
        }.sortedWith { a, b -> naturalCompare(a.folder, b.folder) }
        return ScanResult(works, warnings)
    }

    companion object {
        private val AUDIO = setOf("mp3", "wav", "flac", "m4a", "ogg", "opus")
        private val CODE = Regex("(?i)(?<![a-z0-9])RJ[0-9]{6,10}(?![0-9])")
        fun rj(name: String): String? = CODE.find(name)?.value?.uppercase()

        /** Numeric runs are compared without integer conversion, including arbitrarily long runs. */
        fun naturalCompare(a: String, b: String): Int {
            val left = Regex("[0-9]+|[^0-9]+").findAll(a).map { it.value }.toList()
            val right = Regex("[0-9]+|[^0-9]+").findAll(b).map { it.value }.toList()
            for (i in 0 until minOf(left.size, right.size)) {
                val x = left[i]; val y = right[i]
                val order = if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }
                    val ny = y.trimStart('0').ifEmpty { "0" }
                    nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
                } else x.compareTo(y, ignoreCase = true)
                if (order != 0) return order
            }
            return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}
