package com.alorbach.solarmonitor.data.importing

import android.content.Context
import android.net.Uri
import com.alorbach.solarmonitor.R
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.repository.SolarRepository
import java.io.File
import java.time.ZoneId

class LegacySbfspotImporters(
    private val context: Context,
    private val repository: SolarRepository,
) {
    private val urlClient = UrlImportClient(context)
    private val ftpClient = FtpImportClient(context)
    private val sftpClient = SftpImportClient(File(context.filesDir, "known_hosts"))

    suspend fun parseFile(deviceId: Long, name: String, bytes: ByteArray): ParsedImportBundle {
        val device = repository.getDevice(deviceId)
        val zoneId = runCatching { ZoneId.of(device?.timezone ?: ZoneId.systemDefault().id) }
            .getOrDefault(ZoneId.systemDefault())
        val csvParser = SbfspotCsvParser(
            CsvParseOptions(
                zoneId = zoneId,
                decimalPoint = device?.decimalPoint ?: "comma",
                delimiter = device?.delimiter ?: "semicolon",
                dateFormat = device?.dateFormat,
            )
        )
        val sqliteImporter = LegacySqliteImporter(zoneId) { table, count ->
            context.getString(
                R.string.import_sqlite_too_many_rows,
                table,
                count.toString(),
                LegacySqliteImporter.MAX_ROWS,
            )
        }

        return when {
            name.endsWith(".zip", ignoreCase = true) -> {
                var combined = ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.ZIP)
                for (entry in ZipImportReader.flatten(bytes)) {
                    if (!ZipImportReader.shouldParseFlattenedEntry(entry.name)) continue
                    combined += parseFile(deviceId, entry.name, entry.bytes)
                }
                combined.copy(preservedName = name, sourceType = ImportSourceType.ZIP)
            }

            name.endsWith(".db", ignoreCase = true) -> {
                val temp = File.createTempFile("legacy-", ".db", context.cacheDir)
                try {
                    temp.writeBytes(bytes)
                    sqliteImporter.parse(deviceId, temp).copy(preservedName = name)
                } finally {
                    temp.delete()
                }
            }

            else -> csvParser.parse(deviceId, name, bytes.inputStream())
        }
    }

    fun readUri(uri: Uri): Pair<String, ByteArray> {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "import.csv"
        val bytes = context.contentResolver.openInputStream(uri)?.use {
            RemoteBrowseHelpers.readBytesCapped(it)
        } ?: error(context.getString(R.string.import_unable_open_uri, uri.toString()))
        return name to bytes
    }

    fun downloadUrl(url: String): Pair<String, ByteArray> =
        url.substringAfterLast('/') to urlClient.download(url)

    fun downloadFtp(
        host: String,
        port: Int = FtpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): Pair<String, ByteArray> =
        RemoteBrowseHelpers.fileName(path) to ftpClient.download(host, port, username, password, path)

    fun downloadSftp(
        host: String,
        port: Int = SftpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): Pair<String, ByteArray> =
        RemoteBrowseHelpers.fileName(path) to sftpClient.download(host, port, username, password, path)

    fun listFtp(
        host: String,
        port: Int = FtpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> =
        RemoteBrowseHelpers.prepareBrowseEntries(
            ftpClient.list(host, port, username, password, path),
        )

    fun listSftp(
        host: String,
        port: Int = SftpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> =
        RemoteBrowseHelpers.prepareBrowseEntries(
            sftpClient.list(host, port, username, password, path),
        )

    fun homeDirectoryFtp(
        host: String,
        port: Int = FtpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
    ): String = ftpClient.workingDirectory(host, port, username, password)

    fun homeDirectorySftp(
        host: String,
        port: Int = SftpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
    ): String = sftpClient.workingDirectory(host, port, username, password)

    fun listCsvRecursiveFtp(
        host: String,
        port: Int = FtpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> =
        ftpClient.withSession(host, port, username, password) { session ->
            RemoteBrowseHelpers.collectCsvFiles(path) { dir ->
                RemoteBrowseHelpers.prepareBrowseEntries(session.list(dir))
            }
        }

    fun listCsvRecursiveSftp(
        host: String,
        port: Int = SftpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> =
        sftpClient.withSession(host, port, username, password) { session ->
            RemoteBrowseHelpers.collectCsvFiles(path) { dir ->
                RemoteBrowseHelpers.prepareBrowseEntries(session.list(dir))
            }
        }

    fun forEachCsvInFtpFolder(
        host: String,
        port: Int = FtpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onFile: (name: String, bytes: ByteArray) -> Unit,
    ): Int = ftpClient.withSession(host, port, username, password) { session ->
        forEachCsvInFolder(
            path = path,
            listDir = { dir -> RemoteBrowseHelpers.prepareBrowseEntries(session.list(dir)) },
            download = { remotePath -> session.download(remotePath) },
            keepAlive = { session.noop() },
            onProgress = onProgress,
            onFile = onFile,
        )
    }

    fun forEachCsvInSftpFolder(
        host: String,
        port: Int = SftpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onFile: (name: String, bytes: ByteArray) -> Unit,
    ): Int = sftpClient.withSession(host, port, username, password) { session ->
        forEachCsvInFolder(
            path = path,
            listDir = { dir -> RemoteBrowseHelpers.prepareBrowseEntries(session.list(dir)) },
            download = { remotePath -> session.download(remotePath) },
            keepAlive = { session.keepAlive() },
            onProgress = onProgress,
            onFile = onFile,
        )
    }

    private fun forEachCsvInFolder(
        path: String,
        listDir: (String) -> List<RemoteEntry>,
        download: (String) -> ByteArray,
        keepAlive: () -> Unit,
        onProgress: ((current: Int, total: Int) -> Unit)?,
        onFile: (name: String, bytes: ByteArray) -> Unit,
    ): Int {
        val files = RemoteBrowseHelpers.collectCsvFiles(root = path, listDirectory = listDir)
        require(files.isNotEmpty()) { context.getString(R.string.import_no_csv_under, path) }
        require(files.size <= RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES) {
            context.getString(
                R.string.import_folder_too_many,
                RemoteBrowseHelpers.MAX_FOLDER_IMPORT_FILES,
                files.size,
            )
        }
        var totalBytes = 0L
        files.forEachIndexed { index, entry ->
            val bytes = download(entry.path)
            require(bytes.size <= RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES) {
                context.getString(
                    R.string.import_file_too_large,
                    entry.name,
                    (RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES / (1024 * 1024)).toInt(),
                )
            }
            totalBytes += bytes.size
            require(totalBytes <= RemoteBrowseHelpers.MAX_FOLDER_IMPORT_TOTAL_BYTES) {
                context.getString(
                    R.string.import_folder_too_large_mib,
                    (RemoteBrowseHelpers.MAX_FOLDER_IMPORT_TOTAL_BYTES / (1024 * 1024)).toInt(),
                )
            }
            onFile(entry.name, bytes)
            // Local parse/persist can idle the socket; ping before the next download.
            if (index + 1 < files.size) {
                runCatching { keepAlive() }
            }
            onProgress?.invoke(index + 1, files.size)
        }
        return files.size
    }
}
