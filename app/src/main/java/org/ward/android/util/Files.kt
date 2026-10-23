package org.ward.android.util

import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class AesgcmLink(val httpsUrl: String, val iv: ByteArray, val key: ByteArray, val fragment: String)

object FileCrypto {
    private const val CHUNK = 128 * 1024
    private const val TAG = 16

    fun encrypt(src: File, dst: File): Pair<ByteArray, ByteArray> {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(cipher.update(buf, 0, n))
                }
                output.write(cipher.doFinal())
            }
        }
        return key to iv
    }

    fun decrypt(src: File, dst: File, key: ByteArray, iv: ByteArray) {
        val size = src.length()
        require(size >= TAG) { "encrypted file is too short" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(cipher.update(buf, 0, n))
                }
                output.write(cipher.doFinal())
            }
        }
    }

    fun aesgcmUrl(httpsUrl: String, iv: ByteArray, key: ByteArray): String {
        require(httpsUrl.startsWith("https://")) { "encrypted files need an HTTPS upload URL" }
        return "aesgcm://" + httpsUrl.removePrefix("https://") + "#" + (iv + key).joinToString("") { "%02x".format(it) }
    }

    fun parse(uri: String): AesgcmLink? {
        if (!uri.startsWith("aesgcm://", ignoreCase = true)) return null
        val body = uri.substring(9)
        val hash = body.indexOf('#')
        if (hash <= 0) return null
        val frag = body.substring(hash + 1)
        if (frag.length != 88 && frag.length != 96) return null
        val raw = try { frag.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (_: Exception) { return null }
        return when (raw.size) {
            44 -> AesgcmLink("https://" + body.substring(0, hash), raw.copyOfRange(0, 12), raw.copyOfRange(12, 44), frag)
            48 -> AesgcmLink("https://" + body.substring(0, hash), raw.copyOfRange(0, 16), raw.copyOfRange(16, 48), frag)
            else -> null
        }
    }

    fun cacheName(url: String, name: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + "-" + name.replace('/', '_')
}
