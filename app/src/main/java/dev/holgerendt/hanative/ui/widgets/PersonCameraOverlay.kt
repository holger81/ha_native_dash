package dev.holgerendt.hanative.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.theme.ScreenBackground
import kotlin.math.ceil

internal fun personCameraGridLayout(count: Int): Pair<Int, Int> {
    if (count <= 1) return 1 to 1
    if (count == 2) return 2 to 1
    if (count <= 4) return 2 to 2
    val cols = 3
    return cols to ceil(count / cols.toDouble()).toInt()
}

internal fun personCameraStripHeight(cameraCount: Int): Dp {
    val (_, rows) = personCameraGridLayout(cameraCount)
    val rowHeight = 150.dp
    val spacing = 6.dp
    val padding = 12.dp
    return rowHeight * rows + spacing * (rows - 1).coerceAtLeast(0) + padding
}

@Composable
fun PersonCameraOverlay(
    cameras: List<WidgetNode>,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    if (cameras.isEmpty()) return
    val (cols, rows) = personCameraGridLayout(cameras.size)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ScreenBackground)
            .padding(6.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            var index = 0
            repeat(rows) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(cols) {
                        if (index < cameras.size) {
                            CameraCard(
                                widget = cameras[index],
                                viewModel = viewModel,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                fill = true,
                            )
                            index++
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
