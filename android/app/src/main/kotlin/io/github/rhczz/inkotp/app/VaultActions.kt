package io.github.rhczz.inkotp.app

import io.github.rhczz.inkotp.core.backup.BackupMerge
import io.github.rhczz.inkotp.core.crypto.SecretBytes
import io.github.rhczz.inkotp.core.json.VaultJson
import io.github.rhczz.inkotp.core.model.VaultItem
import io.github.rhczz.inkotp.core.model.VaultModel
import io.github.rhczz.inkotp.core.otp.OtpEngine
import io.github.rhczz.inkotp.core.otp.OtpType
import io.github.rhczz.inkotp.core.uri.BareSecretResult
import io.github.rhczz.inkotp.core.uri.OtpUriResult
import io.github.rhczz.inkotp.core.uri.OtpauthParser
import io.github.rhczz.inkotp.core.util.TextNormalizer
import io.github.rhczz.inkotp.ui.model.VaultItemUi
import java.time.Instant
import java.util.UUID

class SafeInputException(message: String) : IllegalArgumentException(message)

/** Pure changes to a vault; persistence commits before the UI adopts a result. */
object VaultActions {
    private inline fun require(value: Boolean, message: () -> Any) {
        if (!value) throw SafeInputException(message().toString())
    }
    fun passwordError(password: String): String? = when {
        password.codePointCount(0, password.length) < 8 -> "恢复口令至少需要 8 个字符"
        password.length > 1024 -> "恢复口令不能超过 1024 个字符"
        password.all(Char::isDigit) -> "请勿使用纯数字恢复口令"
        password.lowercase() in setOf("password", "password123", "qwerty123", "abcdefgh", "12345678", "123456789", "11111111") -> "请换一个不常见的恢复口令"
        else -> null
    }

    fun add(model: VaultModel, issuer: String, account: String, input: String): VaultModel {
        require(input.length <= 16_384) { "密钥或链接过长" }
        val fields = when (val result = OtpauthParser.parse(input.trim())) {
            is OtpUriResult -> result.fields
            is BareSecretResult -> result.fields
            else -> throw IllegalArgumentException("暂不支持 Google 迁移二维码，请使用单个账号的二维码")
        }
        var adopted = false
        try {
            require(fields.type == OtpType.TOTP) { "此版本只支持添加 TOTP 账号" }
            val now = Instant.now().toString()
            val item = VaultItem(
                id = UUID.randomUUID().toString(), type = fields.type,
                issuer = normalize(issuer.ifBlank { fields.issuer.orEmpty() }, 128).ifBlank { null },
                accountName = normalize(account.ifBlank { fields.accountName.orEmpty() }, 256),
                secret = fields.secret, algorithm = fields.algorithm, digits = fields.digits,
                period = fields.period ?: 30, sortIndex = (model.items.size + 1) * 1000,
                origin = "manual", createdAt = now, updatedAt = now,
            )
            require(item.accountName.isNotBlank()) { "请输入账号名称" }
            require(model.items.none { BackupMerge.keyOf(it) == BackupMerge.keyOf(item) }) { "这个账号和密钥已经存在" }
            val result = validate(model.copy(items = model.items + item, updatedAt = now))
            adopted = true
            return result
        } finally {
            if (!adopted) SecretBytes.wipe(fields.secret)
        }
    }

    fun edit(model: VaultModel, id: String, issuer: String, account: String): VaultModel {
        val name = normalize(account, 256)
        require(name.isNotBlank()) { "账号名称不能为空" }
        val now = Instant.now().toString()
        val result = model.copy(updatedAt = now, items = model.items.map {
            if (it.id == id) it.copy(issuer = normalize(issuer, 128).ifBlank { null }, accountName = name, updatedAt = now) else it
        })
        require(result.items.map(BackupMerge::keyOf).distinct().size == result.items.size) { "修改后会与已有账号重复" }
        return validate(result)
    }

    fun delete(model: VaultModel, id: String): VaultModel = model.copy(
        updatedAt = Instant.now().toString(), items = model.items.filterNot { it.id == id },
    )

    fun move(model: VaultModel, id: String, direction: Int): VaultModel {
        val items = model.items.sortedBy { it.sortIndex }.toMutableList()
        val from = items.indexOfFirst { it.id == id }
        val to = from + direction
        if (from < 0 || to !in items.indices) return model
        val item = items.removeAt(from)
        items.add(to, item)
        return model.copy(updatedAt = Instant.now().toString(), items = items.mapIndexed { i, value -> value.copy(sortIndex = (i + 1) * 1000) })
    }

    fun project(model: VaultModel, epochSeconds: Long): List<VaultItemUi> = model.items.sortedBy { it.sortIndex }.map { item ->
        val supported = item.type == OtpType.TOTP
        val otp = if (supported) OtpEngine.totp(item.otpParameters(), epochSeconds.coerceAtLeast(0)) else null
        VaultItemUi(id = item.id, issuer = item.issuer, accountName = item.accountName,
            code = otp?.value ?: "—", digits = item.digits, remainingSeconds = otp?.remainingSeconds ?: 0,
            period = item.period, hidden = model.settings.hideCodesByDefault, type = item.type.name.lowercase(),
            algorithm = item.algorithm.name)
    }

    private fun normalize(value: String, max: Int): String {
        val text = TextNormalizer.normalize(value.trim()).value
        require(TextNormalizer.codePointLength(text) <= max) { "服务名或账号名称过长" }
        return text
    }

    private fun validate(model: VaultModel): VaultModel {
        val encoded = VaultJson.encode(model)
        SecretBytes.wipe(encoded)
        return model
    }
}
