package moe.fuqiuluo.shamrock.utils

import moe.fuqiuluo.shamrock.helper.Level
import moe.fuqiuluo.shamrock.helper.LogCenter
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

object MD5 {
    @Volatile
    private var nativeAvailable = true

    private val HEX = "0123456789ABCDEF"

    fun getMd5Hex(bytes: ByteArray): String {
        if (nativeAvailable) {
            try {
                return nativeGetMd5Hex(bytes)
            } catch (e: Throwable) {
                if (!markNativeUnavailable(e)) throw e
            }
        }
        return bytesToHex(digest(bytes))
    }

    fun genFileMd5Hex(filePath: String): String {
        if (nativeAvailable) {
            try {
                return nativeGenFileMd5Hex(filePath)
            } catch (e: Throwable) {
                if (!markNativeUnavailable(e)) throw e
            }
        }
        return bytesToHex(digestFile(File(filePath)))
    }

    fun genFileMd5(filePath: String): ByteArray {
        if (nativeAvailable) {
            try {
                return nativeGenFileMd5(filePath)
            } catch (e: Throwable) {
                if (!markNativeUnavailable(e)) throw e
            }
        }
        return digestFile(File(filePath))
    }

    private fun digest(bytes: ByteArray): ByteArray {
        return MessageDigest.getInstance("MD5").digest(bytes)
    }

    private fun digestFile(file: File): ByteArray {
        if (!file.exists()) {
            throw FileNotFoundException("目标文件不存在")
        }
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private fun markNativeUnavailable(error: Throwable): Boolean {
        if (!containsUnsatisfiedLinkError(error)) {
            return false
        }
        synchronized(this) {
            if (nativeAvailable) {
                nativeAvailable = false
                LogCenter.log("MD5 原生方法不可用，已切换到 Kotlin 实现", Level.WARN)
            }
        }
        return true
    }

    private fun containsUnsatisfiedLinkError(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 16) {
            if (current is UnsatisfiedLinkError) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private external fun nativeGetMd5Hex(bytes: ByteArray): String

    private external fun nativeGenFileMd5Hex(filePath: String): String

    private external fun nativeGenFileMd5(filePath: String): ByteArray
}
