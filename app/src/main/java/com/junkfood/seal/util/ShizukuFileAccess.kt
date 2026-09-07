package com.junkfood.seal.util

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import com.junkfood.seal.App
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Opt-in Shizuku-backed file operations. Shizuku does NOT grant this app's process shell
 * privileges — every operation is executed as a remote `sh -c` process running with shell (ADB)
 * UID, so only string-safe paths may ever reach [shellEscape].
 *
 * Scope is deliberately narrow (move/mkdir/exists/delete): downloads still run through yt-dlp
 * exactly as before. When the user's destination is a non-primary volume (OTG/SD card) or a
 * restricted path (e.g. another app's `Android/data`), yt-dlp writes to the app temp dir first and
 * the results are moved into place from here, replacing the slow SAF stream-copy path.
 */
object ShizukuFileAccess {

    private const val TAG = "ShizukuFileAccess"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4201

    /** Per-command ceiling. Same-volume `mv` is instant; cross-device mv (toybox) copies
     *  synchronously, so this needs room for multi-GB transfers to slow OTG media. */
    private const val COMMAND_TIMEOUT_MINUTES = 30L

    private val execMutex = Mutex()

    enum class ShizukuStatus {
        NOT_INSTALLED,
        NOT_RUNNING,
        NO_PERMISSION,
        READY,
    }

    fun currentStatus(): ShizukuStatus {
        if (!isShizukuInstalled()) return ShizukuStatus.NOT_INSTALLED
        if (!isShizukuRunning()) return ShizukuStatus.NOT_RUNNING
        if (!hasPermission()) return ShizukuStatus.NO_PERMISSION
        return ShizukuStatus.READY
    }

    fun isShizukuInstalled(): Boolean =
        runCatching {
            App.context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun isShizukuRunning(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** Must be called from the main thread (the permission dialog is routed to the activity). */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    fun addPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    /** True when Shizuku can be used right now for the move fast-path. */
    fun isReadyForFileOps(): Boolean = isShizukuRunning() && hasPermission()

    /**
     * Maps an SAF tree URI to a real filesystem path reachable by the shell user.
     * Returns null when the URI doesn't follow the `<volume>:<path>` tree-id convention
     * (caller must fall back to SAF).
     */
    fun resolveTreeUriRealPath(uri: Uri): String? =
        runCatching {
            val treeId = DocumentsContract.getTreeDocumentId(uri) // e.g. "3916-1838:Music" or
            val parts = treeId.split(":", limit = 2) // "primary:Download/Cat"
            if (parts.size != 2) return@runCatching null
            val volume = parts[0]
            val relative = parts[1]
            val volumeRoot =
                when (volume) {
                    "primary" -> Environment.getExternalStorageDirectory().absolutePath
                    else -> "/storage/$volume"
                }
            "$volumeRoot/$relative".trimEnd('/')
        }.getOrNull()

    /**
     * Moves every file under [tempPath] into [destDirPath] (a real path), preserving relative
     * subdirectories. Name collisions are resolved with "name (n).ext" suffixes, mirroring how
     * SAF's createDocument handles duplicates. Returns the absolute paths of moved files.
     */
    suspend fun moveDirectoryContents(tempPath: File, destDirPath: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dest = File(destDirPath)
                val moved = mutableListOf<String>()
                val files =
                    tempPath.walkTopDown().filter { it.isFile }.toList().ifEmpty {
                        return@runCatching emptyList<String>()
                    }
                checkMkdirs(destDirPath)
                files.forEach { file ->
                    val relative = file.relativeTo(tempPath).invariantSeparatorsPath
                    val relativeParent = relative.substringBeforeLast('/', "")
                    val targetDir =
                        if (relativeParent.isEmpty()) destDirPath
                        else "$destDirPath/$relativeParent"
                    if (relativeParent.isNotEmpty()) checkMkdirs(targetDir)
                    val finalName = pickNonCollidingName(targetDir, file.name)
                    val targetPath = "$targetDir/$finalName"
                    moveFile(file.absolutePath, targetPath)
                    moved.add(targetPath)
                }
                moved
            }
        }

    /** Moves one file. `mv` inside a shell handles the cross-device copy+unlink fallback. */
    suspend fun moveFile(sourcePath: String, targetPath: String): Result<Unit> =
        runShellCommand("mv -- ${shellEscape(sourcePath)} ${shellEscape(targetPath)}").map {}

    suspend fun checkMkdirs(dirPath: String): Result<Unit> =
        runShellCommand("mkdir -p -- ${shellEscape(dirPath)}").map {}

    suspend fun checkExists(path: String): Result<Boolean> =
        runShellCommand("test -e ${shellEscape(path)} && echo y || echo n").map {
            it.trim() == "y"
        }

    suspend fun deletePath(path: String, recursive: Boolean = false): Result<Unit> =
        runShellCommand(
            "rm ${if (recursive) "-r" else ""} -f -- ${shellEscape(path)}"
        ).map {}

    /**
     * Runs one `sh -c` command through Shizuku. Serialized through a mutex because Shizuku's
     * remote process pool is small and interleaving output reads is asking for garbled results.
     */
    private suspend fun runShellCommand(command: String): Result<String> =
        execMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val process = Shizuku.newProcess(arrayListOf("sh", "-c", command), null, null)
                    val stdout = process.inputStream.bufferedReader()
                    val stderr = process.stderr.bufferedReader()
                    val output =
                        StringBuilder().also { builder ->
                            stdout.forEachLine { line ->
                                builder.appendLine(line)
                            }
                        }
                    val finished = process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                    val errText = stderr.readText()
                    if (!finished) {
                        process.destroyForcibly()
                        error("Shizuku command timed out after ${COMMAND_TIMEOUT_MINUTES}m")
                    }
                    val exit = process.exitValue()
                    if (exit != 0) {
                        error(
                            "Shizuku command failed (exit $exit): " +
                                errText.ifBlank { output.toString() }.trim()
                        )
                    }
                    output.toString().trimEnd('\n')
                }.onFailure {
                    Log.w(TAG, "Shizuku shell failed: $command", it)
                }
            }
        }

    /** Single-quote shell escaping; the only safe way to embed user-controlled paths. */
    private fun shellEscape(arg: String): String = "'${arg.replace("'", "'\\''")}'"

    /**
     * Collision checks run through the shell on purpose: the app process cannot `stat` files on
     * secondary volumes (java.io sees them as nonexistent), the shell user can.
     */
    private suspend fun pickNonCollidingName(targetDir: String, fileName: String): String {
        var candidate = fileName
        var index = 1
        while (
            checkExists("$targetDir/$candidate").getOrDefault(false)
        ) {
            val dot = fileName.lastIndexOf('.')
            candidate =
                if (dot > 0) {
                    "${fileName.substring(0, dot)} ($index)${fileName.substring(dot)}"
                } else {
                    "$fileName ($index)"
                }
            index++
            if (index > 1000) error("Cannot find a non-colliding name for $fileName")
        }
        return candidate
    }
}
