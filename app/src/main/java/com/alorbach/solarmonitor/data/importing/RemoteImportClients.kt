package com.alorbach.solarmonitor.data.importing

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.net.ftp.FTPClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
    private val okHttpClient: OkHttpClient = SharedHttpClients.okHttp,
) {
    fun download(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "URL import failed: ${response.code}" }
            return response.body?.bytes() ?: error("Empty response body")
        }
    }
}

class FtpImportClient {
    fun download(host: String, username: String, password: String, path: String): ByteArray {
        val ftp = FTPClient()
        ftp.connectTimeout = 20_000
        ftp.defaultTimeout = 60_000
        ftp.setDataTimeout(java.time.Duration.ofMillis(60_000))
        try {
            ftp.connect(host)
            check(ftp.login(username, password)) { "FTP login failed" }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
            val out = ByteArrayOutputStream()
            check(ftp.retrieveFile(path, out)) { "FTP retrieve failed for $path" }
            return out.toByteArray()
        } finally {
            runCatching {
                if (ftp.isConnected) {
                    ftp.logout()
                    ftp.disconnect()
                }
            }
        }
    }
}

class SftpImportClient(
    private val knownHostsFile: File,
) {
    fun download(host: String, username: String, password: String, path: String): ByteArray {
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
        val session = jsch.getSession(username, host, 22).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "yes")
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
            return try {
                channel.get(path).use { input -> input.readBytes() }
            } catch (error: SftpException) {
                throw IOException("SFTP download of $path from $host failed: ${error.message}", error)
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
}

object ZipImportReader {
    data class EntryBytes(val name: String, val bytes: ByteArray)

    private const val MAX_ENTRY_BYTES = 50L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 200L * 1024L * 1024L

    fun flatten(bytes: ByteArray): List<EntryBytes> {
        val result = mutableListOf<EntryBytes>()
        var total = 0L
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .filterNot(ZipEntry::isDirectory)
                .forEach { entry ->
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
