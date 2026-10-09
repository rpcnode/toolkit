package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmSecretBox(
    private val keyFile: Path,
    private val envKeyBase64: String? = null,
)
{
    fun encrypt(plain: String): String
    {
        val key = loadOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(nonce.size + sealed.size)
        nonce.copyInto(out)
        sealed.copyInto(out, nonce.size)
        return Base64.getEncoder().encodeToString(out)
    }

    fun decrypt(ciphertext: String): String
    {
        val raw = Base64.getDecoder().decode(ciphertext.trim())
        if (raw.size <= NONCE_BYTES)
        {
            error("ciphertext too short")
        }
        val key = loadOrCreateKey()
        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val body = raw.copyOfRange(NONCE_BYTES, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): ByteArray
    {
        val env = envKeyBase64?.trim().orEmpty()
        if (env.isNotEmpty())
        {
            val decoded = decodeBase64Key(env) ?: error("RPCNODE_NOTIFY_KEY: invalid base64")
            check(decoded.size == KEY_BYTES) { "notify key: need $KEY_BYTES bytes, got ${decoded.size}" }
            return decoded
        }
        if (Files.isRegularFile(keyFile))
        {
            val raw = Files.readAllBytes(keyFile)
            if (raw.size == KEY_BYTES)
            {
                return raw
            }
            val decoded = decodeBase64Key(String(raw, Charsets.UTF_8))
            if (decoded != null && decoded.size == KEY_BYTES)
            {
                return decoded
            }
            error("notify key file ${keyFile.fileName}: expected $KEY_BYTES raw bytes")
        }
        val key = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(key)
        val parent = keyFile.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        Files.write(keyFile, key)
        runCatching {
            Files.setPosixFilePermissions(keyFile, POSIX_OWNER_RW)
        }
        return key
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val POSIX_OWNER_RW = setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        )

        fun decodeBase64Key(text: String): ByteArray?
        {
            val t = text.trim()
            if (t.isEmpty())
            {
                return null
            }
            return runCatching { Base64.getDecoder().decode(t) }.getOrNull()
                ?: runCatching { Base64.getDecoder().decode(t + "=".repeat((4 - t.length % 4) % 4)) }.getOrNull()
        }
    }
}
