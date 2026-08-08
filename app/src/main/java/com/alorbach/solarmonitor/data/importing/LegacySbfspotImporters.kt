package com.alorbach.solarmonitor.data.importing

import android.content.Context
import android.net.Uri
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.repository.SolarRepository
import java.io.File
import java.time.ZoneId

class LegacySbfspotImporters(
    private val context: Context,
    private val repository: SolarRepository,
) {
    private val urlClient = UrlImportClient()
    private val ftpClient = FtpImportClient()
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
        val sqliteImporter = LegacySqliteImporter(zoneId)

        return when {
            name.endsWith(".zip", ignoreCase = true) -> {
                var combined = ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.ZIP)
                for (entry in ZipImportReader.flatten(bytes)) {
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
        } ?: error("Unable to open $uri")
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
        RemoteBrowseHelpers.collectCsvFiles(path) { dir ->
            listFtp(host, port, username, password, dir)
        }

    fun listCsvRecursiveSftp(
        host: String,
        port: Int = SftpImportClient.DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> =
        RemoteBrowseHelpers.collectCsvFiles(path) { dir ->
            listSftp(host, port, username, password, dir)
        }
}
