package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.CleaningMode
import com.example.model.ConnectionState
import com.example.model.FacilityZone
import com.example.model.ObjectCategory
import com.example.model.PdrConfig
import com.example.model.Position
import com.example.model.ServerMapConfig
import com.example.service.TrackingForegroundService
import com.example.ui.components.IndoorMapCanvas
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentRed
import com.example.ui.theme.CleanBg
import com.example.ui.theme.CleanBorder
import com.example.ui.theme.CleanChipBg
import com.example.ui.theme.CleanDarkCard
import com.example.ui.theme.CleanSurface
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.OnPrimaryContainer
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryContainer
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.viewmodel.CleanerTrackerViewModel
import com.example.viewmodel.TrackerUiState

class MainActivity : ComponentActivity() {

    private val viewModel: CleanerTrackerViewModel by viewModels {
        CleanerTrackerViewModel.provideFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                CleanerTrackerScreen(viewModel = viewModel)
            }
        }
    }
}

/**
 * Главный экран приложения трекинга клинеров с концепцией SLAM и Coverage Mapping (как у роботов-пылесосов).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanerTrackerScreen(viewModel: CleanerTrackerViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val otherUsers by viewModel.mapUpdates.collectAsState()
    val pdrConfig by viewModel.pdrConfig.collectAsState()
    val serverMapConfig by viewModel.serverMapConfig.collectAsState()
    val isSimulating by viewModel.isSimulating.collectAsState()
    val activityLogs by viewModel.activityLogs.collectAsState()
    val sessionDuration by viewModel.sessionDurationSeconds.collectAsState()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showZonesSheet by remember { mutableStateOf(false) }
    var showOnlyActiveZone by remember { mutableStateOf(true) }

    // Перехват системной кнопки "Назад" для безопасного выхода из шторок и разметки
    BackHandler(enabled = showSettingsSheet || showLogsDialog || showZonesSheet || uiState.trackerState.perimeterState.isMapping) {
        when {
            showSettingsSheet -> showSettingsSheet = false
            showLogsDialog -> showLogsDialog = false
            showZonesSheet -> showZonesSheet = false
            uiState.trackerState.perimeterState.isMapping -> viewModel.cancelPerimeterMapping()
        }
    }

    // Запрос необходимых разрешений
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.HIGH_SAMPLING_RATE_SENSORS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // Управление фоновым сервисом для непрерывной работы при выключенном экране и переключении в браузер
    LaunchedEffect(uiState.trackerState.isTracking, isSimulating) {
        if (uiState.trackerState.isTracking || isSimulating) {
            TrackingForegroundService.start(context)
        } else {
            TrackingForegroundService.stop(context)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
        containerColor = CleanBg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 1. Верхняя панель: Indoor Track v2.0 SLAM, Cleaner Console, Socket status, ID
            CleanHeaderBar(
                userId = uiState.userId,
                cleanerName = uiState.cleanerName,
                connectionState = uiState.connectionState,
                onReconnect = { viewModel.reconnectSocket() },
                onOpenSettings = { showSettingsSheet = true }
            )

            // Активный интерактивный мастер разметки периметра (Interactive Walk HUD)
            if (uiState.trackerState.perimeterState.isMapping) {
                val pState = uiState.trackerState.perimeterState
                val lastPt = pState.perimeterPoints.lastOrNull() ?: uiState.trackerState.currentPosition
                val curPos = uiState.trackerState.currentPosition
                val currentWallDist = kotlin.math.sqrt(
                    (curPos.x - lastPt.x) * (curPos.x - lastPt.x) + (curPos.y - lastPt.y) * (curPos.y - lastPt.y)
                )

                val stepInstruction = when (pState.perimeterPoints.size) {
                    0 -> "Старт: Встаньте в 1-й угол комнаты и нажмите «+ Угол 1»"
                    1 -> "Стена 1: Идите вдоль стены до угла 2. Нажмите «+ Угол 2»"
                    2 -> "Стена 2: Поверните и идите вдоль стены до угла 3. Нажмите «+ Угол 3»"
                    3 -> "Стена 3: Идите вдоль стены до угла 4. Нажмите «+ Угол 4» или «Замкнуть»"
                    else -> "Финал: Вернитесь в точку старта (Угол 1) и нажмите «✓ Замкнуть контур»"
                }

                Surface(
                    color = Color(0xFF0F172A),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.5.dp, PrimaryBlue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(PrimaryBlue),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DirectionsWalk,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Text(
                                    text = "МАСТЕР ОБХОДА: ${pState.zoneName}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 1
                                )
                            }

                            TextButton(
                                onClick = { viewModel.cancelPerimeterMapping() },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Отмена", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
                            }
                        }

                        // Текст подсказки текущего шага
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 $stepInstruction",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF38BDF8),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }

                        // Метрики разметки: длина текущей стены, точек зафиксировано, площадь
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Текущая стена: %.1f м".format(currentWallDist),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFACC15)
                            )
                            Text(
                                text = "Углов: ${pState.perimeterPoints.size} • S ≈ %.1f м²".format(pState.computedAreaMeters),
                                fontSize = 11.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }

                        // Кнопки действий прямо в мастере: Шаг +0.6м, Поворот 90°, + Угол, Замкнуть
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Button(
                                onClick = { viewModel.addPerimeterPoint() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1.2f).height(38.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("+ Угол (${pState.perimeterPoints.size + 1})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = { viewModel.closePerimeter() },
                                enabled = pState.perimeterPoints.size >= 3,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentGreen,
                                    disabledContainerColor = Color(0xFF334155)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1.1f).height(38.dp)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("Замкнуть", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            FilledTonalButton(
                                onClick = { viewModel.manualStepForward(0.6) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(0.9f).height(38.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                            ) {
                                Text("🚶 +0.6м", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }

                            FilledTonalButton(
                                onClick = { viewModel.manualTurn(90f) },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(0.7f).height(38.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                            ) {
                                Text("↪ 90°", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // 2. Селектор активного объекта (Подъезд / Двор) и режимов уборочного инвентаря
            CleanObjectAndModePill(
                currentZone = uiState.trackerState.currentZone,
                activeMode = uiState.trackerState.cleaningMode,
                cleaningWidthMeters = uiState.trackerState.cleaningWidthMeters,
                onOpenZonePicker = { showZonesSheet = true },
                onSelectMode = { viewModel.updateCleaningMode(it) },
                onSelectWidth = { viewModel.updateCleaningWidth(it) }
            )

            // 3. Основная карточка текущего положения и метрик покрытия
            CleanPositionAndCoverageCard(
                uiState = uiState,
                onFloorChange = { newFloor -> viewModel.setFloor(newFloor) },
                onReset = { viewModel.resetPosition(0.0, 0.0, 0f) }
            )

            // 4. IMU Diagnostics & 2D Карта-холст покрытия (SLAM Coverage Map)
            CleanDiagnosticsAndMapCard(
                uiState = uiState,
                otherUsers = otherUsers,
                showOnlyActiveZone = showOnlyActiveZone,
                modifier = Modifier.weight(1f)
            )

            // 5. Карточка активной сессии (Dark Summary Pill)
            CleanActiveSessionCard(
                isTracking = uiState.trackerState.isTracking,
                durationSeconds = sessionDuration,
                packetsCount = uiState.trackerState.packetsSentCount,
                coveredAreaM2 = uiState.trackerState.coveredAreaM2
            )

            // 6. Нижняя панель действий (Завершить/Начать уборку + Разметка периметра + Симуляция + Логи + Настройки)
            CleanBottomControlSection(
                isTracking = uiState.trackerState.isTracking,
                isSimulating = isSimulating,
                isPerimeterMapping = uiState.trackerState.perimeterState.isMapping,
                perimeterPointsCount = uiState.trackerState.perimeterState.perimeterPoints.size,
                perimeterAreaM2 = uiState.trackerState.perimeterState.computedAreaMeters,
                onToggleCleaning = { viewModel.toggleCleaningSession() },
                onAddPerimeterPoint = { viewModel.addPerimeterPoint() },
                onClosePerimeter = { viewModel.closePerimeter() },
                onCancelPerimeter = { viewModel.cancelPerimeterMapping() },
                onOpenZonesSheet = { showZonesSheet = true },
                onToggleSimulation = { viewModel.toggleSimulation() },
                onOpenLogs = { showLogsDialog = true },
                onOpenSettings = { showSettingsSheet = true }
            )
        }

        // Шторка объектов и разметки периметра
        if (showZonesSheet) {
            ZonesAndPerimeterBottomSheet(
                savedZones = uiState.trackerState.savedZones,
                currentZone = uiState.trackerState.currentZone,
                isPerimeterMapping = uiState.trackerState.perimeterState.isMapping,
                showOnlyActiveZone = showOnlyActiveZone,
                onToggleShowOnlyActive = { showOnlyActiveZone = it },
                onDismiss = { showZonesSheet = false },
                onSelectZone = {
                    viewModel.selectActiveZone(it)
                    showZonesSheet = false
                },
                onQuickCreateZone = { name, category, floor, width, height ->
                    viewModel.createQuickZone(name, category, floor, width, height)
                    showZonesSheet = false
                },
                onStartNewPerimeter = { name, category, floor ->
                    viewModel.startPerimeterMapping(name, category, floor)
                    showZonesSheet = false
                },
                onDeleteZone = { viewModel.deleteZone(it) }
            )
        }

        // Модальное окно калибровки и настроек PDR и веб-карты
        if (showSettingsSheet) {
            SettingsBottomSheet(
                config = pdrConfig,
                mapConfig = serverMapConfig,
                currentPosition = uiState.trackerState.currentPosition,
                onDismiss = { showSettingsSheet = false },
                onUpdateCleanerName = { viewModel.updateCleanerName(it) },
                onUpdateMapConfig = { ox, oy, scale -> viewModel.updateServerMapConfig(ox, oy, scale) },
                onUpdateStepLength = { viewModel.updateStepLength(it) },
                onUpdateThreshold = { viewModel.updateStepThreshold(it) },
                onReset = { viewModel.resetPosition(0.0, 0.0, 0f) },
                onSendTestCenter = {
                    viewModel.resetPosition(0.0, 0.0, 0f)
                    viewModel.sendCurrentServerPosition()
                }
            )
        }

        // Модальное окно логов отладки
        if (showLogsDialog) {
            LogsBottomSheet(
                logs = activityLogs,
                onDismiss = { showLogsDialog = false }
            )
        }
    }
}

/**
 * Верхняя строка: Indoor Track v2.0, Статус сокета и ID клинера.
 */
