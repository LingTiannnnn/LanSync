package com.lansync.app.data.model

import kotlinx.serialization.Serializable

/**
 * 统一错误码枚举（ARCHITECTURE.md §7.1）。
 *
 * 服务端所有错误响应以 [LanSyncErrorDto] 结构化返回，`e.message` 只进 FileLogger，
 * 绝不回显给客户端（SPEC.md §3.2 / §7）。错误码到 HTTP 状态码的映射见 LanSyncRouting。
 *
 * 注：错误响应体从旧版 text/plain 改为 application/json 不破坏互操作——
 * 客户端从不解析错误体（SPEC.md §3.2），冻结红线仅约束「错误状态码不变」（SPEC.md §9）。
 */
enum class LanSyncErrorCode {
    /** 404：下载时按 packageName(+versionCode) 无匹配应用 */
    APP_NOT_FOUND,

    /** 404：配对请求不存在或已处理（connect/response） */
    REQUEST_NOT_FOUND,

    /** 403：系统/受保护应用，不可提取传输 */
    NOT_EXTRACTABLE,

    /** 500：打包失败或打包产物缺失 */
    PACK_FAILED,

    /** 400：请求体解析失败 / requestId 缺失 */
    INVALID_REQUEST,

    /** 409：重复的配对请求（相同 requestId 已登记） */
    CONFLICT,

    /** 503：服务端配对组件未就绪（旧惰性初始化产物；DI 后新服务端不再发出，保留供旧服务端兼容识别） */
    SERVER_NOT_READY,

    /** 500：未分类内部错误 */
    INTERNAL
}

/**
 * 统一错误响应体（ARCHITECTURE.md §7.1）。
 *
 * `code` 为 [LanSyncErrorCode] 的 name，`message` 为用户可读文案（非异常细节）。
 * 两字段均无默认值，故 `encodeDefaults=false` 下恒完整上线（SPEC.md §1.2.1）。
 */
@Serializable
data class LanSyncErrorDto(
    val code: String,
    val message: String
) {
    companion object {
        fun of(code: LanSyncErrorCode, message: String): LanSyncErrorDto =
            LanSyncErrorDto(code = code.name, message = message)
    }
}
