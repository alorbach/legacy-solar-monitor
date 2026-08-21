package com.alorbach.solarmonitor.data.importing

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns app-private preserved import files and serializes deletion against active imports.
 *
 * The device-existence callback prevents a worker that was already in flight when deletion
 * started from recreating files after the device record has been removed.
 */
class ImportCopyStore(
    private val appContext: Context,
    private val deviceExists: suspend (Long) -> Boolean,
) {
    private val deviceMutexes = ConcurrentHashMap<Long, Mutex>()

    private fun deviceMutex(deviceId: Long): Mutex =
        deviceMutexes.getOrPut(deviceId) { Mutex() }

    suspend fun deleteForDevice(
        deviceId: Long,
        legacyPaths: List<String>,
        afterCleanup: suspend () -> Unit,
    ) {
        deviceMutex(deviceId).withLock {
            deleteForDeviceLocked(deviceId, legacyPaths)
            afterCleanup()
        }
    }

    private suspend fun deleteForDeviceLocked(deviceId: Long, legacyPaths: List<String>) {
        withContext(Dispatchers.IO) {
            val importsRoot = appContext.getDir("imports", Context.MODE_PRIVATE)
            val targetDir = importsRoot.resolve("device-$deviceId")
            require(targetDir.isInside(importsRoot)) { "Invalid import path" }

            if (targetDir.exists()) {
                check(targetDir.deleteRecursively() || !targetDir.exists()) {
                    "Unable to delete preserved import copies for device $deviceId"
                }
            }
            legacyPaths
                .map(::File)
                .filter { it.exists() && it.isDirectChildOf(importsRoot) }
                .distinctBy { it.canonicalPath }
                .forEach { file ->
                    check(file.delete() || !file.exists()) {
                        "Unable to delete preserved import copy ${file.name}"
                    }
                }
        }
    }

    suspend fun store(
        deviceId: Long,
        relativeName: String,
        bytes: ByteArray,
        overwritePath: String? = null,
    ): String = withDeviceLock(deviceId) {
        require(deviceExists(deviceId)) {
            "Device no longer exists"
        }
        storeLocked(deviceId, relativeName, bytes, overwritePath)
    }

    private suspend fun storeLocked(
        deviceId: Long,
        relativeName: String,
        bytes: ByteArray,
        overwritePath: String?,
    ): String = withContext(Dispatchers.IO) {
        val importsRoot = appContext.getDir("imports", Context.MODE_PRIVATE)
        val targetDir = importsRoot.resolve("device-$deviceId")
        targetDir.mkdirs()
        if (!overwritePath.isNullOrBlank()) {
            val overwrite = File(overwritePath)
            require(overwrite.isInside(importsRoot)) {
                "Invalid import path"
            }
            // Legacy copies lived directly under imports/; migrate into device-<id>/.
            // Use Path component checks so device-10 is not treated as inside device-1.
            val target = if (overwrite.isInside(targetDir)) {
                overwrite
            } else {
                targetDir.resolve(overwrite.name)
            }
            require(target.isInside(targetDir)) {
                "Invalid import path"
            }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            if (target.canonicalPath != overwrite.canonicalPath && overwrite.exists()) {
                runCatching { overwrite.delete() }
            }
            target.absolutePath
        } else {
            val safeName = relativeName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace("..", "")
                .ifBlank { "import.bin" }
            val dot = safeName.lastIndexOf('.')
            val base = if (dot > 0) safeName.substring(0, dot) else safeName
            val ext = if (dot > 0) safeName.substring(dot) else ""
            var candidate = targetDir.resolve(safeName)
            var suffix = 1
            while (candidate.exists()) {
                candidate = targetDir.resolve("$base-$suffix$ext")
                suffix++
            }
            require(candidate.isInside(targetDir)) {
                "Invalid import path"
            }
            candidate.writeBytes(bytes)
            candidate.absolutePath
        }
    }

    private suspend fun <T> withDeviceLock(deviceId: Long, block: suspend () -> T): T =
        deviceMutex(deviceId).withLock { block() }
}

private fun File.isInside(directory: File): Boolean {
    val dirPath = directory.canonicalFile.toPath()
    val filePath = canonicalFile.toPath()
    return filePath.startsWith(dirPath)
}

private fun File.isDirectChildOf(directory: File): Boolean =
    parentFile?.canonicalFile == directory.canonicalFile
