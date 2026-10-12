package com.yausername.ffmpeg

import android.content.Context
import android.system.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

object FFmpeg {
    private var initialized = false
    private var binDir: File? = null

    @Synchronized
    fun init(appContext: Context) {
        if (initialized) return
        val baseDir = File(appContext.noBackupFilesDir, baseName)
        if (!baseDir.exists()) baseDir.mkdir()
        binDir = File(appContext.applicationInfo.nativeLibraryDir)
        val packagesDir = File(baseDir, packagesRoot)
        val ffmpegDir = File(packagesDir, ffmpegDirName)
        initFFmpeg(ffmpegDir)
        initialized = true
    }

    private fun initFFmpeg(ffmpegDir: File) {
        val ffmpegLib = File(binDir, ffmpegLibName)
        // Size alone is not enough as a version marker: layout 1 wrote the archive's symlinks
        // out as plain files holding the target path, which leaves the binaries unrunnable.
        // Folding the layout revision in forces existing installs to rebuild exactly once.
        val ffmpegSize = "$UNZIP_FORMAT:${ffmpegLib.length()}"
        if (!ffmpegDir.exists() || shouldUpdateFFmpeg(ffmpegSize, ffmpegDir)) {
            ffmpegDir.deleteRecursively()
            ffmpegDir.mkdirs()
            try {
                unzip(ffmpegLib, ffmpegDir)
            } catch (e: Exception) {
                ffmpegDir.deleteRecursively()
                throw RuntimeException("failed to initialize ffmpeg", e)
            }
            File(ffmpegDir, versionFileName).writeText(ffmpegSize)
        }
    }

    private fun shouldUpdateFFmpeg(version: String, ffmpegDir: File): Boolean {
        val marker = File(ffmpegDir, versionFileName)
        return !marker.exists() || marker.readText() != version
    }

    private fun unzip(zipFile: File, destDir: File) {
        // Symlink entries must be identified from the archive metadata. They cannot be told
        // apart by content: libffmpeg.zip.so also holds plain non-ELF files such as *.la,
        // headers and *.typelib, which have to be copied verbatim.
        val links = symlinkEntryNames(zipFile)
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    // Clear anything left by an earlier run, including a stale non-symlink,
                    // so Os.symlink cannot fail with EEXIST.
                    if (out.exists()) out.delete()
                    zip.getInputStream(entry).use { input ->
                        if (entry.name in links) {
                            link(readAll(input), out)
                        } else {
                            FileOutputStream(out).use { input.copyTo(it) }
                        }
                    }
                }
            }
        }
    }

    private fun readAll(input: java.io.InputStream): String =
        ByteArrayOutputStream()
            .apply { input.copyTo(this) }
            .toByteArray()
            .toString(Charsets.UTF_8)

    private fun link(target: String, out: File) {
        try {
            Os.symlink(target, out.absolutePath)
        } catch (e: Exception) {
            // Symlinks are creatable inside the app's own data directory, so this should not
            // happen. If a device refuses anyway, duplicating the resolved target still yields
            // a loadable file under the expected name, at the cost of some disk.
            val resolved = File(out.parentFile, target)
            if (resolved.exists()) {
                resolved.copyTo(out, overwrite = true)
            } else {
                throw RuntimeException("cannot restore symlink $target for ${out.name}", e)
            }
        }
    }

    /**
     * Names of the archive entries that are Unix symlinks, read from the ZIP central directory
     * where the mode lives in the high 16 bits of the external file attributes.
     *
     * `java.util.zip.ZipEntry` exposes no access to that field, so the directory is parsed by
     * hand. Returns an empty set if the layout is not understood, which degrades to the old
     * copy-everything behaviour rather than throwing.
     */
    private fun symlinkEntryNames(zipFile: File): Set<String> {
        val names = mutableSetOf<String>()
        try {
            RandomAccessFile(zipFile, "r").use { raf ->
                val length = raf.length()
                if (length < EOCD_SIZE) return emptySet()
                val tailSize = minOf(length, EOCD_SIZE.toLong() + MAX_COMMENT).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(length - tailSize)
                raf.readFully(tail)

                var eocd = -1
                var i = tail.size - EOCD_SIZE
                while (i >= 0) {
                    if (readIntLE(tail, i) == EOCD_SIGNATURE) {
                        eocd = i
                        break
                    }
                    i--
                }
                if (eocd < 0) return emptySet()

                val entryCount = readShortLE(tail, eocd + 10)
                var position = readIntLE(tail, eocd + 16).toLong() and 0xFFFFFFFFL
                repeat(entryCount) {
                    raf.seek(position)
                    val header = ByteArray(CENTRAL_HEADER_SIZE)
                    raf.readFully(header)
                    if (readIntLE(header, 0) != CENTRAL_SIGNATURE) return@repeat
                    val nameLength = readShortLE(header, 28)
                    val extraLength = readShortLE(header, 30)
                    val commentLength = readShortLE(header, 32)
                    val externalAttributes = readIntLE(header, 38)
                    val name = ByteArray(nameLength)
                    raf.readFully(name)
                    val mode = (externalAttributes ushr 16) and 0xFFFF
                    if ((mode and FILE_TYPE_MASK) == S_IFLNK) {
                        names.add(String(name, Charsets.UTF_8))
                    }
                    position += CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
                }
            }
        } catch (e: Exception) {
            return emptySet()
        }
        return names
    }

    private fun readShortLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    @JvmStatic fun getInstance() = this

    private const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val ffmpegDirName = "ffmpeg"
    private const val ffmpegLibName = "libffmpeg.zip.so"
    private const val versionFileName = ".version"

    /** Bumped whenever the extraction behaviour changes, to force one re-unzip. */
    private const val UNZIP_FORMAT = 2

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val EOCD_SIZE = 22
    private const val MAX_COMMENT = 0xFFFF
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val CENTRAL_HEADER_SIZE = 46
    private const val FILE_TYPE_MASK = 0xF000
    private const val S_IFLNK = 0xA000
}