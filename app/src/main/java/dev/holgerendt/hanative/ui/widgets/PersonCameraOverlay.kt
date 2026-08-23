package dev.holgerendt.hanative.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.holgerendt.hanative.model.WidgetNode
import dev.holgerendt.hanative.ui.HaViewModel
import dev.holgerendt.hanative.ui.theme.ActiveYellow
import dev.holgerendt.hanative.ui.theme.CardLight
import dev.holgerendt.hanative.ui.theme.ScreenBackground
import dev.holgerendt.hanative.ui.theme.TextDark
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
fun BackyardActivityPanel(
    cameras: List<WidgetNode>,
    timeline: WidgetNode,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
) {
    val activityTitle = timeline.name.takeUnless { it.isNullOrBlank() } ?: "This happened around the house"
    var selectedTab by remember { mutableStateOf("cameras") }
    LaunchedEffect(cameras.size) {
        if (cameras.isNotEmpty()) selectedTab = "cameras"
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BackyardTabRow(
            selected = selectedTab,
            cameraLabel = "Live cameras",
            activityLabel = activityTitle,
            onSelect = { selectedTab = it },
        )
        when (selectedTab) {
            "cameras" -> PersonCameraOverlay(
                cameras = cameras,
                viewModel = viewModel,
                fitContent = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = personCameraStripHeight(cameras.size)),
            )
            else -> VisionTimeline(
                widget = timeline,
                viewModel = viewModel,
                showTitle = false,
            )
        }
    }
}

@Composable
private fun BackyardTabRow(
    selected: String,
    cameraLabel: String,
    activityLabel: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(CardLight)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("cameras" to cameraLabel, "activity" to activityLabel).forEach { (id, label) ->
            val active = selected == id
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (active) ActiveYellow else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(vertical = 10.dp, horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (active) Color.Black else TextDark,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
fun PersonCameraOverlay(
    cameras: List<WidgetNode>,
    viewModel: HaViewModel,
    modifier: Modifier = Modifier,
    fitContent: Boolean = false,
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
                                fill = !fitContent,
                                fitContent = fitContent,
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
