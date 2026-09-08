package com.lansync.app.data

import java.io.File
import java.security.MessageDigest

object HashUtils {

    fun md5(file: File): String? {
        return try {
            hashFile(file, "MD5")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 对多个文件字节顺序拼接做 MD5（版本内容指纹，SPEC.md §8.1）。
     *
     * 新语义（SPEC.md §8.2/§8.3）：**null 传播**——空列表、任一文件不可读或读取出错均返回 null，
     * 不再静默跳过不可读文件而产出空摘要幽灵指纹 d41d8cd98f00b204e9800998ecf8427e。
     */
    fun md5(paths: List<String>): String? {
        if (paths.isEmpty()) return null
        return try {
            val digest = MessageDigest.getInstance("MD5")
            for (path in paths) {
                if (!digestFile(digest, path)) return null
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun hashFile(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 读取单个文件更新摘要；成功返回 true，不可读或异常返回 false（触发 null 传播）。 */
    private fun digestFile(digest: MessageDigest, path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.canRead()) return false
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            true
        } catch (e: Exception) {
            com.lansync.app.data.FileLogger.w("HashUtils", "digestFile error for $path: ${e.message}")
            false
        }
    }
}