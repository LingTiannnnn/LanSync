package com.lansync.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncDim
import com.lansync.app.ui.theme.LanSyncTheme

/**
 * 首次扫描遮罩。
 *
 * 遮罩浓度 [LanSyncDim.overlay]（比 M3 默认 scrim 0.32 深，首扫期间确实不可操作，
 * 属登记过的例外，见 `docs/CONVENTIONS.md` §3）。
 *
 * 但遮罩本身不得是**死路**：扫描链路在真机上可能长时间无返回，所以始终给一个 [onSkip] 出口，
 * 用户可以回到主界面继续操作，扫描在后台继续、完成后数据自动就位。
 * 这里不提供「重试」按钮 —— 重试会并发触发第二次 `scanAndRefresh`，与进行中的那次争写缓存文件。
 */
@Composable
fun InitialScanOverlay(onSkip: () -> Unit) {
    val sp = LanSyncTheme.spacing
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = LanSyncDim.overlay,
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(sp.space32),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = LanSyncTheme.elevation.overlay,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(sp.space8),
                    ) {
                        OverlayProgressCard(
                            title = stringResource(R.string.initial_scan_title),
                            body = stringResource(R.string.initial_scan_body),
                            footer = stringResource(R.string.initial_scan_footer),
                        )
                        TextButton(
                            onClick = onSkip,
                            modifier = Modifier
                                .padding(bottom = sp.space8)
                                .heightIn(min = sp.minTouchTarget),
                        ) {
                            Text(stringResource(R.string.initial_scan_skip))
                        }
                    }
                }
            }
        }
    }
}
