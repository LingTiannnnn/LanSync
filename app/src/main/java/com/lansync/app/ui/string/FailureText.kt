package com.lansync.app.ui.string

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.lansync.app.R
import com.lansync.app.data.model.ConnectionFailure
import com.lansync.app.data.model.InstallFailure
import com.lansync.app.ui.components.SaveFailure

/**
 * typed failure → 用户文案的唯一映射（ARCH §7 错误协议 / CONVENTIONS §3 文案唯一来源）。
 *
 * `data` 层只产出机器可读的失败类别与计数参数（[ConnectionFailure]、[InstallFailure]），
 * 不产出自然语言；这里把类别翻成 `strings.xml` 资源。诊断原文一律只进 `FileLogger`，
 * 不进界面 —— 否则 UI 会显示英文堆栈式短句，且无法本地化。
 */

@StringRes
fun connectionFailureTextId(kind: ConnectionFailure.Kind): Int = when (kind) {
    ConnectionFailure.Kind.SEND_NO_RESPONSE -> R.string.reason_no_response
    ConnectionFailure.Kind.REJECTED_BY_PEER -> R.string.reason_rejected
    ConnectionFailure.Kind.WAIT_TIMEOUT -> R.string.reason_timeout_seconds
    ConnectionFailure.Kind.LINK_UNSTABLE -> R.string.reason_link_unstable
    ConnectionFailure.Kind.RECONNECTING -> R.string.reason_reconnecting
    ConnectionFailure.Kind.DEVICE_OFFLINE -> R.string.reason_device_offline
    ConnectionFailure.Kind.UNEXPECTED -> R.string.reason_unexpected
}

@StringRes
fun installFailureTextId(failure: InstallFailure): Int = when (failure) {
    InstallFailure.FILE_MISSING -> R.string.reason_file_missing
    InstallFailure.DOWNLOAD_FAILED -> R.string.reason_download_failed
    InstallFailure.INSTALLER_UNAVAILABLE -> R.string.reason_installer_unavailable
    InstallFailure.INSTALLER_EXCEPTION -> R.string.reason_installer_exception
}

/** 下载/安装失败的用户可读文本。 */
fun installFailureText(context: Context, failure: InstallFailure): String =
    context.getString(installFailureTextId(failure))

/** 组合期便捷版：走 `stringResource`，语言/配置切换时自动跟随。 */
@Composable
@ReadOnlyComposable
fun connectionFailureText(failure: ConnectionFailure): String {
    val id = connectionFailureTextId(failure.kind)
    return when (failure.kind) {
        ConnectionFailure.Kind.WAIT_TIMEOUT -> stringResource(id, failure.waitSeconds)
        ConnectionFailure.Kind.LINK_UNSTABLE, ConnectionFailure.Kind.RECONNECTING ->
            stringResource(id, failure.retry, failure.retryTotal)
        else -> stringResource(id)
    }
}

/** 组合期便捷版。 */
@Composable
@ReadOnlyComposable
fun installFailureText(failure: InstallFailure): String = stringResource(installFailureTextId(failure))

@StringRes
fun saveFailureTextId(reason: SaveFailure): Int = when (reason) {
    SaveFailure.SOURCE_MISSING -> R.string.save_err_source_missing
    SaveFailure.DIR_INACCESSIBLE -> R.string.save_err_dir_inaccessible
    SaveFailure.CREATE_FAILED -> R.string.save_err_create_failed
    SaveFailure.WRITE_FAILED -> R.string.save_err_write_failed
    SaveFailure.PERMISSION_DENIED -> R.string.save_err_permission
    SaveFailure.NO_SPACE -> R.string.save_err_no_space
    SaveFailure.UNKNOWN -> R.string.save_err_generic
}

/** SAF 保存失败的用户可读文本（异常原文只进 FileLogger，永不上屏）。 */
@Composable
@ReadOnlyComposable
fun saveFailureText(reason: SaveFailure): String = stringResource(saveFailureTextId(reason))
