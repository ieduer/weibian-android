package net.bdfz.weibian.sync

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts queued feedback before it reaches Room.
 *
 * Feedback text may contain private context, so the outbox never stores its
 * request body in plaintext. The mutation id is authenticated as AES-GCM AAD
 * to prevent swapping ciphertext between queue rows.
 */
class FeedbackPayloadCipher {
    fun encrypt(clientMutationId: String, clearText: String): String {
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(clientMutationId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(clearText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(clientMutationId: String, encoded: String): String {
        require(FEEDBACK_MUTATION_ID.matches(clientMutationId))
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > IV_SIZE)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_SIZE_BITS, bytes.copyOfRange(0, IV_SIZE)),
        )
        cipher.updateAAD(clientMutationId.toByteArray(Charsets.UTF_8))
        return String(
            cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)),
            Charsets.UTF_8,
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "weibian_feedback_outbox_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE_BITS = 128
    }
}