@Composable
fun CleanHeaderBar(
    userId: String,
    cleanerName: String,
    connectionState: ConnectionState,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "INDOOR TRACK • SLAM",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue,
                letterSpacing = 0.8.sp
            )
            Text(
                text = cleanerName.ifEmpty { "Cleaner Console" },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onReconnect() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("connection_badge")
            ) {
                val dotColor = when (connectionState) {
                    is ConnectionState.Connected -> AccentGreen
                    is ConnectionState.Connecting -> Color(0xFFF59E0B)
                    else -> AccentRed
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = "icv.dotozen.ru",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
            }

            Text(
                text = "ID: ${userId.takeLast(8)}",
                fontSize = 9.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

/**
 * Плашка выбора объекта (Подъезд, этаж, двор) и переключения режима/ширины уборочного инвентаря.
 */
@Composable
fun CleanObjectAndModePill(
    currentZone: FacilityZone?,
    activeMode: CleaningMode,
    cleaningWidthMeters: Double,
    onOpenZonePicker: () -> Unit,
    onSelectMode: (CleaningMode) -> Unit,
    onSelectWidth: (Double) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Строка 1: Текущий объект (Подъезд/Двор)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpenZonePicker() }
                    .background(CleanChipBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    val icon = if (currentZone?.category == ObjectCategory.OUTDOOR_YARD) Icons.Default.Park else Icons.Default.Domain
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = currentZone?.let { "${it.name} (${it.areaSquareMeters.toInt()} м²)" } ?: "Объект не выбран",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                }

                Surface(
                    color = PrimaryContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "Объекты",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = OnPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        maxLines = 1
                    )
                }
            }

            // Строка 2: Режимы инвентаря клинера (Влажная, Сухая, Простой) - 3 режима равномерно без переносов
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CleaningMode.values().forEach { mode ->
                    val isSelected = mode == activeMode
                    val chipBg = if (isSelected) PrimaryBlue else CleanChipBg
                    val contentColor = if (isSelected) Color.White else TextPrimary

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(chipBg)
                            .clickable { onSelectMode(mode) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(text = mode.iconEmoji, fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = mode.shortName,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }

            // Строка 3: Ширина захвата инвентаря (Швабра/Пылесос 40 см - 100 см)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Ширина захвата:",
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.4, 0.5, 0.6, 0.8, 1.0).forEach { width ->
                        val isSelected = (cleaningWidthMeters == width)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isSelected) PrimaryContainer else CleanChipBg)
                                .clickable { onSelectWidth(width) }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "%.0fсм".format(width * 100),
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) PrimaryBlue else TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Карточка текущего положения и метрик покрытия (Coverage %).
 */
