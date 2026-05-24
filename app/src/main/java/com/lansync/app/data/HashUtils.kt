package com.lansync.app.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object HashUtils {

    fun sha256(file: File): String {
        return try {
            hashFile(file, "SHA-256")
        } catch (e: Exception) {
            ""
        }
    }

    fun sha256(paths: List<String>): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            paths.forEach { path -> digestFile(digest, path) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    fun md5(file: File): String {
        return try {
            hashFile(file, "MD5")
        } catch (e: Exception) {
            ""
        }
    }

    fun md5(paths: List<String>): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            paths.forEach { path -> digestFile(digest, path) }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    fun md5(inputStream: InputStream): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
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

    private fun digestFile(digest: MessageDigest, path: String) {
        try {
            val file = File(path)
            if (file.canRead()) {
                file.inputStream().use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}