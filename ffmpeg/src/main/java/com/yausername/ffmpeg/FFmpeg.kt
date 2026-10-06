package com.yausername.ffmpeg

import android.content.Context
import java.io.File
import java.io.FileOutputStream
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
        // using size of lib as version
        val ffmpegSize = ffmpegLib.length().toString()
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
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    @JvmStatic
    fun getInstance() = this
    private const val baseName = "youtubedl-android"
    private const val packagesRoot = "packages"
    private const val ffmpegDirName = "ffmpeg"
    private const val ffmpegLibName = "libffmpeg.zip.so"
    private const val versionFileName = ".version"
}