@Composable
fun CleanPositionAndCoverageCard(
    uiState: TrackerUiState,
    onFloorChange: (Int) -> Unit,
    onReset: () -> Unit
) {
    val pos = uiState.trackerState.currentPosition
    val currentZone = uiState.trackerState.currentZone
    val coveredArea = uiState.trackerState.coveredAreaM2

    val coveragePercent = if (currentZone != null && currentZone.areaSquareMeters > 0) {
        ((coveredArea / currentZone.areaSquareMeters) * 100.0).coerceIn(0.0, 100.0)
    } else {
        0.0
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card")
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Верхний ряд: локация, этаж
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(PrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(16.dp)
                        )
                    }

                    Column {
                        Text(
                            text = "ПОЗИЦИЯ & ПОКРЫТИЕ",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Этаж %d • Сайт [800×600 px]".format(pos.floor),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }
                }

                // Кнопки этажа
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CleanChipBg)
                        .padding(2.dp)
                ) {
                    IconButton(
                        onClick = { onFloorChange(pos.floor - 1) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Remove,
                            contentDescription = "Этаж вниз",
                            modifier = Modifier.size(12.dp),
                            tint = TextPrimary
                        )
                    }
                    Text(
                        text = "эт. ${pos.floor}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 3.dp)
                    )
                    IconButton(
                        onClick = { onFloorChange(pos.floor + 1) },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Этаж вверх",
                            modifier = Modifier.size(12.dp),
                            tint = TextPrimary
                        )
                    }
                }
            }

            // Метрики: X, Y, Убрано м², Покрытие %
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CleanMetricBox(
                    label = "X (МЕТРЫ)",
                    value = "%.2f".format(pos.x),
                    subLabel = "Web: %.0f px".format(uiState.serverX),
                    modifier = Modifier.weight(1f)
                )
                CleanMetricBox(
                    label = "Y (МЕТРЫ)",
                    value = "%.2f".format(pos.y),
                    subLabel = "Web: %.0f px".format(uiState.serverY),
                    modifier = Modifier.weight(1f)
                )
                CleanMetricBox(
                    label = "УБРАНО",
                    value = "%.1f".format(coveredArea),
                    subLabel = if (currentZone != null) "%.0f%% зоны".format(coveragePercent) else "м²",
                    modifier = Modifier.weight(1f),
                    accent = true
                )
            }
        }
    }
}

