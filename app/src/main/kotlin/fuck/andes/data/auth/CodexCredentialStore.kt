package fuck.andes.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CodexOAuthCredential(
    val accessToken: String,
    val refreshToken: String,
    val idToken: String,
    val accountId: String?,
    val expiresAtEpochMillis: Long,
) {
    override fun toString(): String =
        "CodexOAuthCredential(" +
            "accessToken=<redacted>, " +
            "refreshToken=<redacted>, " +
            "idToken=<redacted>, " +
            "accountId=<redacted>, " +
            "expiresAtEpochMillis=$expiresAtEpochMillis)"
}

internal interface CodexCredentialStore {
    fun load(providerId: String): CodexOAuthCredential?

    fun save(providerId: String, credential: CodexOAuthCredential)

    fun clear(providerId: String)
}

internal fun interface SecretKeyProvider {
    fun getOrCreate(): SecretKey
}

internal class AndroidKeyStoreSecretKeyProvider(
    private val alias: String = KEY_ALIAS,
) : SecretKeyProvider {
    override fun getOrCreate(): SecretKey = synchronized(KEYSTORE_LOCK) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "eta_codex_oauth_v1"
        const val KEY_SIZE_BITS = 256
        val KEYSTORE_LOCK = Any()
    }
}

internal class AndroidCodexCredentialStore(
    private val packageName: String,
    private val preferences: SharedPreferences,
    private val secretKeyProvider: SecretKeyProvider = AndroidKeyStoreSecretKeyProvider(),
) : CodexCredentialStore {
    constructor(
        context: Context,
        secretKeyProvider: SecretKeyProvider = AndroidKeyStoreSecretKeyProvider(),
    ) : this(
        packageName = context.applicationContext.packageName,
        preferences = context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
        secretKeyProvider = secretKeyProvider,
    )

    override fun load(providerId: String): CodexOAuthCredential? {
        val preferenceKey = preferenceKey(providerId)
        var envelope: ByteArray? = null
        var ciphertext: ByteArray? = null
        var plaintext: ByteArray? = null
        return try {
            val encoded = preferences.getString(preferenceKey, null) ?: return null
            envelope = Base64.decode(encoded, Base64.NO_WRAP)
            val parsed = parseEnvelope(envelope)
            ciphertext = parsed.ciphertext

            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKeyProvider.getOrCreate(),
                GCMParameterSpec(GCM_TAG_BITS, parsed.iv),
            )
            cipher.updateAAD(aad(providerId))
            plaintext = cipher.doFinal(ciphertext)
            decodeCredential(plaintext)
        } catch (_: Exception) {
            val cleanupCommitted = try {
                preferences.edit().remove(preferenceKey).commit()
            } catch (_: Exception) {
                false
            }
            if (!cleanupCommitted) {
                throw CodexCredentialStoreException("cleanup")
            }
            null
        } finally {
            envelope?.fill(0)
            ciphertext?.fill(0)
            plaintext?.fill(0)
        }
    }

    override fun save(providerId: String, credential: CodexOAuthCredential) {
        val plaintext = encodeCredential(credential)
        var ciphertext: ByteArray? = null
        var envelope: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeyProvider.getOrCreate())
            val iv = cipher.iv
            if (iv.size != IV_BYTES) {
                throw CodexCredentialStoreException("encryption")
            }
            cipher.updateAAD(aad(providerId))
            ciphertext = cipher.doFinal(plaintext)
            envelope = ByteBuffer.allocate(1 + IV_BYTES + ciphertext.size)
                .put(STORAGE_VERSION)
                .put(iv)
                .put(ciphertext)
                .array()

            val encoded = Base64.encodeToString(envelope, Base64.NO_WRAP)
            if (!preferences.edit().putString(preferenceKey(providerId), encoded).commit()) {
                throw CodexCredentialStoreException("persistence")
            }
        } catch (exception: CodexCredentialStoreException) {
            throw exception
        } catch (_: Exception) {
            throw CodexCredentialStoreException("encryption")
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
            envelope?.fill(0)
        }
    }

    override fun clear(providerId: String) {
        if (!preferences.edit().remove(preferenceKey(providerId)).commit()) {
            throw CodexCredentialStoreException("clear")
        }
    }

    private fun parseEnvelope(envelope: ByteArray): EncryptedEnvelope {
        if (envelope.size < 1 + IV_BYTES + GCM_TAG_BYTES || envelope[0] != STORAGE_VERSION) {
            throw CodexCredentialStoreException("decryption")
        }
        return EncryptedEnvelope(
            iv = envelope.copyOfRange(1, 1 + IV_BYTES),
            ciphertext = envelope.copyOfRange(1 + IV_BYTES, envelope.size),
        )
    }

    private fun aad(providerId: String): ByteArray =
        "$packageName|$providerId|v1".toByteArray(StandardCharsets.UTF_8)

    private fun preferenceKey(providerId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(providerId.toByteArray(StandardCharsets.UTF_8))
        return "credential_" + Base64.encodeToString(
            digest,
            Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE,
        )
    }

    private data class EncryptedEnvelope(
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    internal companion object {
        const val PREFERENCES_NAME = "eta_codex_oauth_credentials_v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val STORAGE_VERSION: Byte = 1
    }
}

