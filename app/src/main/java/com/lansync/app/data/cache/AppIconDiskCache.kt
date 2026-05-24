package com.lansync.app.data.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

class AppIconDiskCache(private val context: Context) {

    private val cacheDir: File = File(context.filesDir, "app_icons")

    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    fun get(packageName: String, targetSize: Int = 96): Bitmap? {
        val file = iconFile(packageName)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val scaleFactor = maxOf(
                options.outWidth / targetSize,
                options.outHeight / targetSize,
                1
            )
            options.inJustDecodeBounds = false
            options.inSampleSize = Integer.highestOneBit(scaleFactor)
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    fun put(packageName: String, bitmap: Bitmap) {
        val file = iconFile(packageName)
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (_: Exception) {
        }
    }

    fun batchPut(icons: Map<String, Bitmap>) {
        for ((pkg, bitmap) in icons) {
            put(pkg, bitmap)
        }
    }

    fun remove(packageName: String) {
        iconFile(packageName).delete()
    }

    fun removeStale(validPackages: Set<String>) {
        cacheDir.listFiles()?.forEach { file ->
            val pkgName = file.nameWithoutExtension
            if (pkgName !in validPackages) {
                file.delete()
            }
        }
    }

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun exists(packageName: String): Boolean = iconFile(packageName).exists()

    fun size(): Int = cacheDir.listFiles()?.size ?: 0

    fun totalBytes(): Long = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun iconFile(packageName: String): File =
        File(cacheDir, "${sanitizeFileName(packageName)}.png")

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    companion object {
        @Volatile
        private var instance: AppIconDiskCache? = null

        fun getInstance(context: Context): AppIconDiskCache =
            instance ?: synchronized(this) {
                instance ?: AppIconDiskCache(context.applicationContext).also { instance = it }
            }
    }
}