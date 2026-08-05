package com.alorbach.solarmonitor.data.importing

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.net.ftp.FTPClient
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class UrlImportClient(
    private val okHttpClient: OkHttpClient = OkHttpClient(),
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
        ftp.connect(host)
        check(ftp.login(username, password)) { "FTP login failed" }
        ftp.enterLocalPassiveMode()
        ftp.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
        val out = ByteArrayOutputStream()
        check(ftp.retrieveFile(path, out)) { "FTP retrieve failed for $path" }
        ftp.logout()
        ftp.disconnect()
        return out.toByteArray()
    }
}

class SftpImportClient {
    fun download(host: String, username: String, password: String, path: String): ByteArray {
        val session = JSch().getSession(username, host, 22).apply {
            setPassword(password)
            setConfig("StrictHostKeyChecking", "no")
            connect(20_000)
        }
        val channel = (session.openChannel("sftp") as ChannelSftp).apply { connect(20_000) }
        return try {
            channel.get(path).use { input -> input.readBytes() }
        } finally {
            channel.disconnect()
            session.disconnect()
        }
    }
}

object ZipImportReader {
    data class EntryBytes(val name: String, val bytes: ByteArray)

    fun flatten(bytes: ByteArray): List<EntryBytes> {
        val result = mutableListOf<EntryBytes>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }
                .filterNot(ZipEntry::isDirectory)
                .forEach { entry ->
                    result += EntryBytes(entry.name.substringAfterLast('/'), zip.readBytes())
                }
        }
        return result
    }
}
