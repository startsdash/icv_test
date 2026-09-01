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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.CleaningMode
import com.example.model.CoverageSegment
import com.example.model.FacilityZone
import com.example.model.PerimeterMappingState
import com.example.model.Position
import com.example.model.UserPosition
import com.example.ui.theme.AccentAmber
import com.example.ui.theme.CleanBorder
import com.example.ui.theme.MapAxis
import com.example.ui.theme.MapBackgroundClean
import com.example.ui.theme.MapGridMajor
import com.example.ui.theme.MapGridMinor
import com.example.ui.theme.MapOtherUserMarker
import com.example.ui.theme.MapTrajectoryPoint
import com.example.ui.theme.MapUserMarker

/**
 * 2D интерактивный холст SLAM и карты покрытия (Coverage Map) в стиле роботов-пылесосов.
 * Отображает:
 * 1. Координатную сетку в метрах.
 * 2. Размеченные объекты (подъезды, дворы, площадки) полигонами с площадью м².
 * 3. Активную разметку периметра объекта с контрольными точками.
 * 4. Полосы покрытия уборки заданной ширины (швабра 40-60 см) с цветовым кодированием режима.
 * 5. Маркер клинера с кругом ширины захвата инвентаря и азимутом движения.
 */
@Composable
fun IndoorMapCanvas(
    currentPosition: Position,
    headingDegrees: Float,
    trajectory: List<Position>,
    coverageSegments: List<CoverageSegment> = emptyList(),
    savedZones: List<FacilityZone> = emptyList(),
    currentZone: FacilityZone? = null,
    perimeterState: PerimeterMappingState = PerimeterMappingState(),
    cleaningWidthMeters: Double = 0.5,
    cleaningMode: CleaningMode = CleaningMode.WET_CLEANING,
    otherUsers: List<UserPosition> = emptyList(),
    showOnlyActiveZone: Boolean = true,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MapBackgroundClean
) {
    // Масштаб: сколько пикселей на 1 метр (по умолчанию 38 px = 1 м)
    var scale by remember { mutableFloatStateOf(38f) }
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
                    scale = (scale * zoom).coerceIn(12f, 180f)
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

            // =================================================================
            // 2. Отрисовка размеченных объектов / полигонов (подъезды, дворы)
            // =================================================================
            val zonesToDraw = if (showOnlyActiveZone && currentZone != null) {
                listOf(currentZone)
            } else {
                savedZones.filter { it.floor == currentPosition.floor }
            }

            zonesToDraw.forEach { zone ->
                if (zone.floor == currentPosition.floor && zone.polygonPoints.size >= 3) {
                    val zonePath = Path()
                    val firstPt = toCanvasOffset(zone.polygonPoints.first().x, zone.polygonPoints.first().y)
                    zonePath.moveTo(firstPt.x, firstPt.y)

                    var sumX = zone.polygonPoints.first().x
                    var sumY = zone.polygonPoints.first().y

                    for (i in 1 until zone.polygonPoints.size) {
                        val pt = toCanvasOffset(zone.polygonPoints[i].x, zone.polygonPoints[i].y)
                        zonePath.lineTo(pt.x, pt.y)
                        sumX += zone.polygonPoints[i].x
                        sumY += zone.polygonPoints[i].y
                    }
                    zonePath.close()

                    val isCurrent = currentZone?.id == zone.id
                    val baseColor = Color(zone.colorHex)
                    val fillColor = if (isCurrent) baseColor.copy(alpha = 0.18f) else baseColor.copy(alpha = 0.08f)
                    val strokeColor = if (isCurrent) baseColor.copy(alpha = 0.95f) else baseColor.copy(alpha = 0.50f)

                    // Заливка полигона объекта
                    drawPath(
                        path = zonePath,
                        color = fillColor,
                        style = Fill
                    )

                    // Четкий контур полигона объекта
                    drawPath(
                        path = zonePath,
                        color = strokeColor,
                        style = Stroke(
                            width = if (isCurrent) 3.dp.toPx() else 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )

                    // Отрисовка маркеров каждого угла полигона (чтобы углы были четко видны)
                    zone.polygonPoints.forEach { pt ->
                        val cornerOffset = toCanvasOffset(pt.x, pt.y)
                        drawCircle(
                            color = Color.White,
                            radius = 4.5.dp.toPx(),
                            center = cornerOffset
                        )
                        drawCircle(
                            color = strokeColor,
                            radius = 3.dp.toPx(),
                            center = cornerOffset
                        )
                    }

                    // Центроид для отрисовки названия и площади объекта
                    val centerX = sumX / zone.polygonPoints.size
                    val centerY = sumY / zone.polygonPoints.size
                    val centerOffset = toCanvasOffset(centerX, centerY)

                    drawContext.canvas.nativeCanvas.apply {
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.DKGRAY
                            textSize = 28f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                        val title = "${zone.name} (%.1f м²)".format(zone.areaSquareMeters)
                        drawText(title, centerOffset.x, centerOffset.y, textPaint)
                    }
                }
            }

            // =================================================================
            // 3. Отрисовка активной разметки периметра (Perimeter Mapping)
            // =================================================================
            if (perimeterState.isMapping && perimeterState.perimeterPoints.isNotEmpty()) {
                val pColor = Color(0xFFEF4444) // Ярко-красный цвет разметки периметра
                val points = perimeterState.perimeterPoints

                if (points.size > 1) {
                    val pPath = Path()
                    val p0 = toCanvasOffset(points.first().x, points.first().y)
                    pPath.moveTo(p0.x, p0.y)
                    for (i in 1 until points.size) {
                        val pi = toCanvasOffset(points[i].x, points[i].y)
                        pPath.lineTo(pi.x, pi.y)
                    }

                    drawPath(
                        path = pPath,
                        color = pColor,
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // Пунктир от последней контрольной точки к текущей позиции клинера
                val lastRecorded = points.last()
                val lastOffset = toCanvasOffset(lastRecorded.x, lastRecorded.y)
                val curOffset = toCanvasOffset(currentPosition.x, currentPosition.y)

                drawLine(
                    color = pColor.copy(alpha = 0.85f),
                    start = lastOffset,
                    end = curOffset,
                    strokeWidth = 2.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                )

                // Расстояние в метрах между последним углом и текущим положением
                val dx = currentPosition.x - lastRecorded.x
                val dy = currentPosition.y - lastRecorded.y
                val distM = kotlin.math.sqrt(dx * dx + dy * dy)
                if (distM > 0.3) {
                    val midX = (lastOffset.x + curOffset.x) / 2f
                    val midY = (lastOffset.y + curOffset.y) / 2f
                    drawContext.canvas.nativeCanvas.apply {
                        val distPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#B91C1C")
                            textSize = 24f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                        drawText("%.1f м".format(distM), midX, midY - 10f, distPaint)
                    }
                }

                // Нумерованные контрольные точки (углы)
                points.forEachIndexed { index, pt ->
                    val ptOffset = toCanvasOffset(pt.x, pt.y)
                    val isFirst = index == 0
                    val markerColor = if (isFirst) Color(0xFF10B981) else pColor

                    drawCircle(
                        color = Color.White,
                        radius = 9.dp.toPx(),
                        center = ptOffset
                    )
                    drawCircle(
                        color = markerColor,
                        radius = 7.dp.toPx(),
                        center = ptOffset
                    )
                    drawContext.canvas.nativeCanvas.apply {
                        val textPaint = android.graphics.Paint().apply {
                            color = android.graphics.Color.WHITE
                            textSize = 22f
                            textAlign = android.graphics.Paint.Align.CENTER
                            isFakeBoldText = true
                            isAntiAlias = true
                        }
                        drawText("${index + 1}", ptOffset.x, ptOffset.y + 7f, textPaint)
                    }
                }
            }

            // =================================================================
            // 4. Отрисовка полосы покрытия уборки (Coverage Ribbon - как у пылесоса)
            // =================================================================
            if (coverageSegments.isNotEmpty()) {
                coverageSegments.forEach { seg ->
                    if (seg.start.floor == currentPosition.floor) {
                        val p1 = toCanvasOffset(seg.start.x, seg.start.y)
                        val p2 = toCanvasOffset(seg.end.x, seg.end.y)
                        val strokePx = (seg.cleaningWidthMeters * scale).toFloat().coerceAtLeast(6f)

                        val segColor = when (seg.mode) {
                            CleaningMode.WET_CLEANING -> Color(0xFF0284C7).copy(alpha = 0.35f)
                            CleaningMode.DRY_VACUUM -> Color(0xFFF59E0B).copy(alpha = 0.35f)
                            CleaningMode.IDLE_TRANSIT -> Color(0xFF9CA3AF).copy(alpha = 0.15f)
                        }

                        drawLine(
                            color = segColor,
                            start = p1,
                            end = p2,
                            strokeWidth = strokePx,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }

            // 5. Отрисовка центральной линии траектории (Center Track)
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
                    color = Color(0xFF1E40AF).copy(alpha = 0.8f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                trajectory.forEach { pos ->
                    val pt = toCanvasOffset(pos.x, pos.y)
                    drawCircle(
                        color = MapTrajectoryPoint,
                        radius = 2.dp.toPx(),
                        center = pt
                    )
                }
            }

            // 6. Маркер стартовой точки (0,0)
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

            // 7. Отрисовка других клинеров
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

            // =================================================================
            // 8. Текущая позиция клинера: круг захвата инвентаря + стрелка курса
            // =================================================================
            val currentOffset = toCanvasOffset(currentPosition.x, currentPosition.y)
            val brushRadiusPx = ((cleaningWidthMeters / 2.0) * scale).toFloat().coerceAtLeast(8f)

            // Ореол ширины захвата швабры/пылесоса
            val toolColor = when (cleaningMode) {
                CleaningMode.WET_CLEANING -> Color(0xFF0284C7)
                CleaningMode.DRY_VACUUM -> Color(0xFFF59E0B)
                CleaningMode.IDLE_TRANSIT -> Color(0xFF6B7280)
            }

            drawCircle(
                color = toolColor.copy(alpha = 0.20f),
                radius = brushRadiusPx,
                center = currentOffset
            )
            drawCircle(
                color = toolColor,
                radius = brushRadiusPx,
                center = currentOffset,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f))
            )

            // Центральный маркер клинера
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
                    moveTo(currentOffset.x, currentOffset.y - (brushRadiusPx + 10.dp.toPx()))
                    lineTo(currentOffset.x + 6.dp.toPx(), currentOffset.y - (brushRadiusPx - 2.dp.toPx()))
                    lineTo(currentOffset.x, currentOffset.y - (brushRadiusPx + 2.dp.toPx()))
                    lineTo(currentOffset.x - 6.dp.toPx(), currentOffset.y - (brushRadiusPx - 2.dp.toPx()))
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

        // Верхний левый информационный оверлей: Объект, этаж, режим инвентаря
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.94f),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CleanBorder),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${cleaningMode.iconEmoji} ${cleaningMode.shortName} • %.0f см".format(cleaningWidthMeters * 100),
                        color = Color(0xFF1A1C1E),
                        fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                    Text(
                        text = "| эт. ${currentPosition.floor}",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }

            if (perimeterState.isMapping) {
                Surface(
                    color = Color(0xFFFEE2E2),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFCA5A5))
                ) {
                    Text(
                        text = "Разметка контура: ${perimeterState.perimeterPoints.size} точек (P=%.1fм, S=%.1fм²)".format(
                            perimeterState.computedPerimeterMeters,
                            perimeterState.computedAreaMeters
                        ),
                        color = Color(0xFF991B1B),
                        fontSize = 10.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Кнопки управления масштабом и центрированием
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilledIconButton(
                onClick = { scale = (scale * 1.3f).coerceAtMost(180f) },
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1A1C1E)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = "Увеличить",
                    modifier = Modifier.size(16.dp)
                )
            }

            FilledIconButton(
                onClick = { scale = (scale / 1.3f).coerceAtLeast(12f) },
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF1A1C1E)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.ZoomOut,
                    contentDescription = "Уменьшить",
                    modifier = Modifier.size(16.dp)
                )
            }

            FilledIconButton(
                onClick = {
                    panOffsetX = -currentPosition.x.toFloat() * scale
                    panOffsetY = currentPosition.y.toFloat() * scale
                },
                modifier = Modifier.size(34.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = "Центрировать",
                    modifier = Modifier.size(16.dp)
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
    var x = canvasCenterX % step1m
    while (x < width) {
        val meterIndex = Math.round((x - canvasCenterX) / scale)
        val isMajor = meterIndex % 5 == 0
        val isAxis = meterIndex == 0
        val color = if (isAxis) axisColor else if (isMajor) majorColor else minorColor
        val strokeW = if (isAxis) 2f else if (isMajor) 1.2f else 0.6f

        drawLine(color = color, start = Offset(x, 0f), end = Offset(x, height), strokeWidth = strokeW)
        x += step1m
    }

    var y = canvasCenterY % step1m
    while (y < height) {
        val meterIndex = Math.round((canvasCenterY - y) / scale)
        val isMajor = meterIndex % 5 == 0
        val isAxis = meterIndex == 0
        val color = if (isAxis) axisColor else if (isMajor) majorColor else minorColor
        val strokeW = if (isAxis) 2f else if (isMajor) 1.2f else 0.6f

        drawLine(color = color, start = Offset(0f, y), end = Offset(width, y), strokeWidth = strokeW)
        y += step1m
    }
}
