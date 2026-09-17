/*
 * Copyright (C) 2024 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.geq.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.aospa.dolby.geq.data.BandGain
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private const val GAIN_MAX = 150f

@Composable
fun EqualizerGraph(
    bandGains: List<BandGain>,
    onGainChangeFinished: (index: Int, gain: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bandCount = bandGains.size
    if (bandCount == 0) return

    val animatables = remember(bandCount) { List(bandCount) { Animatable(0f) } }
    val scope = rememberCoroutineScope()
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val targets = bandGains.map { (it.gain / GAIN_MAX).coerceIn(-1f, 1f) }

    // Morph the curve whenever the gains change externally (e.g. preset switch).
    LaunchedEffect(bandGains) {
        coroutineScope {
            targets.forEachIndexed { index, target ->
                launch { animatables[index].animateTo(target, tween(durationMillis = 300)) }
            }
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(240.dp)) {
            Canvas(
                modifier =
                    Modifier.fillMaxSize()
                        .pointerInput(bandCount) {
                            val cellWidth = size.width.toFloat() / bandCount
                            val padY = 20.dp.toPx()
                            val centerY = size.height / 2f
                            val amplitude = centerY - padY

                            fun indexOf(x: Float) =
                                (x / cellWidth).toInt().coerceIn(0, bandCount - 1)

                            fun gainOf(y: Float) =
                                ((centerY - y) / amplitude).coerceIn(-1f, 1f)

                            detectDragGestures(
                                onDragStart = { offset ->
                                    val index = indexOf(offset.x)
                                    dragIndex = index
                                    dragValue = gainOf(offset.y)
                                    scope.launch { animatables[index].snapTo(dragValue) }
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val index = dragIndex
                                    if (index >= 0) {
                                        dragValue = gainOf(change.position.y)
                                        scope.launch { animatables[index].snapTo(dragValue) }
                                    }
                                },
                                onDragEnd = {
                                    val index = dragIndex
                                    if (index >= 0) {
                                        onGainChangeFinished(
                                            index,
                                            (dragValue * GAIN_MAX).roundToInt(),
                                        )
                                    }
                                    dragIndex = -1
                                },
                                onDragCancel = { dragIndex = -1 },
                            )
                        }
            ) {
                val gains = animatables.map { it.value }
                val width = size.width
                val height = size.height
                val cellWidth = width / bandCount
                val padY = 20.dp.toPx()
                val centerY = height / 2f
                val amplitude = centerY - padY

                fun xOf(index: Int) = cellWidth * (index + 0.5f)
                fun yOf(gain: Float) = centerY - gain * amplitude

                // Horizontal grid lines every 5 dB.
                val gridSteps = listOf(-1f, -2f / 3f, -1f / 3f, 0f, 1f / 3f, 2f / 3f, 1f)
                gridSteps.forEach { gain ->
                    val isCenter = gain == 0f
                    drawLine(
                        color = grid.copy(alpha = if (isCenter) 0.9f else 0.35f),
                        start = Offset(0f, yOf(gain)),
                        end = Offset(width, yOf(gain)),
                        strokeWidth = if (isCenter) 1.5.dp.toPx() else 1.dp.toPx(),
                    )
                }

                // Vertical grid lines at every band.
                for (index in 0 until bandCount) {
                    drawLine(
                        color = grid.copy(alpha = 0.3f),
                        start = Offset(xOf(index), 0f),
                        end = Offset(xOf(index), height),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                val points = gains.mapIndexed { index, gain -> Offset(xOf(index), yOf(gain)) }
                val curve = smoothPath(points)

                val fill =
                    Path().apply {
                        addPath(curve)
                        lineTo(points.last().x, centerY)
                        lineTo(points.first().x, centerY)
                        close()
                    }
                drawPath(
                    path = fill,
                    brush =
                        Brush.verticalGradient(
                            colors =
                                listOf(primary.copy(alpha = 0.35f), primary.copy(alpha = 0.02f)),
                            startY = 0f,
                            endY = height,
                        ),
                )

                drawPath(
                    path = curve,
                    color = primary,
                    style =
                        Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                )

                points.forEachIndexed { index, point ->
                    val active = index == dragIndex
                    val radius = if (active) 8.dp.toPx() else 6.dp.toPx()
                    drawCircle(color = surface, radius = radius, center = point)
                    drawCircle(
                        color = primary,
                        radius = radius,
                        center = point,
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            }

            if (dragIndex in bandGains.indices) {
                Text(
                    text =
                        "${bandGains[dragIndex].band.formatFrequency()} Hz   " +
                            "%+.1f dB".format(animatables[dragIndex].value * 15f),
                    modifier =
                        Modifier.align(Alignment.TopCenter)
                            .padding(top = 4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(50),
                            )
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = secondary,
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            bandGains.forEach { bandGain ->
                Text(
                    text = bandGain.band.formatFrequency(),
                    modifier = Modifier.weight(1f),
                    color = secondary,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun Int.formatFrequency(): String =
    if (this >= 1000) {
        val thousands = this / 1000f
        if (thousands % 1f == 0f) "${this / 1000}k" else "%.1fk".format(thousands)
    } else {
        toString()
    }

private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (index in 0 until points.size - 1) {
        val previous = if (index > 0) points[index - 1] else points[index]
        val current = points[index]
        val next = points[index + 1]
        val afterNext = if (index + 2 < points.size) points[index + 2] else points[index + 1]
        val control1 = Offset(current.x + (next.x - previous.x) / 6f, current.y + (next.y - previous.y) / 6f)
        val control2 = Offset(next.x - (afterNext.x - current.x) / 6f, next.y - (afterNext.y - current.y) / 6f)
        path.cubicTo(control1.x, control1.y, control2.x, control2.y, next.x, next.y)
    }
    return path
}
