package net.bdfz.weibian.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSession(
    val slug: String,
    val displayName: String,
    /** `bdfz_uc_session=...`，原样作为 Cookie 头发送 */
    val cookie: String,
)

/**
 * 会话安全存储。
 *
 * 会话 Cookie 等同于用户身份，绝不明文落盘：密钥由 AndroidKeyStore 持有并且
 * 不可导出，App 只拿到句柄。解密失败一律清空重登，不做降级读取——
 * 一个读不出来的会话，最坏的处理方式是猜。
 */
class SecureSessionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("weibian_secure_session", Context.MODE_PRIVATE)

    fun read(): AppSession? = synchronized(SESSION_LOCK) {
        val encoded = prefs.getString(KEY_SESSION, null) ?: return@synchronized null
        runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > IV_SIZE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_SIZE_BITS, bytes.copyOfRange(0, IV_SIZE)),
            )
            val json = JSONObject(
                String(cipher.doFinal(bytes.copyOfRange(IV_SIZE, bytes.size)), Charsets.UTF_8),
            )
            AppSession(
                slug = json.getString("slug"),
                displayName = json.optString("displayName", json.getString("slug")),
                cookie = json.getString("cookie"),
            )
        }.getOrElse {
            clearLocked()
            null
        }
    }

    fun write(session: AppSession) = synchronized(SESSION_LOCK) {
        val clearText = JSONObject()
            .put("slug", session.slug)
            .put("displayName", session.displayName)
            .put("cookie", session.cookie)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(clearText)
        prefs.edit()
            .putString(KEY_SESSION, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun replaceIfUnchanged(
        expected: AppSession,
        replacement: AppSession,
    ): Boolean = synchronized(SESSION_LOCK) {
        if (read() != expected) return@synchronized false
        write(replacement)
        true
    }

    fun clearIfUnchanged(expected: AppSession): Boolean = synchronized(SESSION_LOCK) {
        if (read() != expected) return@synchronized false
        clearLocked()
        true
    }

    fun clear() = synchronized(SESSION_LOCK) {
        clearLocked()
    }

    private fun clearLocked() {
        prefs.edit().remove(KEY_SESSION).apply()
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
                    .apply {
                        // 锁屏后仍需后台同步，故不要求解锁；API 24 起可用。
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setUserAuthenticationRequired(false)
                        }
                    }
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "weibian_session_key"
        const val KEY_SESSION = "session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_SIZE_BITS = 128
        val SESSION_LOCK = Any()
    }
}
