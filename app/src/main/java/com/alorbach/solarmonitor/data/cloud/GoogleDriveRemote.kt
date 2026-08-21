package com.alorbach.solarmonitor.data.cloud

import com.alorbach.solarmonitor.data.importing.SharedHttpClients
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

interface DriveRemote {
    suspend fun userEmail(): String
    suspend fun findOrCreateFolder(
        name: String,
        cachedId: String?,
        fallbackNames: List<String> = emptyList(),
    ): String
    suspend fun upsertFile(folderId: String, name: String, file: File)
    suspend fun downloadFile(
        folderId: String,
        name: String,
        target: File,
        maxBytes: Long,
    )
}

class GoogleDriveRemote(
    private val tokenProvider: suspend () -> String,
    client: OkHttpClient = SharedHttpClients.okHttp,
) : DriveRemote {
    private val http = client.newBuilder()
        .writeTimeout(5, TimeUnit.MINUTES)
        .readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    override suspend fun userEmail(): String {
        val json = execute(
            Request.Builder()
                .url("$DRIVE_API/about?fields=user(emailAddress)")
                .get()
                .build(),
        )
        return DriveJson.stringField(json, "emailAddress")
            ?: error("Drive about response missing email")
    }

    override suspend fun findOrCreateFolder(
        name: String,
        cachedId: String?,
        fallbackNames: List<String>,
    ): String {
        val lookupNames = (listOf(name) + fallbackNames).distinct()
        val byId = linkedMapOf<String, CloudBackupPolicy.DriveFolderCandidate>()

        suspend fun addCandidate(id: String, folderName: String) {
            if (id.isBlank() || byId.containsKey(id)) return
            byId[id] = CloudBackupPolicy.DriveFolderCandidate(
                id = id,
                name = folderName,
                hasDatabaseBackup = findInFolder(id, CloudBackupPolicy.DATABASE_BACKUP_FILENAME) != null,
            )
        }

        val cached = cachedId?.trim().orEmpty()
        if (cached.isNotBlank()) {
            val meta = getFileMeta(cached)
            if (meta != null && !meta.trashed) {
                addCandidate(cached, meta.name?.ifBlank { name } ?: name)
            }
        }
        for (folderName in lookupNames) {
            for (ref in findFoldersByName(folderName)) {
                addCandidate(ref.id, ref.name.ifBlank { folderName })
            }
        }

        val candidates = byId.values.toList()
        val chosen = CloudBackupPolicy.pickDriveFolder(name, candidates)
        if (chosen != null) {
            if (CloudBackupPolicy.shouldRenameDriveFolder(name, chosen, candidates)) {
                runCatching { renameFile(chosen.id, name) }
            }
            return chosen.id
        }
        val body = """{"name":${DriveJson.jsonString(name)},"mimeType":${DriveJson.jsonString(FOLDER_MIME)}}"""
        val json = execute(
            Request.Builder()
                .url("$DRIVE_API/files?fields=id")
                .post(body.toRequestBody(JSON))
                .build(),
        )
        return DriveJson.stringField(json, "id") ?: error("Drive folder create missing id")
    }

    override suspend fun upsertFile(folderId: String, name: String, file: File) {
        val existing = findInFolder(folderId, name)
        if (existing != null) {
            execute(
                Request.Builder()
                    .url("$DRIVE_UPLOAD/files/${existing.id}?uploadType=media")
                    .patch(file.asRequestBody(OCTET_STREAM))
                    .build(),
            )
            return
        }
        val metadata = """{"name":${DriveJson.jsonString(name)},"parents":[${DriveJson.jsonString(folderId)}]}"""
        execute(
            Request.Builder()
                .url("$DRIVE_UPLOAD/files?uploadType=multipart&fields=id")
                .post(createFileMultipart(metadata, file))
                .build(),
        )
    }

    override suspend fun downloadFile(
        folderId: String,
        name: String,
        target: File,
        maxBytes: Long,
    ) {
        val existing = findInFolder(folderId, name)
            ?: error("Drive file not found: $name")
        val request = authorized(
            Request.Builder()
                .url("$DRIVE_API/files/${existing.id}?alt=media")
                .get()
                .build(),
        )
        http.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Drive request failed: ${response.code}" }
            val body = response.body ?: error("Drive download empty")
            val declared = body.contentLength()
            if (declared >= 0) {
                require(declared <= maxBytes) {
                    "Drive download exceeds ${maxBytes / (1024 * 1024)} MiB limit"
                }
            }
            target.outputStream().use { output ->
                val buffer = ByteArray(8 * 1024)
                var total = 0L
                val input = body.byteStream()
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) {
                        "Drive download exceeds ${maxBytes / (1024 * 1024)} MiB limit"
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        require(target.exists() && target.length() > 0L) { "Drive download empty" }
    }

    private suspend fun findFoldersByName(name: String): List<DriveFileRef> =
        findFiles(
            "name='${DriveJson.driveQueryLiteral(name)}' and " +
                "mimeType='$FOLDER_MIME' and trashed=false and 'root' in parents",
        )

    private suspend fun findInFolder(folderId: String, name: String): DriveFileRef? =
        findFiles(
            "name='${DriveJson.driveQueryLiteral(name)}' and " +
                "'${DriveJson.driveQueryLiteral(folderId)}' in parents and trashed=false",
        ).firstOrNull()

    private suspend fun findFiles(query: String): List<DriveFileRef> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        val json = execute(
            Request.Builder()
                .url("$DRIVE_API/files?q=$encoded&spaces=drive&fields=files(id,name)&pageSize=50")
                .get()
                .build(),
        )
        return DriveJson.files(json)
    }

    private suspend fun getFileMeta(id: String): DriveFileMeta? {
        val encoded = URLEncoder.encode(id, Charsets.UTF_8.name())
        val request = authorized(
            Request.Builder()
                .url("$DRIVE_API/files/$encoded?fields=id,name,trashed")
                .get()
                .build(),
        )
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            require(response.isSuccessful) { "Drive request failed: ${response.code}" }
            val json = response.body?.string().orEmpty()
            val fileId = DriveJson.stringField(json, "id") ?: return null
            return DriveFileMeta(
                id = fileId,
                name = DriveJson.stringField(json, "name"),
                trashed = DriveJson.booleanField(json, "trashed") == true,
            )
        }
    }

    private suspend fun renameFile(id: String, newName: String) {
        val encoded = URLEncoder.encode(id, Charsets.UTF_8.name())
        execute(
            Request.Builder()
                .url("$DRIVE_API/files/$encoded")
                .patch("""{"name":${DriveJson.jsonString(newName)}}""".toRequestBody(JSON))
                .build(),
        )
    }

    private suspend fun execute(request: Request): String {
        val authorized = authorized(request)
        http.newCall(authorized).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Drive request failed: ${response.code}" }
            return body
        }
    }

    private suspend fun authorized(request: Request): Request {
        val token = tokenProvider()
        return request.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
    }

    companion object {
        private const val DRIVE_API = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
        private val MULTIPART_RELATED = "multipart/related".toMediaType()

        /**
         * Drive `uploadType=multipart` body. OkHttp forbids `Content-Type` on part headers;
         * each [okhttp3.RequestBody] already supplies it (otherwise: "Unexpected header: Content-Type").
         */
        internal fun createFileMultipart(
            metadataJson: String,
            file: File,
            boundary: String = "solar-monitor-${System.nanoTime()}",
        ): MultipartBody =
            MultipartBody.Builder(boundary)
                .setType(MULTIPART_RELATED)
                .addPart(metadataJson.toRequestBody(JSON))
                .addPart(file.asRequestBody(OCTET_STREAM))
                .build()
    }
}

private data class DriveFileMeta(
    val id: String,
    val name: String?,
    val trashed: Boolean,
)