internal class CodexCredentialStoreException(
    operation: String,
) : IllegalStateException("Codex credential $operation failed")

private const val PAYLOAD_VERSION: Byte = 1
private const val MAX_FIELD_BYTES = 256 * 1024
private const val MAX_PAYLOAD_BYTES = 1024 * 1024

private fun encodeCredential(credential: CodexOAuthCredential): ByteArray {
    val accessToken = credential.accessToken.toByteArray(StandardCharsets.UTF_8)
    val refreshToken = credential.refreshToken.toByteArray(StandardCharsets.UTF_8)
    val idToken = credential.idToken.toByteArray(StandardCharsets.UTF_8)
    val accountId = credential.accountId?.toByteArray(StandardCharsets.UTF_8)
    val fields = listOfNotNull(accessToken, refreshToken, idToken, accountId)
    try {
        if (fields.any { it.size > MAX_FIELD_BYTES }) {
            throw CodexCredentialStoreException("serialization")
        }
        val payloadSize = 1L +
            Integer.BYTES + accessToken.size +
            Integer.BYTES + refreshToken.size +
            Integer.BYTES + idToken.size +
            Integer.BYTES + (accountId?.size ?: 0) +
            java.lang.Long.BYTES
        if (payloadSize > MAX_PAYLOAD_BYTES) {
            throw CodexCredentialStoreException("serialization")
        }

        return ByteBuffer.allocate(payloadSize.toInt())
            .put(PAYLOAD_VERSION)
            .putSized(accessToken)
            .putSized(refreshToken)
            .putSized(idToken)
            .apply {
                if (accountId == null) putInt(-1) else putSized(accountId)
            }
            .putLong(credential.expiresAtEpochMillis)
            .array()
    } finally {
        fields.forEach { it.fill(0) }
    }
}

private fun decodeCredential(payload: ByteArray): CodexOAuthCredential {
    val buffer = ByteBuffer.wrap(payload)
    if (!buffer.hasRemaining() || buffer.get() != PAYLOAD_VERSION) {
        throw CodexCredentialStoreException("deserialization")
    }
    val credential = CodexOAuthCredential(
        accessToken = buffer.readString(),
        refreshToken = buffer.readString(),
        idToken = buffer.readString(),
        accountId = buffer.readNullableString(),
        expiresAtEpochMillis = buffer.readLongSafely(),
    )
    if (buffer.hasRemaining()) {
        throw CodexCredentialStoreException("deserialization")
    }
    return credential
}

private fun ByteBuffer.putSized(bytes: ByteArray): ByteBuffer = putInt(bytes.size).put(bytes)

private fun ByteBuffer.readString(): String {
    val length = readLength(nullable = false)
    return readUtf8(length)
}

private fun ByteBuffer.readNullableString(): String? {
    val length = readLength(nullable = true)
    return if (length == -1) null else readUtf8(length)
}

private fun ByteBuffer.readLength(nullable: Boolean): Int {
    if (remaining() < Integer.BYTES) {
        throw CodexCredentialStoreException("deserialization")
    }
    val length = int
    if (length < 0 && (!nullable || length != -1)) {
        throw CodexCredentialStoreException("deserialization")
    }
    if (length > MAX_FIELD_BYTES || length > remaining()) {
        throw CodexCredentialStoreException("deserialization")
    }
    return length
}

private fun ByteBuffer.readUtf8(length: Int): String {
    val bytes = ByteArray(length)
    get(bytes)
    return try {
        String(bytes, StandardCharsets.UTF_8)
    } finally {
        bytes.fill(0)
    }
}

private fun ByteBuffer.readLongSafely(): Long {
    if (remaining() < java.lang.Long.BYTES) {
        throw CodexCredentialStoreException("deserialization")
    }
    return long
}
