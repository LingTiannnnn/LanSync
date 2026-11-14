package com.lansync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.lansync.app.R
import com.lansync.app.ui.theme.LanSyncTheme

/** 索引轨道固定槽数：a–z 26 格 + `#`，与 [com.lansync.app.data.appNameIndexSections] 槽位一一对应。 */
private const val RAIL_SLOT_COUNT = 27
private const val RAIL_HASH_SLOT = 26

/** 轨道各槽的显示字母（A-Z+#），气泡取字也用它。 */
internal val indexRailLetters: List<Char> =
    List(RAIL_SLOT_COUNT) { pos -> if (pos == RAIL_HASH_SLOT) '#' else 'A' + pos }

/**
 * 通讯录式字母索引轨道（远程应用页两个列表共用）。整体是一条手势面（滚动条语义，非逐字母
 * 按钮）：点按跳段、拖动连续定位，槽位变化轻震一下；缺段字母置灰、滑过不跳转。
 * 拖动中的当前段反馈由页面挂 [IndexScrubBubble]，轨道本身只做高亮。
 *
 * 各字母槽 `weight(1f)` 均分轨道高度，与手势的「y ÷ 高度 × 27」映射天然一致，永不溢出。
 * 当前段字母用 primaryContainer 小圆片标记；圆片只出现在字周围，槽位定宽定高不抖动。
 */
@Composable
fun AlphabetIndexBar(
    sections: List<Int>,
    currentPos: Int,
    onSeek: (Int) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val railDescription = stringResource(R.string.cd_index_rail)
    var lastSeekPos by remember { mutableIntStateOf(-1) }

    fun seek(pos: Int) {
        if (sections.getOrNull(pos) == null || sections[pos] == -1) return
        if (pos == lastSeekPos) return
        lastSeekPos = pos
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onSeek(pos)
    }

    fun posAt(y: Float, height: Float): Int =
        (y / height * RAIL_SLOT_COUNT).toInt().coerceIn(0, RAIL_SLOT_COUNT - 1)

    Column(
        modifier = modifier
            .width(sp.indexRailWidth)
            .fillMaxHeight()
            .clearAndSetSemantics { contentDescription = railDescription }
            .pointerInput(sections) {
                detectTapGestures { offset ->
                    seek(posAt(offset.y, size.height.toFloat()))
                }
            }
            .pointerInput(sections) {
                detectVerticalDragGestures(
                    onDragStart = { offset -> seek(posAt(offset.y, size.height.toFloat())) },
                    onVerticalDrag = { change, _ ->
                        seek(posAt(change.position.y, size.height.toFloat()))
                        change.consume()
                    },
                    onDragEnd = {
                        lastSeekPos = -1
                        onScrubEnd()
                    },
                    onDragCancel = {
                        lastSeekPos = -1
                        onScrubEnd()
                    },
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        indexRailLetters.forEachIndexed { pos, letter ->
            val present = sections.getOrNull(pos) != -1
            val isCurrent = pos == currentPos && present
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = letter.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        isCurrent -> colors.onPrimaryContainer
                        present -> colors.primary
                        else -> colors.onSurfaceVariant
                    },
                    modifier = if (isCurrent) {
                        Modifier
                            .background(colors.primaryContainer, MaterialTheme.shapes.small)
                            .padding(sp.space2)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

/**
 * 拖动定位时浮在列表中央的当前段字母气泡：容器配对底色、大号字母，
 * 无动效，松手即由页面撤下。
 */
@Composable
fun IndexScrubBubble(
    label: Char,
    modifier: Modifier = Modifier,
) {
    val sp = LanSyncTheme.spacing
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(sp.minTouchTarget)
            .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
    ) {
        Text(
            text = label.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}