@Composable
fun CleanMetricBox(
    label: String,
    value: String,
    subLabel: String = "",
    accent: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) PrimaryContainer.copy(alpha = 0.5f) else CleanChipBg)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (accent) PrimaryBlue else TextSecondary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1
            )
            if (subLabel.isNotEmpty()) {
                Text(
                    text = subLabel,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (accent) PrimaryBlue else TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * IMU Diagnostics & 2D Coverage SLAM Canvas Container.
 */
@Composable
fun CleanDiagnosticsAndMapCard(
    uiState: TrackerUiState,
    otherUsers: List<com.example.model.UserPosition>,
    showOnlyActiveZone: Boolean = true,
    modifier: Modifier = Modifier
) {
    val state = uiState.trackerState

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Diagnostics Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SLAM COVERAGE MAP",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Шагов: ${state.stepCount} • %.1f м".format(state.totalDistance),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted,
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (state.isTracking) PrimaryContainer else CleanChipBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (state.isTracking) "УБОРКА" else "ОЖИДАНИЕ",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.isTracking) PrimaryBlue else TextSecondary
                        )
                    }
                }
            }

            // 2D Map Canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                IndoorMapCanvas(
                    currentPosition = state.currentPosition,
                    headingDegrees = state.headingDegrees,
                    trajectory = state.trajectory,
                    coverageSegments = state.coverageSegments,
                    savedZones = state.savedZones,
                    currentZone = state.currentZone,
                    perimeterState = state.perimeterState,
                    cleaningWidthMeters = state.cleaningWidthMeters,
                    cleaningMode = state.cleaningMode,
                    otherUsers = otherUsers,
                    showOnlyActiveZone = showOnlyActiveZone,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Карточка активной сессии (Dark Summary Pill) с площадью уборки.
 */
