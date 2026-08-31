package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.Position
import com.example.model.UserPosition
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CleanBorder
import com.example.ui.theme.MapAxis
import com.example.ui.theme.MapBackgroundClean
import com.example.ui.theme.MapGridMajor
import com.example.ui.theme.MapGridMinor
import com.example.ui.theme.MapOtherUserMarker
import com.example.ui.theme.MapTrajectoryLine
import com.example.ui.theme.MapTrajectoryPoint
import com.example.ui.theme.MapUserMarker

/**
 * 2D холст для визуализации перемещений клинера внутри здания в стиле Clean Minimalism.
 */
@Composable
fun IndoorMapCanvas(
    currentPosition: Position,
    headingDegrees: Float,
    trajectory: List<Position>,
    otherUsers: List<UserPosition>,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MapBackgroundClean
) {
    // Масштаб: сколько пикселей на 1 метр
    var scale by remember { mutableFloatStateOf(44f) } // 44 px = 1 метр
    // Смещение холста (панорамирование)
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundColor)
            .border(1.dp, CleanBorder, RoundedCornerShape(24.dp))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(14f, 180f)
                    panOffsetX += pan.x
                    panOffsetY += pan.y
                }
            }
            .testTag("indoor_map_canvas")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasCenterX = size.width / 2f + panOffsetX
            val canvasCenterY = size.height / 2f + panOffsetY

            // 1. Отрисовка координатной сетки в метрах
            drawMeterGrid(
                canvasCenterX = canvasCenterX,
                canvasCenterY = canvasCenterY,
                scale = scale,
                width = size.width,
                height = size.height,
                minorColor = MapGridMinor,
                majorColor = MapGridMajor,
                axisColor = MapAxis
            )

            // Конвертер координат: (x метры вправо, y метры вверх) -> (canvasX, canvasY)
            fun toCanvasOffset(xMeters: Double, yMeters: Double): Offset {
                return Offset(
                    x = (canvasCenterX + xMeters * scale).toFloat(),
                    y = (canvasCenterY - yMeters * scale).toFloat()
                )
            }

            // 2. Отрисовка траектории (линия пройденного пути)
            if (trajectory.size > 1) {
                val path = Path()
                val firstPoint = toCanvasOffset(trajectory.first().x, trajectory.first().y)
                path.moveTo(firstPoint.x, firstPoint.y)

                for (i in 1 until trajectory.size) {
                    val p = toCanvasOffset(trajectory[i].x, trajectory[i].y)
                    path.lineTo(p.x, p.y)
                }

                drawPath(
                    path = path,
                    color = MapTrajectoryLine,
                    style = Stroke(
                        width = 3.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Точки шагов вдоль траектории
                trajectory.forEach { pos ->
                    val pt = toCanvasOffset(pos.x, pos.y)
                    drawCircle(
                        color = MapTrajectoryPoint,
                        radius = 2.5.dp.toPx(),
                        center = pt
                    )
                }
            }

            // 3. Маркер стартовой точки (0,0)
            val originOffset = toCanvasOffset(0.0, 0.0)
            drawCircle(
                color = AccentAmber.copy(alpha = 0.25f),
                radius = 12.dp.toPx(),
                center = originOffset
            )
            drawCircle(
                color = AccentAmber,
                radius = 5.dp.toPx(),
                center = originOffset
            )

            // 4. Отрисовка других клинеров из map_update
            otherUsers.forEach { user ->
                val isSameFloor = user.floor == currentPosition.floor
                val userOffset = toCanvasOffset(user.x, user.y)
                val alpha = if (isSameFloor) 1f else 0.4f

                drawCircle(
                    color = MapOtherUserMarker.copy(alpha = 0.2f * alpha),
                    radius = 14.dp.toPx(),
                    center = userOffset
                )
                drawCircle(
                    color = MapOtherUserMarker.copy(alpha = alpha),
                    radius = 6.dp.toPx(),
                    center = userOffset
                )
            }

            // 5. Текущая позиция клинера с направлением курса
            val currentOffset = toCanvasOffset(currentPosition.x, currentPosition.y)

            // Пульсирующий ореол вокруг клинера
            drawCircle(
                color = MapUserMarker.copy(alpha = 0.18f),
                radius = 22.dp.toPx(),
                center = currentOffset
            )
            drawCircle(
                color = MapUserMarker.copy(alpha = 0.35f),
                radius = 12.dp.toPx(),
                center = currentOffset
            )
            drawCircle(
                color = Color.White,
                radius = 7.dp.toPx(),
                center = currentOffset
            )
            drawCircle(
                color = MapUserMarker,
                radius = 5.dp.toPx(),
                center = currentOffset
            )

            // Стрелка направления курса
            rotate(degrees = headingDegrees, pivot = currentOffset) {
                val arrowPath = Path().apply {
                    moveTo(currentOffset.x, currentOffset.y - 18.dp.toPx())
                    lineTo(currentOffset.x + 7.5.dp.toPx(), currentOffset.y + 2.dp.toPx())
                    lineTo(currentOffset.x, currentOffset.y - 3.dp.toPx())
                    lineTo(currentOffset.x - 7.5.dp.toPx(), currentOffset.y + 2.dp.toPx())
                    close()
                }
                drawPath(
                    path = arrowPath,
                    color = Color.White,
                    style = Fill
                )
                drawPath(
                    path = arrowPath,
                    color = MapUserMarker,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Информационные плашки карты (Этаж, масштаб)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.92f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CleanBorder),
                shadowElevation = 2.dp
            ) {
                Text(
                    text = "Этаж ${currentPosition.floor} • 1 м = ${scale.toInt()} px",
                    color = Color(0xFF1A1C1E),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            if (otherUsers.isNotEmpty()) {
                val onSameFloor = otherUsers.count { it.floor == currentPosition.floor }
                Surface(
                    color = Color(0xFFFDE8E8),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5))
                ) {
                    Text(
                        text = "Клинеров на карте: ${otherUsers.size} ($onSameFloor на этаже)",
                        color = Color(0xFF991B1B),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Кнопки управления зумом и центрированием в чистом стиле
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledIconButton(
                onClick = { scale = (scale * 1.3f).coerceAtMost(180f) },
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1A1C1E)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = "Увеличить",
                    modifier = Modifier.size(18.dp)
                )
            }

            FilledIconButton(
                onClick = { scale = (scale / 1.3f).coerceAtLeast(14f) },
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1A1C1E)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOut,
                    contentDescription = "Уменьшить",
                    modifier = Modifier.size(18.dp)
                )
            }

            FilledIconButton(
                onClick = {
                    panOffsetX = -currentPosition.x.toFloat() * scale
                    panOffsetY = currentPosition.y.toFloat() * scale
                },
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Центрировать на мне",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Отрисовка координатной сетки в метрах на холсте.
 */
private fun DrawScope.drawMeterGrid(
    canvasCenterX: Float,
    canvasCenterY: Float,
    scale: Float,
    width: Float,
    height: Float,
    minorColor: Color,
    majorColor: Color,
    axisColor: Color
) {
    val step1m = scale
    val step5m = scale * 5f

    // Линии по оси X (вертикальные)
    var x = canvasCenterX % step1m
    while (x < width) {
        val meterIndex = Math.round((x - canvasCenterX) / scale)
        val isMajor = meterIndex % 5 == 0
        val isAxis = meterIndex == 0

        val color = when {
            isAxis -> axisColor
            isMajor -> majorColor
            else -> minorColor
        }
        val strokeW = if (isAxis) 2f else if (isMajor) 1.2f else 0.6f

        drawLine(
            color = color,
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = strokeW
        )
        x += step1m
    }

    // Линии по оси Y (горизонтальные)
    var y = canvasCenterY % step1m
    while (y < height) {
        val meterIndex = Math.round((canvasCenterY - y) / scale)
        val isMajor = meterIndex % 5 == 0
        val isAxis = meterIndex == 0

        val color = when {
            isAxis -> axisColor
            isMajor -> majorColor
            else -> minorColor
        }
        val strokeW = if (isAxis) 2f else if (isMajor) 1.2f else 0.6f

        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(width, y),
            strokeWidth = strokeW
        )
        y += step1m
    }
}

