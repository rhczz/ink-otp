package io.github.rhczz.inkotp.core.json

import io.github.rhczz.inkotp.core.model.VaultIcon
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.model.VaultSchemaException
import io.github.rhczz.inkotp.core.model.VaultSettings
import io.github.rhczz.inkotp.core.otp.OtpAlgorithm
import io.github.rhczz.inkotp.core.otp.OtpEngine
import io.github.rhczz.inkotp.core.otp.OtpException
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.util.TextNormalizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

class VaultJsonException(
    message: String,
) : IllegalArgumentException(message)

@Serializable
internal data class VaultDto(
    val schemaVersion: Int,
    val vaultId: String,
    val createdAt: String,
    val updatedAt: String,
    val settings: SettingsDto = SettingsDto(),
    val items: List<ItemDto>,
)

@Serializable
internal data class SettingsDto(
    val lockTimeoutSeconds: Int = 30,
    val hideCodesByDefault: Boolean = false,
    val clipboardClearSeconds: Int = 30,
)

@Serializable
internal data class ItemDto(
    val id: String,
    val type: String,
    val issuer: String? = null,
    val accountName: String,
    val secretB64: String,
    val algorithm: String = "SHA1",
    val digits: Int = 6,
    val period: Int = 30,
    val counter: Long? = null,
    val tags: List<String> = emptyList(),
    val icon: IconDto? = null,
    val sortIndex: Int,
    val origin: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val website: String? = null,
)

@Serializable
internal data class IconDto(
    val kind: String,
    val value: String,
    val colorHex: String? = null,
)

/**
 * Encoding/decoding of the decrypted vault JSON (design doc §5.2).
 *
 * The platform-neutral contract is:
 * - enums are uppercase strings, integers are explicit-width JSON numbers;
 * - `secretB64` is raw secret bytes in standard Base64 with padding;
 * - unknown fields are ignored on read (forward compatibility);
 * - `schemaVersion > 1` is rejected (no downgrade writes).
 */
object VaultJson {

    private const val MAX_ITEMS = 10_000
    private val base64 = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()
    private val colorRegex = Regex("^#[0-9A-Fa-f]{6}$")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(model: VaultModel): ByteArray {
        val normalized = validate(model)
        return json.encodeToString(VaultDto.serializer(), normalized.toDto())
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun encodeToString(model: VaultModel): String = String(encode(model), StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): VaultModel = decode(utf8(bytes))

    fun decode(text: String): VaultModel {
        val dto = try {
            json.decodeFromString(VaultDto.serializer(), text)
        } catch (_: SerializationException) {
            throw VaultJsonException("invalid vault JSON")
        } catch (_: IllegalArgumentException) {
            throw VaultJsonException("invalid vault JSON")
        }
        return validate(dto.toModel())
    }

    private fun utf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        throw VaultJsonException("vault JSON is not valid UTF-8")
    }

    private fun validate(model: VaultModel): VaultModel {
        if (model.schemaVersion != 1) throw VaultSchemaException("unsupported schemaVersion ${model.schemaVersion}")
        val vaultId = canonicalUuid(model.vaultId, "vaultId")
        requireInstant(model.createdAt, "createdAt")
        requireInstant(model.updatedAt, "updatedAt")
        if (model.settings.lockTimeoutSeconds !in 0..3600) {
            throw VaultSchemaException("lockTimeoutSeconds out of range")
        }
        if (model.settings.clipboardClearSeconds !in 0..300) {
            throw VaultSchemaException("clipboardClearSeconds out of range")
        }
        if (model.items.size > MAX_ITEMS) throw VaultSchemaException("too many items")

        val ids = HashSet<String>()
        val items = model.items.map { item ->
            val normalized = validateItem(item)
            if (!ids.add(normalized.id)) throw VaultSchemaException("duplicate item id")
            normalized
        }
        return model.copy(
            schemaVersion = 1,
            vaultId = vaultId,
            settings = model.settings.copy(
                lockTimeoutSeconds = model.settings.lockTimeoutSeconds,
                clipboardClearSeconds = model.settings.clipboardClearSeconds,
            ),
            items = items,
        )
    }

