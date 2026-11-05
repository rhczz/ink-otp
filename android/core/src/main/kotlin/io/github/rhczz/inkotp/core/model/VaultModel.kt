package io.github.rhczz.inkotp.core.model

import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpParameters
import io.github.rhczz.inkotp.core.otp.OtpType

data class VaultIcon(
    val kind: String,
    val value: String,
    val colorHex: String? = null,
)

data class VaultItem(
    val id: String,
    val type: OtpType,
    val issuer: String?,
    val accountName: String,
    val secret: ByteArray,
    val algorithm: OtpAlgorithm = OtpAlgorithm.SHA1,
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long? = null,
    val tags: List<String> = emptyList(),
    val icon: VaultIcon? = null,
    val sortIndex: Int,
    val origin: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val website: String? = null,
) {
    fun otpParameters(): OtpParameters = OtpParameters(
        secret = secret,
        algorithm = algorithm,
        digits = digits,
        period = period,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultItem) return false
        return id == other.id &&
            type == other.type &&
            issuer == other.issuer &&
            accountName == other.accountName &&
            secret.contentEquals(other.secret) &&
            algorithm == other.algorithm &&
            digits == other.digits &&
            period == other.period &&
            counter == other.counter &&
            tags == other.tags &&
            icon == other.icon &&
            sortIndex == other.sortIndex &&
            origin == other.origin &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt &&
            website == other.website
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (issuer?.hashCode() ?: 0)
        result = 31 * result + accountName.hashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + algorithm.hashCode()
        result = 31 * result + digits
        result = 31 * result + period
        result = 31 * result + (counter?.hashCode() ?: 0)
        result = 31 * result + tags.hashCode()
        result = 31 * result + (icon?.hashCode() ?: 0)
        result = 31 * result + sortIndex
        result = 31 * result + (origin?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + (website?.hashCode() ?: 0)
        return result
    }
}

data class VaultSettings(
    val lockTimeoutSeconds: Int = 30,
    val hideCodesByDefault: Boolean = false,
    val clipboardClearSeconds: Int = 30,
)

data class VaultModel(
    val schemaVersion: Int = 1,
    val vaultId: String,
    val createdAt: String,
    val updatedAt: String,
    val settings: VaultSettings = VaultSettings(),
    val items: List<VaultItem> = emptyList(),
)

class VaultSchemaException(
    message: String,
) : IllegalArgumentException(message)
