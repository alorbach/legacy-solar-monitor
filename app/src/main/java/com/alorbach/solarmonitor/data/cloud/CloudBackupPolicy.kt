package com.alorbach.solarmonitor.data.cloud

enum class BackupTrigger {
    Manual,
    Auto,
}

data class BackupResult(
    val skipped: Boolean,
    val success: Boolean,
    val message: String,
    val uploadedCount: Int = 0,
    /** When set, the worker should schedule another Auto attempt after this many seconds. */
    val deferSeconds: Long? = null,
    /** True when failure is likely transient (network/5xx) and WorkManager should retry. */
    val retryable: Boolean = false,
)

object CloudBackupPolicy {
    const val UNIQUE_WORK_NAME = "cloud_backup"
    /** Coalesces throttled Auto follow-ups without cancelling an in-flight backup. */
    const val UNIQUE_DEFERRED_WORK_NAME = "cloud_backup_deferred"
    const val KEY_TRIGGER = "trigger"
    const val AUTO_THROTTLE_SECONDS = 15 * 60L
    const val DATABASE_BACKUP_FILENAME = "solar-monitor.db"

    /** Shown when no signed URL is saved yet; not enough to enable uploads by itself. */
    const val DEFAULT_SIGNED_URL_TEMPLATE =
        "https://storage.googleapis.com/{bucket}/{prefix}/{filename}"

    fun buildPathTemplate(bucket: String, prefix: String): String {
        val resolvedBucket = bucket.trim().ifBlank { "{bucket}" }
        val resolvedPrefix = prefix.trim().trim('/').ifBlank { "{prefix}" }
        return "https://storage.googleapis.com/$resolvedBucket/$resolvedPrefix/{filename}"
    }

    /** Object path for the database backup file using configured bucket/prefix. */
    fun buildDatabaseObjectUrl(bucket: String, prefix: String): String =
        buildPathTemplate(bucket, prefix).replace("{filename}", DATABASE_BACKUP_FILENAME)

    fun displaySignedUrlTemplate(stored: String, bucket: String = "", prefix: String = ""): String =
        stored.ifBlank { buildDatabaseObjectUrl(bucket, prefix) }

    /**
     * Rebuilds the unsigned object path from [bucket]/[prefix].
     * Any previous query string is dropped — GCS signatures bind to an exact object path.
     */
    fun withAutoPath(existingUrl: String, bucket: String, prefix: String): String {
        val hadQuery = existingUrl.substringAfter('?', missingDelimiterValue = "").isNotBlank()
        // Keep a concrete DB object path so a pasted signed PUT URL can match one object.
        return if (hadQuery || existingUrl.contains(DATABASE_BACKUP_FILENAME)) {
            buildDatabaseObjectUrl(bucket, prefix)
        } else {
            buildPathTemplate(bucket, prefix)
        }.let { pathOnly ->
            // Never reattach an old signature to a rewritten path.
            pathOnly
        }
    }

    /** True only when a signature query is present — bare auto-paths are not enough. */
    fun isUploadConfigured(signedUrl: String): Boolean {
        val url = signedUrl.trim()
        if (url.isBlank() || url == DEFAULT_SIGNED_URL_TEMPLATE) return false
        if (url == buildPathTemplate("", "")) return false
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val query = url.substringAfter('?', missingDelimiterValue = "")
        return query.isNotBlank()
    }

    /**
     * A pasted GCS signature covers one canonical object path. Filter staged filenames so we
     * never rewrite `{filename}` (or bucket/prefix) underneath an existing signature query.
     */
    fun selectableBackupFilenames(template: String, candidates: List<String>): List<String> {
        val hasQuery = template.substringAfter('?', missingDelimiterValue = "").isNotBlank()
        if (!hasQuery) return candidates
        val path = template.substringBefore('?')
        return if (path.contains("{filename}")) {
            // Placeholder + signature cannot be valid for multiple names; only the DB object
            // is supported (user must sign that exact path).
            candidates.filter { it == DATABASE_BACKUP_FILENAME }
        } else {
            // Signed URLs cover one object path. Prefer the longest candidate suffix so a
            // nested key like device-1/day.csv wins over a bare day.csv basename match.
            val exact = candidates.filter { candidate ->
                val name = candidate.trim('/')
                path.endsWith("/$name") || path == name
            }
            if (exact.isNotEmpty()) {
                val bestLen = exact.maxOf { it.trim('/').length }
                val chosen = exact.filter { it.trim('/').length == bestLen }
                val basename = path.substringAfterLast('/')
                // Prefer nested device-* copies over a legacy flat file when both exist.
                if (basename.isNotBlank() && chosen.all { !it.contains('/') }) {
                    val nested = candidates.filter {
                        it.contains('/') && it.substringAfterLast('/') == basename
                    }
                    if (nested.size == 1) return nested
                }
                return chosen
            }
            // Upgraded installs may still have a pre-nested signature for .../day.csv while
            // local copies are now device-N/day.csv. Only fall back for legacy flat object
            // paths (parent segment is not device-*), never for a nested device-N/ URL that
            // would otherwise silently upload another device's file.
            val basename = path.substringAfterLast('/')
            if (basename.isBlank()) return emptyList()
            val parentSegment = path.substringBeforeLast('/').substringAfterLast('/')
            if (parentSegment.startsWith("device-")) return emptyList()
            val byBase = candidates.filter { it.substringAfterLast('/') == basename }
            if (byBase.size == 1) byBase else emptyList()
        }
    }

    fun shouldThrottleAuto(nowEpochSeconds: Long, lastSuccessEpochSeconds: Long?): Boolean {
        val last = lastSuccessEpochSeconds ?: return false
        return nowEpochSeconds - last < AUTO_THROTTLE_SECONDS
    }

    fun throttleRemainingSeconds(nowEpochSeconds: Long, lastSuccessEpochSeconds: Long?): Long {
        val last = lastSuccessEpochSeconds ?: return 0L
        val remaining = AUTO_THROTTLE_SECONDS - (nowEpochSeconds - last)
        return remaining.coerceAtLeast(0L)
    }

    fun buildUploadUrl(template: String, bucket: String, prefix: String, filename: String): String =
        template
            .replace("{bucket}", bucket)
            .replace("{prefix}", prefix.trim('/'))
            .replace("{filename}", filename)

    fun resolveSkipReason(
        enabled: Boolean,
        signedUrlBlank: Boolean,
        includeDatabase: Boolean,
        includeImportCopies: Boolean,
    ): String? = when {
        !enabled || signedUrlBlank -> "Cloud backup is not configured"
        !includeDatabase && !includeImportCopies -> "No backup content selected"
        else -> null
    }

    fun isTransientUploadFailure(message: String?): Boolean {
        val text = message.orEmpty().lowercase()
        return text.contains("timeout") ||
            text.contains("timed out") ||
            text.contains("connection") ||
            text.contains("connect") ||
            text.contains("unreachable") ||
            text.contains("unable to resolve host") ||
            text.contains("unknownhost") ||
            text.contains("unavailable") ||
            text.contains("temporary") ||
            text.contains("gcs upload failed: 5") ||
            text.contains("gcs upload failed: 429")
    }
}