    private fun validateItem(item: VaultItem): VaultItem {
        val id = canonicalUuid(item.id, "item.id")
        val account = TextNormalizer.normalize(item.accountName).value
        if (account.isEmpty()) throw VaultSchemaException("accountName is empty")
        if (TextNormalizer.codePointLength(account) > 256) throw VaultSchemaException("accountName too long")

        val issuer = item.issuer?.let { TextNormalizer.normalize(it).value }?.takeIf { it.isNotEmpty() }
        if (issuer != null && TextNormalizer.codePointLength(issuer) > 128) {
            throw VaultSchemaException("issuer too long")
        }

        try {
            OtpEngine.validateSecret(item.secret)
            OtpEngine.validateDigits(item.digits)
            OtpEngine.validatePeriod(item.period)
        } catch (exception: OtpException) {
            throw VaultSchemaException("invalid OTP parameters: ${exception.code}")
        }
        when (item.type) {
            OtpType.TOTP -> if (item.counter != null) throw VaultSchemaException("TOTP must not have a counter")
            OtpType.HOTP -> {
                val counter = item.counter ?: throw VaultSchemaException("HOTP requires a counter")
                if (counter < 0) throw VaultSchemaException("HOTP counter is negative")
            }
        }

        if (item.tags.size > 20) throw VaultSchemaException("too many tags")
        val tags = item.tags.map { raw ->
            val tag = TextNormalizer.normalize(raw).value
            if (tag.isEmpty() || TextNormalizer.codePointLength(tag) > 32) {
                throw VaultSchemaException("invalid tag")
            }
            tag
        }

        val icon = item.icon?.let { raw ->
            val kind = TextNormalizer.normalize(raw.kind).value
            val value = TextNormalizer.normalize(raw.value).value
            if (kind.isEmpty() || kind.length > 32) throw VaultSchemaException("invalid icon kind")
            if (value.isEmpty() || TextNormalizer.codePointLength(value) > 2) {
                throw VaultSchemaException("invalid icon value")
            }
            val color = raw.colorHex?.takeIf { it.isNotEmpty() }
            if (color != null && !colorRegex.matches(color)) throw VaultSchemaException("invalid icon color")
            VaultIcon(kind = kind, value = value, colorHex = color)
        }

        val origin = item.origin?.let { TextNormalizer.normalize(it).value }?.takeIf { it.isNotEmpty() }
        if (origin != null && origin.length > 32) throw VaultSchemaException("origin too long")

        val website = item.website?.let { TextNormalizer.normalize(it).value }?.takeIf { it.isNotEmpty() }
        if (website != null && TextNormalizer.codePointLength(website) > 256) {
            throw VaultSchemaException("website too long")
        }

        requireInstant(item.createdAt, "item.createdAt")
        requireInstant(item.updatedAt, "item.updatedAt")

        return item.copy(
            id = id,
            accountName = account,
            issuer = issuer,
            tags = tags,
            icon = icon,
            origin = origin,
            website = website,
        )
    }

    private fun canonicalUuid(value: String, field: String): String = try {
        UUID.fromString(value).toString()
    } catch (_: IllegalArgumentException) {
        throw VaultSchemaException("invalid $field")
    }

    private fun requireInstant(value: String, field: String) {
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            throw VaultSchemaException("invalid $field")
        }
    }

    private fun VaultModel.toDto(): VaultDto = VaultDto(
        schemaVersion = schemaVersion,
        vaultId = vaultId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        settings = SettingsDto(
            lockTimeoutSeconds = settings.lockTimeoutSeconds,
            hideCodesByDefault = settings.hideCodesByDefault,
            clipboardClearSeconds = settings.clipboardClearSeconds,
        ),
        items = items.map { it.toDto() },
    )

    private fun VaultItem.toDto(): ItemDto = ItemDto(
        id = id,
        type = type.name.lowercase(),
        issuer = issuer,
        accountName = accountName,
        secretB64 = base64.encodeToString(secret),
        algorithm = algorithm.name,
        digits = digits,
        period = period,
        counter = counter,
        tags = tags,
        icon = icon?.let { IconDto(kind = it.kind, value = it.value, colorHex = it.colorHex) },
        sortIndex = sortIndex,
        origin = origin,
        createdAt = createdAt,
        updatedAt = updatedAt,
        website = website,
    )

    private fun VaultDto.toModel(): VaultModel = VaultModel(
        schemaVersion = schemaVersion,
        vaultId = vaultId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        settings = VaultSettings(
            lockTimeoutSeconds = settings.lockTimeoutSeconds,
            hideCodesByDefault = settings.hideCodesByDefault,
            clipboardClearSeconds = settings.clipboardClearSeconds,
        ),
        items = items.map { it.toModel() },
    )

    private fun ItemDto.toModel(): VaultItem {
        val type = when (type.uppercase()) {
            "TOTP" -> OtpType.TOTP
            "HOTP" -> OtpType.HOTP
            else -> throw VaultSchemaException("unknown item type '$type'")
        }
        val algorithm = OtpAlgorithm.fromSpecName(algorithm)
            ?: throw VaultSchemaException("unsupported algorithm '$algorithm'")
        val secret = try {
            base64Decoder.decode(secretB64)
        } catch (_: IllegalArgumentException) {
            throw VaultSchemaException("secretB64 is not valid Base64")
        }
        return VaultItem(
            id = id,
            type = type,
            issuer = issuer,
            accountName = accountName,
            secret = secret,
            algorithm = algorithm,
            digits = digits,
            period = period,
            counter = counter,
            tags = tags,
            icon = icon?.let { VaultIcon(kind = it.kind, value = it.value, colorHex = it.colorHex) },
            sortIndex = sortIndex,
            origin = origin,
            createdAt = createdAt,
            updatedAt = updatedAt,
            website = website,
        )
    }
}
