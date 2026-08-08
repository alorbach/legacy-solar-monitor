package com.alorbach.solarmonitor.data.importing

import com.alorbach.solarmonitor.data.model.ImportJobEntity
import com.alorbach.solarmonitor.data.model.ImportSourceType
import org.json.JSONObject

/**
 * Serializable (password-free) snapshot so an import job can be re-run later.
 * Passwords and sensitive import URLs stay in
 * [com.alorbach.solarmonitor.data.security.CredentialStore].
 */
data class ImportReplayConfig(
    val kind: String,
    val deviceId: Long,
    val host: String? = null,
    val port: Int? = null,
    val username: String? = null,
    val path: String? = null,
    val directory: Boolean = false,
    val url: String? = null,
    val sourceLabel: String,
) {
    fun toJson(): String =
        JSONObject()
            .put("kind", kind)
            .put("deviceId", deviceId)
            .put("host", host)
            .put("port", port)
            .put("username", username)
            .put("path", path)
            .put("directory", directory)
            .put("url", url)
            .put("sourceLabel", sourceLabel)
            .toString()

    fun toRequest(
        password: String? = null,
        passwordCredentialId: String? = null,
        portOverride: Int? = null,
    ): ImportRequest =
        when (kind) {
            "FTP" -> ImportRequest.FtpRequest(
                deviceId = deviceId,
                host = requireNotNull(host) { "FTP host missing" },
                port = portOverride ?: port ?: FtpImportClient.DEFAULT_PORT,
                username = username.orEmpty(),
                password = password,
                passwordCredentialId = passwordCredentialId,
                path = requireNotNull(path) { "FTP path missing" },
                directory = directory,
                clearBeforeImport = false,
                sourceLabel = sourceLabel,
            )
            "SFTP" -> ImportRequest.SftpRequest(
                deviceId = deviceId,
                host = requireNotNull(host) { "SFTP host missing" },
                port = portOverride ?: port ?: SftpImportClient.DEFAULT_PORT,
                username = username.orEmpty(),
                password = password,
                passwordCredentialId = passwordCredentialId,
                path = requireNotNull(path) { "SFTP path missing" },
                directory = directory,
                clearBeforeImport = false,
                sourceLabel = sourceLabel,
            )
            "URL" -> ImportRequest.UrlRequest(
                deviceId = deviceId,
                url = requireNotNull(url) { "URL missing" },
                sourceLabel = sourceLabel,
            )
            else -> error("Unsupported replay kind: $kind")
        }

    companion object {
        /** Query tokens or embedded userinfo must not be stored in Room/backups. */
        fun isSensitiveImportUrl(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.contains('?')) return true
            val schemeSep = trimmed.indexOf("://")
            if (schemeSep < 0) return false
            val authority = trimmed.substring(schemeSep + 3).substringBefore('/')
            return authority.contains('@')
        }
        fun fromJson(json: String): ImportReplayConfig {
            val obj = JSONObject(json)
            return ImportReplayConfig(
                kind = obj.getString("kind"),
                deviceId = obj.getLong("deviceId"),
                host = obj.optString("host").takeIf { it.isNotBlank() },
                port = if (obj.has("port") && !obj.isNull("port")) obj.getInt("port") else null,
                username = obj.optString("username").takeIf { it.isNotBlank() },
                path = obj.optString("path").takeIf { it.isNotBlank() },
                directory = obj.optBoolean("directory", false),
                url = obj.optString("url").takeIf { it.isNotBlank() },
                sourceLabel = obj.optString("sourceLabel").ifBlank { obj.getString("kind") },
            )
        }

        fun fromRequest(request: ImportRequest): ImportReplayConfig? {
            val deviceId = request.deviceId ?: return null
            return when (request) {
                is ImportRequest.FtpRequest -> ImportReplayConfig(
                    kind = "FTP",
                    deviceId = deviceId,
                    host = request.host,
                    port = request.port,
                    username = request.username,
                    path = request.path,
                    directory = request.directory,
                    sourceLabel = request.sourceLabel,
                )
                is ImportRequest.SftpRequest -> ImportReplayConfig(
                    kind = "SFTP",
                    deviceId = deviceId,
                    host = request.host,
                    port = request.port,
                    username = request.username,
                    path = request.path,
                    directory = request.directory,
                    sourceLabel = request.sourceLabel,
                )
                is ImportRequest.UrlRequest -> ImportReplayConfig(
                    kind = "URL",
                    deviceId = deviceId,
                    url = request.url,
                    sourceLabel = request.sourceLabel,
                )
                is ImportRequest.FileRequest -> null
            }
        }

        /**
         * Best-effort parse of older jobs that only stored labels like
         * `FTP folder 172.21.0.30:/smadata` or `SFTP host:/path/file.csv`.
         */
        fun fromLegacyJob(job: ImportJobEntity): ImportReplayConfig? {
            val deviceId = job.deviceId ?: return null
            val label = job.sourceLabel.trim()
            val folderMatch = Regex("""^(FTP|SFTP)\s+folder\s+(.+):(.+)$""", RegexOption.IGNORE_CASE)
                .matchEntire(label)
            if (folderMatch != null) {
                val kind = folderMatch.groupValues[1].uppercase()
                return ImportReplayConfig(
                    kind = kind,
                    deviceId = deviceId,
                    host = folderMatch.groupValues[2].trim(),
                    path = folderMatch.groupValues[3].trim(),
                    directory = true,
                    username = "",
                    sourceLabel = label,
                )
            }
            val fileMatch = Regex("""^(FTP|SFTP)\s+(.+):(.+)$""", RegexOption.IGNORE_CASE)
                .matchEntire(label)
            if (fileMatch != null) {
                val kind = fileMatch.groupValues[1].uppercase()
                return ImportReplayConfig(
                    kind = kind,
                    deviceId = deviceId,
                    host = fileMatch.groupValues[2].trim(),
                    path = fileMatch.groupValues[3].trim(),
                    directory = false,
                    username = "",
                    sourceLabel = label,
                )
            }
            if (job.sourceType == ImportSourceType.URL && label.isNotBlank()) {
                val url = label.removePrefix("URL import").trim().ifBlank { null } ?: return null
                if (!url.startsWith("http", ignoreCase = true)) return null
                return ImportReplayConfig(
                    kind = "URL",
                    deviceId = deviceId,
                    url = url,
                    sourceLabel = label,
                )
            }
            return null
        }
    }
}

fun ImportJobEntity.replayConfig(): ImportReplayConfig? =
    replayConfigJson?.let { runCatching { ImportReplayConfig.fromJson(it) }.getOrNull() }
        ?: ImportReplayConfig.fromLegacyJob(this)

fun ImportJobEntity.canReplay(): Boolean = replayConfig() != null
