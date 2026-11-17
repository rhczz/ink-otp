package io.github.rhczz.inkotp.data

enum class VaultAccessError(val message: String) {
    LOCKED("库已锁定，请重新解锁。"),
    ALREADY_EXISTS("本机已存在加密库，不能重新创建。"),
    NOT_CREATED("尚未创建加密库。"),
    PASSWORD_REQUIRED("请先设置或输入本机恢复口令。"),
    PASSWORD_INCORRECT("恢复口令不正确。"),
    WEAK_PASSWORD("恢复口令至少 8 个字符，不能使用纯数字或常见弱口令。"),
    DEVICE_UNAVAILABLE("设备密钥不可用，请用恢复口令解锁。"),
    DEVICE_LOCKED("请先解锁手机，再打开验证器。"),
    BIOMETRIC_CHANGED("生物识别已变更或失效，请用恢复口令解锁后重新开启。"),
    STORAGE_FAILURE("无法读写加密库，请重试；不要清除 App 数据。"),
    INVALID_STATE("当前操作已失效，请重新打开后重试。"),
    SYSTEM_AUTH_REQUIRED("需要先完成系统身份验证，账号仍保持锁定。"),
    SYSTEM_AUTH_RECOVERY_REQUIRED("设备验证密钥已失效，请完成系统验证并输入恢复口令重新绑定。"),
    SYSTEM_AUTH_UNAVAILABLE("无法读取系统验证策略，请重试；不会绕过验证或清空账号。"),
    SYSTEM_AUTH_EXPIRED("这次系统身份验证已失效，请重新验证。"),
}

class VaultAccessException(val code: VaultAccessError) : IllegalStateException(code.message)
