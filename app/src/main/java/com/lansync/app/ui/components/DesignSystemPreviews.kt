package com.lansync.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncMonoText
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 设计系统目视基线（light / dark × 共享原语）。
 *
 * Compose UI 不进 JVM 单测，`assembleDebug` + `DesignSystemTest` 只能证明写法，不能证明看起来对；
 * 这里给升 BOM / 改令牌时一条可比的目视基线。预览同样只用令牌与 `strings.xml`，
 * 所以文本与尺寸审计门禁对预览也不开豁免。
 */

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    LanSyncTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LanSyncTheme.spacing.space16),
                verticalArrangement = Arrangement.spacedBy(LanSyncTheme.spacing.space12),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PreviewRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(LanSyncTheme.spacing.space6)) { content() }
}

@Preview(name = "EmptyState")
@Preview(name = "EmptyState Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EmptyStatePreview() {
    PreviewFrame {
        EmptyState(
            icon = Icons.Default.Inbox,
            title = stringResource(R.string.remote_empty_user),
            body = stringResource(R.string.remote_empty_hint),
            actionLabel = stringResource(R.string.action_refresh),
            onAction = {},
        )
    }
}

@Preview(name = "Badges")
@Preview(name = "Badges Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BadgePreview() {
    PreviewFrame {
        PreviewRow {
            LanSyncBadge(LanSyncBadgeKind.Online)
            LanSyncBadge(LanSyncBadgeKind.Updatable)
            LanSyncBadge(LanSyncBadgeKind.Split)
            LanSyncBadge(LanSyncBadgeKind.NotExtractable)
        }
    }
}

@Preview(name = "App Row")
@Preview(name = "App Row Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AppRowPreview() {
    PreviewFrame {
        LanSyncAppRow(
            title = stringResource(R.string.app_name),
            packageName = "com.example.app",
            selected = true,
            checked = true,
            onCheckedChange = {},
            accent = true,
            icon = {},
            badges = { LanSyncBadge(LanSyncBadgeKind.Updatable) },
            supporting = { RowSupportingText(text = stringResource(R.string.status_running)) },
            trailing = {
                RowActionButton(
                    onClick = {},
                    label = stringResource(R.string.action_install),
                    icon = null,
                )
            },
        )
    }
}

@Preview(name = "Batch Bar")
@Preview(name = "Batch Bar Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BatchBarPreview() {
    PreviewFrame {
        BatchBar(
            label = stringResource(R.string.selection_count_apps, 3),
            actionLabel = stringResource(R.string.action_batch_update),
            onAction = {},
        )
    }
}

@Preview(name = "Feedback Banners")
@Preview(name = "Feedback Banners Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun FeedbackBannerPreview() {
    PreviewFrame {
        FeedbackBanner(
            text = stringResource(R.string.install_installing, "com.example.app"),
            tone = FeedbackTone.Working,
            busy = true,
        )
        FeedbackBanner(
            text = stringResource(R.string.install_failed, "com.example.app"),
            tone = FeedbackTone.Failure,
            dismissible = true,
            onDismiss = {},
        )
        FeedbackBanner(
            text = stringResource(R.string.op_refreshing),
            tone = FeedbackTone.Info,
        )
        Text(
            text = stringResource(R.string.mesh_hint_stats, 2, 3),
            style = LanSyncMonoText.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 大字号（系统「显示大小/字体放大」到 1.4x）目视基线。
 * 行组件与反馈条是最容易在放大字号下溢出或被截断的两处，升 BOM 前先钉住外观。
 */
@Preview(name = "App Row 1.4x font", fontScale = 1.4f)
@Composable
private fun AppRowLargeFontPreview() {
    AppRowPreview()
}

@Preview(name = "Feedback Banner 1.4x font", fontScale = 1.4f)
@Composable
private fun FeedbackBannerLargeFontPreview() {
    FeedbackBannerPreview()
}

/** 预览用的技术样例值（设备名 / IP / 端口），不是需要翻译的界面文案。 */
private const val SAMPLE_DEVICE_NAME = "Pixel-6A"
private const val SAMPLE_IP = "192.168.1.42"
private const val SAMPLE_PORT = 8080

/**
 * 身份条：本机名 + 端口 + 三个计数 chip 的同一行布局，是最容易在暗色与大字号下错位的一行。
 */
@Composable
private fun IdentityStripContent() {
    IdentityStrip(
        deviceName = SAMPLE_DEVICE_NAME,
        isRunning = true,
        serverPort = SAMPLE_PORT,
        isStarting = false,
        isStopping = false,
        onToggleRunning = {},
        updatableCount = 2,
        connectedCount = 1,
        localAppCount = 37,
    )
}

@Preview(name = "Identity Strip")
@Preview(name = "Identity Strip Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun IdentityStripPreview() {
    PreviewFrame { IdentityStripContent() }
}

@Preview(name = "Identity Strip 1.4x font", fontScale = 1.4f)
@Composable
private fun IdentityStripLargeFontPreview() {
    PreviewFrame { IdentityStripContent() }
}

/**
 * 加载词汇基线：`LoadingVisuals.kt` 是裸指示器唯一的容身处，
 * 顶栏小 spinner 与遮罩大 spinner / 两条进度条必须同框比一次。
 */
@Preview(name = "Loading visuals")
@Preview(name = "Loading visuals Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LoadingVisualsPreview() {
    val sp = LanSyncTheme.spacing
    PreviewFrame {
        PreviewRow {
            LanSyncSpinner()
            LanSyncSpinner(size = sp.topBarIndicator, strokeWidth = sp.strokeThin)
            LanSyncSpinner(size = sp.iconEmpty, color = MaterialTheme.colorScheme.secondary)
        }
        LanSyncProgressBar(progress = 0.4f, modifier = Modifier.fillMaxWidth())
        LanSyncProgressBar(modifier = Modifier.fillMaxWidth())
    }
}

/** chip / 段落头 / 进行中按钮：选中态与容器配对色在同框里才可比。 */
@Preview(name = "Chips, header, in-progress button")
@Preview(
    name = "Chips, header, in-progress button Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChipAndButtonPreview() {
    val scheme = MaterialTheme.colorScheme
    PreviewFrame {
        PreviewRow {
            StatusChip(
                text = stringResource(R.string.device_online_badge),
                containerColor = scheme.secondaryContainer,
                contentColor = scheme.onSecondaryContainer,
            )
            LanSyncBadge(LanSyncBadgeKind.Updatable)
            InProgressButton(label = stringResource(R.string.action_installing))
        }
        LanSyncFilterChip(
            selected = true,
            onClick = {},
            label = stringResource(R.string.category_user, 3),
        )
        SectionHeader(
            title = stringResource(R.string.tab_remote),
            icon = Icons.Default.Inbox,
            count = 37,
        )
        Text(
            text = "$SAMPLE_IP:$SAMPLE_PORT",
            style = LanSyncMonoText.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
}
