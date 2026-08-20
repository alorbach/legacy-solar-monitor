package com.alorbach.solarmonitor.data.importing

import android.content.Context
import com.alorbach.solarmonitor.R
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpATTRS
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Vector
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object SharedHttpClients {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
}

class UrlImportClient(
    private val context: Context? = null,
    okHttpClient: OkHttpClient = SharedHttpClients.okHttp,
) {
    private val okHttpClient: OkHttpClient = okHttpClient.newBuilder()
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            require(UrlImportPolicy.isAllowed(url)) {
                context?.getString(R.string.import_url_not_allowed)
                    ?: "Only HTTPS URLs, or HTTP on a private/LAN address, are allowed."
            }
            chain.proceed(chain.request())
        }
        .build()

    fun download(url: String): ByteArray {
        require(UrlImportPolicy.isAllowed(url)) {
            context?.getString(R.string.import_url_not_allowed)
                ?: "Only HTTPS URLs, or HTTP on a private/LAN address, are allowed."
        }
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                context?.getString(R.string.import_url_http_failed, response.code)
                    ?: "URL import failed: ${response.code}"
            }
            val body = response.body ?: error(
                context?.getString(R.string.import_empty_response) ?: "Empty response body",
            )
            val declared = body.contentLength()
            if (declared >= 0) {
                require(declared <= RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES) {
                    val mib = (RemoteBrowseHelpers.MAX_IMPORT_FILE_BYTES / (1024 * 1024)).toInt()
                    context?.getString(R.string.import_file_too_large_mib, mib)
                        ?: "Import file exceeds $mib MiB limit"
                }
            }
            return body.byteStream().use { RemoteBrowseHelpers.readBytesCapped(it) }
        }
    }
}

