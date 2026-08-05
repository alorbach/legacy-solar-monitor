package com.alorbach.solarmonitor.data.importing

import android.content.Context
import android.net.Uri
import com.alorbach.solarmonitor.data.model.ImportSourceType
import com.alorbach.solarmonitor.data.repository.SolarRepository
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneId

class LegacySbfspotImporters(
    private val context: Context,
    private val repository: SolarRepository,
) {
    private val csvParser = SbfspotCsvParser(ZoneId.systemDefault())
    private val sqliteImporter = LegacySqliteImporter()
    private val urlClient = UrlImportClient(OkHttpClient())
    private val ftpClient = FtpImportClient()
    private val sftpClient = SftpImportClient()

    fun parseFile(deviceId: Long, name: String, bytes: ByteArray): ParsedImportBundle {
        return when {
            name.endsWith(".zip", ignoreCase = true) -> {
                ZipImportReader.flatten(bytes)
                    .map { parseFile(deviceId, it.name, it.bytes) }
                    .fold(ParsedImportBundle(preservedName = name, sourceType = ImportSourceType.ZIP)) { acc, item ->
                        ParsedImportBundle(
                            spotSamples = acc.spotSamples + item.spotSamples,
                            dayAggregates = acc.dayAggregates + item.dayAggregates,
                            monthAggregates = acc.monthAggregates + item.monthAggregates,
                            events = acc.events + item.events,
                            preservedName = name,
                            sourceType = ImportSourceType.ZIP,
                        )
                    }
            }

            name.endsWith(".db", ignoreCase = true) -> {
                val temp = File.createTempFile("legacy-", ".db", context.cacheDir)
                temp.writeBytes(bytes)
                sqliteImporter.parse(deviceId, temp).copy(preservedName = name)
            }

            else -> csvParser.parse(deviceId, name, bytes.inputStream())
        }
    }

    fun readUri(uri: Uri): Pair<String, ByteArray> {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "import.csv"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Unable to open $uri")
        return name to bytes
    }

    fun downloadUrl(url: String): Pair<String, ByteArray> =
        url.substringAfterLast('/') to urlClient.download(url)

    fun downloadFtp(host: String, username: String, password: String, path: String): Pair<String, ByteArray> =
        path.substringAfterLast('/') to ftpClient.download(host, username, password, path)

    fun downloadSftp(host: String, username: String, password: String, path: String): Pair<String, ByteArray> =
        path.substringAfterLast('/') to sftpClient.download(host, username, password, path)
}