@Composable
fun CleanActiveSessionCard(
    isTracking: Boolean,
    durationSeconds: Long,
    packetsCount: Long,
    coveredAreaM2: Double
) {
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    val formattedTime = "%02d:%02d:%02d".format(hours, minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CleanDarkCard)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(AccentGreen.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Column {
                    Text(
                        text = "ВРЕМЯ УБОРКИ",
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (isTracking) formattedTime else "00:00:00",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ПЛОЩАДЬ",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "%.1f м²".format(coveredAreaM2),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "WSS ПАКЕТОВ",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "$packetsCount",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Нижняя панель действий: Кнопка Старт/Стоп или разметки периметра + навигация.
 */
@Composable
fun CleanBottomControlSection(
    isTracking: Boolean,
    isSimulating: Boolean,
    isPerimeterMapping: Boolean,
    perimeterPointsCount: Int,
    perimeterAreaM2: Double,
    onToggleCleaning: () -> Unit,
    onAddPerimeterPoint: () -> Unit,
    onClosePerimeter: () -> Unit,
    onCancelPerimeter: () -> Unit,
    onOpenZonesSheet: () -> Unit,
    onToggleSimulation: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val buttonColor by animateColorAsState(
        targetValue = if (isTracking) AccentRed else PrimaryBlue,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "btn_color"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (isPerimeterMapping) {
            // Кнопки управления разметкой периметра (Компактно снизу в одну строку)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onAddPerimeterPoint,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("+ Угол ($perimeterPointsCount)", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                Button(
                    onClick = onClosePerimeter,
                    enabled = perimeterPointsCount >= 3,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1.1f).height(44.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("Замкнуть", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onCancelPerimeter,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(0.8f).height(44.dp)
                ) {
                    Text("Отмена", fontSize = 11.sp, color = TextSecondary, maxLines = 1)
                }
            }
        } else {
            // Главная кнопка действия (Начать / Завершить уборку)
            Button(
                onClick = onToggleCleaning,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("toggle_cleaning_button")
            ) {
                Icon(
                    imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isTracking) "Завершить уборку" else "Начать уборку",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1
                )
            }
        }

        // Нижняя строка навигации и вспомогательных действий
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Объекты / Периметр
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenZonesSheet() }
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isPerimeterMapping) Color(0xFFFEE2E2) else PrimaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Polyline,
                        contentDescription = "Объекты",
                        tint = if (isPerimeterMapping) AccentRed else OnPrimaryContainer,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "Объекты",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }

            // 2. Симуляция шагов
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onToggleSimulation() }
                    .padding(2.dp)
                    .testTag("simulation_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSimulating) PrimaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSimulating) Icons.Default.PauseCircle else Icons.Default.DirectionsWalk,
                        contentDescription = "Симуляция",
                        tint = if (isSimulating) PrimaryBlue else TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = if (isSimulating) "Стоп тест" else "Тест шагов",
                    fontSize = 9.sp,
                    fontWeight = if (isSimulating) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSimulating) PrimaryBlue else TextSecondary
                )
            }

            // 3. Логи WSS
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenLogs() }
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 24.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Логи WSS",
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "Логи WSS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
            }

            // 4. Настройки
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenSettings() }
                    .padding(2.dp)
                    .testTag("settings_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 38.dp, height = 24.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Настройки",
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = "Настройки",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextSecondary
                )
            }
        }
    }
}

