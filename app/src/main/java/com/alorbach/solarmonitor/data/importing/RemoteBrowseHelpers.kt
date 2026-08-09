package com.alorbach.solarmonitor.data.importing

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long? = null,
)

object RemoteBrowseHelpers {
    /** Soft cap for huge SBFspot trees (~years of daily CSVs). */
    const val MAX_FOLDER_IMPORT_FILES = 25_000
    const val MAX_IMPORT_FILE_BYTES = 50L * 1024L * 1024L
    /** Aggregate download cap for one folder import (independent of per-file heap bound). */
    const val MAX_FOLDER_IMPORT_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L

    fun readBytesCapped(
        input: java.io.InputStream,
        maxBytes: Long = MAX_IMPORT_FILE_BYTES,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) {
                "Import file exceeds ${maxBytes / (1024 * 1024)} MiB limit"
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    fun isImportableFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".csv") || lower.endsWith(".zip") || lower.endsWith(".db")
    }

    fun isCsvFile(name: String): Boolean = name.lowercase().endsWith(".csv")

    /** Join parent directory and child name into a normalized absolute-style path. */
    fun joinPath(parent: String, child: String): String {
        val cleanChild = child.trim('/').trim('\\')
        if (cleanChild.isEmpty() || cleanChild == ".") return normalizeDirectory(parent)
        if (cleanChild == "..") return parentPath(parent) ?: "/"
        val base = normalizeDirectory(parent).trimEnd('/')
        return if (base.isEmpty() || base == "/") "/$cleanChild" else "$base/$cleanChild"
    }

    fun parentPath(path: String): String? {
        val normalized = path.replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty() || normalized == "/") return null
        val slash = normalized.lastIndexOf('/')
        if (slash <= 0) return "/"
        return normalized.substring(0, slash).ifEmpty { "/" }
    }

    fun normalizeDirectory(path: String): String {
        val trimmed = path.replace('\\', '/').trim()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        return "/" + trimmed.trim('/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
    }

    fun fileName(path: String): String =
        path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path }

    /** True when [path] looks like a directory rather than an importable file. */
    fun looksLikeDirectory(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || trimmed.endsWith('/')) return true
        return !isImportableFile(fileName(trimmed))
    }

    /** Keep directories and importable files; directories first, then name (case-insensitive). */
    fun prepareBrowseEntries(entries: List<RemoteEntry>): List<RemoteEntry> =
        entries
            .filter { it.isDirectory || isImportableFile(it.name) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))

    /**
     * Recursively collect CSV files under [root] using [listDirectory].
     * Depth is capped to avoid runaway walks on large trees.
     */
    fun collectCsvFiles(
        root: String,
        maxDepth: Int = 8,
        maxFiles: Int = MAX_FOLDER_IMPORT_FILES,
        listDirectory: (String) -> List<RemoteEntry>,
    ): List<RemoteEntry> {
        val result = mutableListOf<RemoteEntry>()
        fun walk(dir: String, depth: Int) {
            require(depth <= maxDepth) {
                "Folder import exceeds maximum directory depth of $maxDepth under $root"
            }
            for (entry in listDirectory(dir)) {
                when {
                    entry.isDirectory -> walk(entry.path, depth + 1)
                    isCsvFile(entry.name) -> {
                        require(result.size < maxFiles) {
                            "Folder import exceeds $maxFiles CSV files"
                        }
                        result += entry
                    }
                }
            }
        }
        walk(normalizeDirectory(root), 0)
        return result.sortedBy { it.path.lowercase() }
    }
}