class FtpImportClient(
    private val context: Context? = null,
) {
    class FtpSession internal constructor(
        private val ftp: FTPClient,
        private val context: Context?,
    ) {
        fun list(path: String): List<RemoteEntry> = listOn(ftp, path, context)
        fun download(path: String): ByteArray = downloadOn(ftp, path, context)
        fun noop() {
            check(ftp.sendNoOp()) { "FTP NOOP failed" }
        }
    }

    /** Current working directory after login (home for most accounts). */
    fun workingDirectory(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
    ): String = withClient(host, port, username, password) { ftp ->
        RemoteBrowseHelpers.normalizeDirectory(ftp.printWorkingDirectory().orEmpty().ifBlank { "/" })
    }

    fun download(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): ByteArray = withClient(host, port, username, password) { ftp ->
        downloadOn(ftp, path, context)
    }

    fun list(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> = withClient(host, port, username, password) { ftp ->
        listOn(ftp, path, context)
    }

    /** Keep one FTP login for recursive listing + many downloads. */
    fun <T> withSession(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
        block: (FtpSession) -> T,
    ): T = withClient(host, port, username, password) { ftp ->
        block(FtpSession(ftp, context))
    }

    private fun <T> withClient(
        host: String,
        port: Int,
        username: String,
        password: String,
        block: (FTPClient) -> T,
    ): T {
        val ftp = FTPClient()
        ftp.connectTimeout = 20_000
        ftp.defaultTimeout = 60_000
        ftp.setDataTimeout(java.time.Duration.ofMillis(60_000))
        try {
            ftp.connect(host, port)
            check(ftp.login(username, password)) { "FTP login failed" }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            return block(ftp)
        } finally {
            runCatching {
                if (ftp.isConnected) {
                    ftp.logout()
                    ftp.disconnect()
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 21

        private fun listOn(ftp: FTPClient, path: String, context: Context?): List<RemoteEntry> {
            val dir = RemoteBrowseHelpers.normalizeDirectory(path)
            val files: Array<FTPFile> = ftp.listFiles(dir)
                ?: error(context?.getString(R.string.import_ftp_list_failed, dir) ?: "FTP list failed for $dir")
            return files.mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (name == "." || name == "..") return@mapNotNull null
                RemoteEntry(
                    name = name,
                    path = RemoteBrowseHelpers.joinPath(dir, name),
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.size.takeIf { it >= 0 } else null,
                )
            }
        }

        private fun downloadOn(ftp: FTPClient, path: String, context: Context?): ByteArray {
            val stream = ftp.retrieveFileStream(path)
                ?: error(context?.getString(R.string.import_ftp_retrieve_failed, path) ?: "FTP retrieve failed for $path")
            try {
                return RemoteBrowseHelpers.readBytesCapped(stream)
            } finally {
                runCatching { stream.close() }
                check(ftp.completePendingCommand()) {
                    context?.getString(R.string.import_ftp_retrieve_failed, path) ?: "FTP retrieve failed for $path"
                }
            }
        }
    }
}

class SftpImportClient(
    private val knownHostsFile: File,
) {
    fun download(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): ByteArray = withChannel(host, port, username, password) { channel ->
        downloadOn(channel, host, path)
    }

    fun list(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
        path: String,
    ): List<RemoteEntry> = withChannel(host, port, username, password) { channel ->
        listOn(channel, host, path)
    }

    /** Keep one SFTP channel for recursive listing + many downloads. */
    fun <T> withSession(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
        block: (SftpSession) -> T,
    ): T = withChannel(host, port, username, password) { channel ->
        block(SftpSession(channel, host))
    }

    class SftpSession internal constructor(
        private val channel: ChannelSftp,
        private val host: String,
    ) {
        fun list(path: String): List<RemoteEntry> = listOn(channel, host, path)
        fun download(path: String): ByteArray = downloadOn(channel, host, path)
        fun keepAlive() {
            channel.session?.sendKeepAliveMsg()
        }
    }

    fun workingDirectory(
        host: String,
        port: Int = DEFAULT_PORT,
        username: String,
        password: String,
    ): String = withChannel(host, port, username, password) { channel ->
        RemoteBrowseHelpers.normalizeDirectory(channel.pwd().orEmpty().ifBlank { "/" })
    }

    private fun <T> withChannel(
        host: String,
        port: Int,
        username: String,
        password: String,
        block: (ChannelSftp) -> T,
    ): T {
        knownHostsFile.parentFile?.mkdirs()
        if (!knownHostsFile.exists()) {
            knownHostsFile.createNewFile()
        }
        val jsch = JSch()
        jsch.setKnownHosts(knownHostsFile.absolutePath)
        val knownHosts = jsch.hostKeyRepository
        // Trust-on-first-use: persist unknown keys, but keep CHANGED as a hard failure.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                val result = knownHosts.check(host, key)
                if (result == HostKeyRepository.NOT_INCLUDED && host != null && key != null) {
                    knownHosts.add(HostKey(host, key), null)
                    return HostKeyRepository.OK
                }
                return result
            }

            override fun add(hostkey: HostKey?, ui: UserInfo?) = knownHosts.add(hostkey, ui)
            override fun remove(host: String?, type: String?) = knownHosts.remove(host, type)
            override fun remove(host: String?, type: String?, key: ByteArray?) = knownHosts.remove(host, type, key)
            override fun getKnownHostsRepositoryID(): String? = knownHosts.knownHostsRepositoryID
            override fun getHostKey(): Array<HostKey>? = knownHosts.hostKey
            override fun getHostKey(host: String?, type: String?): Array<HostKey>? = knownHosts.getHostKey(host, type)
        }
        val session = jsch.getSession(username, host, port).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "yes")
            // Keep the control channel alive while local parse/DB work runs between downloads.
            serverAliveInterval = 15_000
            serverAliveCountMax = 8
        }
        try {
            session.connect(20_000)
        } catch (error: JSchException) {
            throw connectionFailure(host, error)
        }
        try {
            val channel = try {
                (session.openChannel("sftp") as ChannelSftp).apply { connect(20_000) }
            } catch (error: JSchException) {
                throw connectionFailure(host, error)
            }
            try {
                return block(channel)
            } finally {
                channel.disconnect()
            }
        } finally {
            session.disconnect()
        }
    }

    /** Only a rejected key is a host key problem; auth failures and timeouts keep their own cause. */
    private fun connectionFailure(host: String, error: JSchException): IOException {
        val message = error.message.orEmpty()
        return if (message.contains("hostkey", ignoreCase = true) || message.contains("host key", ignoreCase = true)) {
            IOException(
                "SFTP host key verification failed for $host. The server key changed or was rejected; " +
                    "remove its entry from ${knownHostsFile.absolutePath} if the change is expected.",
                error,
            )
        } else {
            IOException("SFTP connection to $host failed: ${message.ifBlank { error::class.java.simpleName }}", error)
        }
    }

    companion object {
        const val DEFAULT_PORT = 22

        private fun listOn(channel: ChannelSftp, host: String, path: String): List<RemoteEntry> {
            val dir = RemoteBrowseHelpers.normalizeDirectory(path)
            try {
                @Suppress("UNCHECKED_CAST")
                val entries = channel.ls(dir) as Vector<ChannelSftp.LsEntry>
                return entries.mapNotNull { entry ->
                    val name = entry.filename ?: return@mapNotNull null
                    if (name == "." || name == "..") return@mapNotNull null
                    val attrs: SftpATTRS? = entry.attrs
                    RemoteEntry(
                        name = name,
                        path = RemoteBrowseHelpers.joinPath(dir, name),
                        isDirectory = attrs?.isDir == true,
                        size = attrs?.takeIf { !it.isDir }?.size?.takeIf { it >= 0 },
                    )
                }
            } catch (error: SftpException) {
                throw IOException("SFTP list of $dir on $host failed: ${error.message}", error)
            }
        }

        private fun downloadOn(channel: ChannelSftp, host: String, path: String): ByteArray {
            try {
                return channel.get(path).use { input -> RemoteBrowseHelpers.readBytesCapped(input) }
            } catch (error: SftpException) {
                throw IOException("SFTP download of $path from $host failed: ${error.message}", error)
            }
        }
    }
}

object ZipImportReader {
    data class EntryBytes(val name: String, val bytes: ByteArray)

    const val MAX_ENTRY_COUNT = 5_000
    private const val MAX_ENTRY_BYTES = 50L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 200L * 1024L * 1024L

    fun shouldParseFlattenedEntry(name: String): Boolean =
        !name.endsWith(".zip", ignoreCase = true)

    fun flatten(bytes: ByteArray): List<EntryBytes> {
        val result = mutableListOf<EntryBytes>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .filterNot(ZipEntry::isDirectory)
                .forEach { entry ->
                    require(result.size < MAX_ENTRY_COUNT) { "ZIP archive exceeds entry count limit" }
                    val safeName = entry.name
                        .substringAfterLast('/')
                        .replace("..", "")
                        .ifBlank { "entry.bin" }
                    val content = zip.readNBytesCapped(MAX_ENTRY_BYTES)
                    total += content.size
                    require(total <= MAX_TOTAL_BYTES) { "ZIP archive exceeds size limit" }
                    result += EntryBytes(safeName, content)
                }
        }
        return result
    }

    private fun ZipInputStream.readNBytesCapped(max: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= max) { "ZIP entry exceeds size limit" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

private typealias IOException = java.io.IOException