/**
 * Шторка управления объектами уборки (Подъезды, Дворы) и разметки контуров (Perimeter SLAM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZonesAndPerimeterBottomSheet(
    savedZones: List<FacilityZone>,
    currentZone: FacilityZone?,
    isPerimeterMapping: Boolean,
    showOnlyActiveZone: Boolean,
    onToggleShowOnlyActive: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSelectZone: (FacilityZone) -> Unit,
    onQuickCreateZone: (String, ObjectCategory, Int, Double, Double) -> Unit,
    onStartNewPerimeter: (String, ObjectCategory, Int) -> Unit,
    onDeleteZone: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isCreatingNew by remember { mutableStateOf(false) }
    var creationMethod by remember { mutableStateOf(0) } // 0 = Готовый размер (1 клик), 1 = Обход периметра (PDR)
    var newZoneName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ObjectCategory.ENTRANCE_BUILDING) }
    var selectedFloor by remember { mutableStateOf(1) }
    var customWidthM by remember { mutableStateOf("6.0") }
    var customHeightM by remember { mutableStateOf("4.0") }

    // Intercept back button when in create mode to return to list instead of closing app
    BackHandler(enabled = isCreatingNew) {
        isCreatingNew = false
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CleanSurface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isCreatingNew) "Создание нового объекта" else "Объекты уборки",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    text = if (isCreatingNew) "Создайте объект готового размера или пройдите периметр пешком." else "Выберите активный объект для фокусировки карты или создайте новый.",
                    fontSize = 11.sp,
                    color = TextMuted
                )
            }

            if (!isCreatingNew) {
                // Тумблер: Показывать только активный объект (устраняет кучу на карте)
                item {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = CleanChipBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Только активный объект на карте",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (showOnlyActiveZone) "Скрывает другие объекты, фокус на текущем" else "Отображает все сохраненные объекты разом",
                                    fontSize = 10.sp,
                                    color = TextMuted
                                )
                            }
                            androidx.compose.material3.Switch(
                                checked = showOnlyActiveZone,
                                onCheckedChange = onToggleShowOnlyActive,
                                modifier = Modifier.size(width = 46.dp, height = 28.dp)
                            )
                        }
                    }
                }

                // Кнопка создать новый объект
                item {
                    Button(
                        onClick = { isCreatingNew = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Создать / Разметить новый объект", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Список сохраненных зон
                items(savedZones) { zone ->
                    val isSelected = currentZone?.id == zone.id
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) PrimaryContainer.copy(alpha = 0.5f) else CleanChipBg
                        ),
                        border = BorderStroke(1.dp, if (isSelected) PrimaryBlue else CleanBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectZone(zone) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = zone.category.emoji,
                                    fontSize = 20.sp
                                )
                                Column {
                                    Text(
                                        text = zone.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${zone.category.title} • эт. ${zone.floor} • S ≈ %.1f м² (${zone.polygonPoints.size} углов)".format(zone.areaSquareMeters),
                                        fontSize = 11.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Выбран",
                                        tint = PrimaryBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    IconButton(
                                        onClick = { onDeleteZone(zone.id) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Удалить",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Форма создания нового объекта (с двумя режимами: 1 клик или Обход)
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = CleanChipBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Переключатель метода создания: Шаблон (1 клик) / Обход периметра (PDR)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White)
                                    .padding(3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Button(
                                    onClick = { creationMethod = 0 },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (creationMethod == 0) PrimaryBlue else Color.Transparent,
                                        contentColor = if (creationMethod == 0) Color.White else TextPrimary
                                    ),
                                    elevation = null,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    Text("⚡ Готовые размеры (1 клик)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }

                                Button(
                                    onClick = { creationMethod = 1 },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (creationMethod == 1) PrimaryBlue else Color.Transparent,
                                        contentColor = if (creationMethod == 1) Color.White else TextPrimary
                                    ),
                                    elevation = null,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(36.dp)
                                ) {
                                    Text("🚶 Обход ногами (PDR)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                            }

                            // Категория: Подъезд или Двор
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ObjectCategory.values().forEach { cat ->
                                    val isCatSelected = selectedCategory == cat
                                    FilledTonalButton(
                                        onClick = { selectedCategory = cat },
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isCatSelected) PrimaryBlue else Color.White,
                                            contentColor = if (isCatSelected) Color.White else TextPrimary
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Text("${cat.emoji} ${cat.title}", fontSize = 11.sp, maxLines = 1)
                                    }
                                }
                            }

                            // Название объекта
                            OutlinedTextField(
                                value = newZoneName,
                                onValueChange = { newZoneName = it },
                                label = { Text("Название (например: Подъезд 1 • Этаж $selectedFloor)", fontSize = 11.sp) },
                                placeholder = { Text("Введите название...", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )

                            // Выбор этажа
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Этаж здания:", fontSize = 12.sp, color = TextPrimary)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { if (selectedFloor > -1) selectedFloor-- },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                    Text("эт. $selectedFloor", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    IconButton(
                                        onClick = { selectedFloor++ },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }

                            if (creationMethod == 0) {
                                // ⚡ БЫСТРЫЕ ШАБЛОНЫ РАЗМЕРОВ
                                Text(
                                    text = "Быстрые шаблоны с точными углами:",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSecondary
                                )

                                val presets = listOf(
                                    Triple("4×3 м", 4.0, 3.0),
                                    Triple("6×4 м (24 м²)", 6.0, 4.0),
                                    Triple("10×6 м (60 м²)", 10.0, 6.0),
                                    Triple("20×10 м (200 м²)", 20.0, 10.0)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    presets.forEach { (title, w, h) ->
                                        OutlinedButton(
                                            onClick = {
                                                customWidthM = w.toString()
                                                customHeightM = h.toString()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).height(34.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
                                        ) {
                                            Text(title, fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = customWidthM,
                                        onValueChange = { customWidthM = it },
                                        label = { Text("Длина (м)", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    OutlinedTextField(
                                        value = customHeightM,
                                        onValueChange = { customHeightM = it },
                                        label = { Text("Ширина (м)", fontSize = 10.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            } else {
                                // 🚶 ИНСТРУКЦИЯ ПО ОБХОДУ ПЕРИМЕТРА
                                Surface(
                                    color = PrimaryContainer.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Как правильно разметить помещение:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryBlue
                                        )
                                        Text(
                                            text = "1. Встаньте в первый угол комнаты и нажмите «Начать обход».\n2. Дойдите вдоль стены до следующего угла и нажмите «+ Угол».\n3. Обойдите все углы помещения и нажмите «Замкнуть».",
                                            fontSize = 10.sp,
                                            color = TextPrimary,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                            }

                            // Кнопки Действия / Отмена
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { isCreatingNew = false },
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f).height(42.dp)
                                ) {
                                    Text("Назад", fontSize = 12.sp)
                                }

                                if (creationMethod == 0) {
                                    Button(
                                        onClick = {
                                            val w = customWidthM.toDoubleOrNull() ?: 6.0
                                            val h = customHeightM.toDoubleOrNull() ?: 4.0
                                            val finalName = newZoneName.ifEmpty {
                                                if (selectedCategory == ObjectCategory.ENTRANCE_BUILDING) "Подъезд • эт. $selectedFloor (${w.toInt()}×${h.toInt()} м)" else "Двор (${w.toInt()}×${h.toInt()} м)"
                                            }
                                            onQuickCreateZone(finalName, selectedCategory, selectedFloor, w, h)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1.6f).height(42.dp)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Создать объект", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            val finalName = newZoneName.ifEmpty {
                                                if (selectedCategory == ObjectCategory.ENTRANCE_BUILDING) "Подъезд • Этаж $selectedFloor" else "Дворовая территория"
                                            }
                                            onStartNewPerimeter(finalName, selectedCategory, selectedFloor)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1.6f).height(42.dp)
                                    ) {
                                        Icon(Icons.Default.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Начать обход", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom Sheet для калибровки PDR алгоритма, настроек веб-карты и сброса координат.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    config: PdrConfig,
    mapConfig: ServerMapConfig,
    currentPosition: Position,
    onDismiss: () -> Unit,
    onUpdateCleanerName: (String) -> Unit,
    onUpdateMapConfig: (Double, Double, Double) -> Unit,
    onUpdateStepLength: (Double) -> Unit,
    onUpdateThreshold: (Float) -> Unit,
    onReset: () -> Unit,
    onSendTestCenter: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var stepLen by remember { mutableFloatStateOf(config.stepLength.toFloat()) }
    var threshold by remember { mutableFloatStateOf(config.stepThreshold) }
    var originX by remember { mutableFloatStateOf(mapConfig.originX.toFloat()) }
    var originY by remember { mutableFloatStateOf(mapConfig.originY.toFloat()) }
    var scalePx by remember { mutableFloatStateOf(mapConfig.pixelsPerMeter.toFloat()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CleanSurface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Настройки и интеграция с веб-сайтом",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 1. Быстрая проверка отправки в центр
            item {
                Surface(
                    color = PrimaryContainer,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Координаты на веб-сервере (icv.dotozen.ru)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = OnPrimaryContainer
                        )
                        Text(
                            text = "Сайт использует холст 800×600 px. Приложение автоматически пересчитывает метры шагов в пиксели холста.",
                            fontSize = 11.sp,
                            color = OnPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Button(
                            onClick = onSendTestCenter,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отправить маркер в центр сайта (400, 300)")
                        }
                    }
                }
            }

            // 2. Длина шага клинера
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Длина шага клинера",
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "%.2f м".format(stepLen),
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue
                        )
                    }
                    Slider(
                        value = stepLen,
                        onValueChange = {
                            stepLen = it
                            onUpdateStepLength(it.toDouble())
                        },
                        valueRange = 0.4f..1.2f,
                        steps = 15,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        )
                    )
                }
            }

            // 3. Порог акселерометра для детекции шага
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Порог детекции шага (акселерометр)",
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "%.2f м/с²".format(threshold),
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue
                        )
                    }
                    Slider(
                        value = threshold,
                        onValueChange = {
                            threshold = it
                            onUpdateThreshold(it)
                        },
                        valueRange = 0.8f..3.0f,
                        steps = 21,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        )
                    )
                }
            }

            // 4. Масштаб пикселей на метр
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Масштаб на веб-карте (px / метр)",
                            fontSize = 14.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "%.0f px/м".format(scalePx),
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue
                        )
                    }
                    Slider(
                        value = scalePx,
                        onValueChange = {
                            scalePx = it
                            onUpdateMapConfig(originX.toDouble(), originY.toDouble(), it.toDouble())
                        },
                        valueRange = 5f..50f,
                        steps = 18,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryBlue,
                            activeTrackColor = PrimaryBlue
                        )
                    )
                }
            }

            // 5. Кнопки сброса координат
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onReset()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("reset_position_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Сброс (0,0)")
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

/**
 * Bottom Sheet со списком системных логов и сокет-событий.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsBottomSheet(
    logs: List<String>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CleanSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Логи событий Socket.io и PDR",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(CleanDarkCard)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { logLine ->
                    Text(
                        text = logLine,
                        fontSize = 11.sp,
                        color = if (logLine.contains("Ошибка") || logLine.contains("Error")) Color(0xFFFF8A80) else Color(0xFFE2E8F0),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Закрыть")
            }
        }
    }
}
