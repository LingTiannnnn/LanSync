package com.lansync.app.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 动效令牌：时长、自动消失间隔、手势位移。
 *
 * 组件内**禁止**再写 `tween(300)` / `delay(10_000L)` 这类字面量，一律经
 * [LanSyncTheme.motion] 取值；系统「移除动画」（animator duration scale = 0）时
 * [standard] / [exit] / [refreshSpin] 自动降为 0，位移与淡入淡出退化为瞬时。
 *
 * 协议超时与重试间隔不在这里，那些走 `data/AppConfig`（CONVENTIONS §1）。
 */
@Immutable
data class LanSyncMotion(
    val animationsEnabled: Boolean = true,
    val standardDurationMs: Int = 300,
    val exitDurationMs: Int = 200,
    val refreshSpinMs: Int = 700,
    val feedbackAutoDismissMs: Long = 10_000L,
    val countdownTickMs: Long = 1_000L,
    val swipeDismissThreshold: Dp = 100.dp,
    val swipeLimit: Dp = 500.dp,
    val swipeExit: Dp = 1_000.dp,
) {
    /** 动画关闭时三个时长读数退化；位移阈值保持不变，手势仍然能关横幅。 */
    val standard: Int get() = if (animationsEnabled) standardDurationMs else 0
    val exit: Int get() = if (animationsEnabled) exitDurationMs else 0
    val refreshSpin: Int get() = if (animationsEnabled) refreshSpinMs else 0

    fun disabled(): LanSyncMotion = copy(animationsEnabled = false)

    /** 组件里不许再写 `tween(300)`；时长一律从这里出。 */
    fun standardTween(): TweenSpec<Float> = tween(standard)

    fun exitTween(): TweenSpec<Float> = tween(exit)
}

val DefaultMotion = LanSyncMotion()

/**
 * 非组合上下文（ViewModel / Service）需要的 UI 反馈时长。
 *
 * 组合内一律走 [LanSyncTheme.motion]；这里只放组合外拿不到 CompositionLocal 的少数几枚，
 * 目的是让「时长不写在构件里」这条规则对整个 ui 层成立（`architecture/DesignSystemTest`）。
 */
object LanSyncTiming {
    /** 操作类短提示（启动/停止/刷新）的驻留时长。 */
    const val OPERATION_FEEDBACK_MS: Long = 600L
}

val LocalMotion = staticCompositionLocalOf { DefaultMotion }

/** 读系统 animator duration scale；读不到按「开启动画」处理。 */
@Composable
@ReadOnlyComposable
fun rememberMotionEnabled(): Boolean {
    val context: Context = LocalContext.current
    val scale: Float = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale > 0f
}
