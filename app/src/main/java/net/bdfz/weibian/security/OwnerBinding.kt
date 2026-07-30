package net.bdfz.weibian.security

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable, one-way local partition identifiers.
 *
 * The hash namespace intentionally keeps the already-shipped feedback binding
 * input. Changing it would orphan encrypted authenticated feedback already
 * queued on a device. Learning data now reuses the same account binding, while
 * guest and pre-partition data use explicit non-account sentinels.
 */
const val GUEST_OWNER_BINDING = "guest-v1"
const val LEGACY_LOCAL_OWNER_BINDING = "legacy-local-v1"

fun accountOwnerBinding(slug: String?): String? {
    val normalized = slug?.trim()?.lowercase(Locale.US).orEmpty()
    if (normalized.isEmpty()) return null
    return MessageDigest.getInstance("SHA-256")
        .digest("weibian-feedback-owner-v1:$normalized".toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

fun AppSession.ownerBinding(): String =
    requireNotNull(accountOwnerBinding(slug)) { "账号标识无效" }

fun ownerBindingFor(session: AppSession?): String =
    session?.ownerBinding() ?: GUEST_OWNER_BINDING

fun isAccountOwnerBinding(ownerBinding: String): Boolean =
    ACCOUNT_OWNER_BINDING.matches(ownerBinding)

internal fun requireActiveOwnerBinding(ownerBinding: String): String {
    require(
        ownerBinding == GUEST_OWNER_BINDING ||
            isAccountOwnerBinding(ownerBinding)
    ) {
        "本地学习分区标识无效"
    }
    return ownerBinding
}

internal fun requireAccountOwnerBinding(ownerBinding: String): String {
    require(isAccountOwnerBinding(ownerBinding)) {
        "驗證答案只允許已登入帳號分區"
    }
    return ownerBinding
}

private val ACCOUNT_OWNER_BINDING = Regex("^[0-9a-f]{64}$")
