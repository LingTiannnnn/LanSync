package com.lansync.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 短反馈横幅唯一实现（安装状态、操作提示、失败原因共用）。
 *
 * 此前有两套手写实现：`OperationMessageSnackbar`（inverseSurface + `shapes.medium`）与
 * `InstallStatusSnackbar`（三种 status 各写一个 Surface + 8dp 圆角），同一个"短反馈"概念
 * 两种圆角两种色角色，且阴影借用的是间距令牌。
 *
 * 时长与位移阈值全部取自 [LanSyncTheme.motion]：阈值按 dp 定义、按 density 换算 px，
 * 不同像素密度设备的"划走"手感一致。文本变化即视为新一条反馈（重置位移与计时）。
 * `liveRegion` 让 TalkBack 能播报这条瞬时反馈。
 */
enum class FeedbackTone { Working, Success, Failure, Info }

@Composable
fun FeedbackBanner(
    text: String,
    tone: FeedbackTone,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    autoDismissMs: Long? = null,
    dismissible: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    val sp = LanSyncTheme.spacing
    val motion = LanSyncTheme.motion
    val scheme = MaterialTheme.colorScheme
    val visual: Pair<Color, ImageVector?> = when (tone) {
        FeedbackTone.Working -> scheme.primaryContainer to null
        FeedbackTone.Success -> scheme.tertiaryContainer to Icons.Default.CheckCircle
        FeedbackTone.Failure -> scheme.errorContainer to Icons.Default.Error
        FeedbackTone.Info -> scheme.inverseSurface to Icons.Default.Info
    }
    val leadIcon: ImageVector? = visual.second
    val contentColor: Color = when (tone) {
        FeedbackTone.Working -> scheme.onPrimaryContainer
        FeedbackTone.Success -> scheme.onTertiaryContainer
        FeedbackTone.Failure -> scheme.onErrorContainer
        FeedbackTone.Info -> scheme.inverseOnSurface
    }

    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { motion.swipeDismissThreshold.toPx() }
    val limitPx = with(density) { motion.swipeLimit.toPx() }
    val exitPx = with(density) { motion.swipeExit.toPx() }
    val offsetX = remember(text) { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val slideOutAndDismiss: () -> Unit = {
        scope.launch {
            offsetX.animateTo(exitPx, motion.standardTween())
            onDismiss?.invoke()
        }
    }

    LaunchedEffect(text) {
        if (autoDismissMs != null) {
            delay(autoDismissMs)
            offsetX.animateTo(exitPx, motion.standardTween())
            onDismiss?.invoke()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sp.space16, vertical = sp.space8)
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .then(
                if (dismissible) {
                    Modifier.pointerInput(text) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (abs(offsetX.value) > dismissThresholdPx) {
                                    scope.launch {
                                        offsetX.animateTo(
                                            if (offsetX.value > 0) exitPx else -exitPx,
                                            motion.standardTween(),
                                        )
                                        onDismiss?.invoke()
                                    }
                                } else {
                                    scope.launch { offsetX.animateTo(0f, motion.standardTween()) }
                                }
                            },
                        ) { _, dragAmount ->
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount).coerceIn(-limitPx, limitPx))
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = text
            },
        shape = MaterialTheme.shapes.medium,
        color = visual.first,
        contentColor = contentColor,
        shadowElevation = LanSyncTheme.elevation.banner,
    ) {
        Row(
            modifier = Modifier.padding(sp.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(sp.space12),
        ) {
            when {
                busy -> LanSyncSpinner(
                    size = sp.iconXl,
                    strokeWidth = sp.strokeThin,
                    color = contentColor,
                )
                leadIcon != null -> Icon(
                    imageVector = leadIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(sp.iconLg),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    modifier = Modifier.heightIn(min = sp.minTouchTarget),
                ) {
                    Text(actionLabel)
                }
            }
            if (dismissible) {
                // 关闭是可点属性，不是「没有动作时的兜底文案」：
                // 动作与关闭可以共存，否则带重试按钮的失败横幅就没有显式出口，只剩一次无提示的滑动。
                IconButton(
                    onClick = slideOutAndDismiss,
                    modifier = Modifier.heightIn(min = sp.minTouchTarget),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = contentColor,
                        modifier = Modifier.size(sp.iconMd),
                    )
                }
            }
        }
    }
}
