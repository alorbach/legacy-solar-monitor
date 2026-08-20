package com.alorbach.solarmonitor.data.cloud

import com.alorbach.solarmonitor.data.importing.SharedHttpClients
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

interface DriveRemote {
    suspend fun userEmail(): String
    suspend fun findOrCreateFolder(name: String, cachedId: String?): String
    suspend fun upsertFile(folderId: String, name: String, file: File)
    suspend fun downloadFile(folderId: String, name: String, target: File)
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

    override suspend fun findOrCreateFolder(name: String, cachedId: String?): String {
        val cached = cachedId?.trim().orEmpty()
        if (cached.isNotBlank() && fileExists(cached)) return cached
        val existing = findFiles(
            "name='${DriveJson.driveQueryLiteral(name)}' and " +
                "mimeType='$FOLDER_MIME' and trashed=false and 'root' in parents",
        ).firstOrNull()
        if (existing != null) return existing.id
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
        val boundary = "solar-monitor-${System.nanoTime()}"
        val metadata = """{"name":${DriveJson.jsonString(name)},"parents":[${DriveJson.jsonString(folderId)}]}"""
        val multipart = MultipartBody.Builder(boundary)
            .setType("multipart/related".toMediaType())
            .addPart(
                Headers.headersOf("Content-Type", "application/json; charset=UTF-8"),
                metadata.toRequestBody(JSON),
            )
            .addPart(
                Headers.headersOf("Content-Type", "application/octet-stream"),
                file.asRequestBody(OCTET_STREAM),
            )
            .build()
        execute(
            Request.Builder()
                .url("$DRIVE_UPLOAD/files?uploadType=multipart&fields=id")
                .post(multipart)
                .build(),
        )
    }

    override suspend fun downloadFile(folderId: String, name: String, target: File) {
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
            target.outputStream().use { output -> body.byteStream().copyTo(output) }
        }
        require(target.exists() && target.length() > 0L) { "Drive download empty" }
    }

    private suspend fun findInFolder(folderId: String, name: String): DriveFileRef? =
        findFiles(
            "name='${DriveJson.driveQueryLiteral(name)}' and " +
                "'${DriveJson.driveQueryLiteral(folderId)}' in parents and trashed=false",
        ).firstOrNull()

    private suspend fun findFiles(query: String): List<DriveFileRef> {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name()).replace("+", "%20")
        val json = execute(
            Request.Builder()
                .url("$DRIVE_API/files?q=$encoded&spaces=drive&fields=files(id,name)&pageSize=10")
                .get()
                .build(),
        )
        return DriveJson.files(json)
    }

    private suspend fun fileExists(id: String): Boolean {
        val encoded = URLEncoder.encode(id, Charsets.UTF_8.name())
        val request = authorized(
            Request.Builder()
                .url("$DRIVE_API/files/$encoded?fields=id,trashed")
                .get()
                .build(),
        )
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return false
            require(response.isSuccessful) { "Drive request failed: ${response.code}" }
            val json = response.body?.string().orEmpty()
            return DriveJson.booleanField(json, "trashed") != true
        }
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
    }
}
