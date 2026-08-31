package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Верхняя панель: Indoor Track v2.0 SLAM, Cleaner Console, Socket status, ID
            CleanHeaderBar(
                userId = uiState.userId,
                cleanerName = uiState.cleanerName,
                connectionState = uiState.connectionState,
                onReconnect = { viewModel.reconnectSocket() },
                onOpenSettings = { showSettingsSheet = true }
            )

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
                onAddPerimeterPoint = { viewModel.addPerimeterPoint() },
                onClosePerimeter = { viewModel.closePerimeter() },
                onCancelPerimeter = { viewModel.cancelPerimeterMapping() },
                modifier = Modifier.weight(1f)
            )

            // 5. Карточка активной сессии (Dark Summary Pill)
            CleanActiveSessionCard(
                isTracking = uiState.trackerState.isTracking,
                durationSeconds = sessionDuration,
                packetsCount = uiState.trackerState.packetsSentCount,
                coveredAreaM2 = uiState.trackerState.coveredAreaM2
            )

            // 6. Нижняя панель действий (Завершить/Начать уборку + Разметка + Симуляция + Логи + Настройки)
            CleanBottomControlSection(
                isTracking = uiState.trackerState.isTracking,
                isSimulating = isSimulating,
                isPerimeterMapping = uiState.trackerState.perimeterState.isMapping,
                onToggleCleaning = { viewModel.toggleCleaningSession() },
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
                onDismiss = { showZonesSheet = false },
                onSelectZone = {
                    viewModel.selectActiveZone(it)
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
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "INDOOR TRACK • SLAM COVERAGE",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue,
                letterSpacing = 1.sp
            )
            Text(
                text = cleanerName.ifEmpty { "Cleaner Console" },
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        .size(8.dp)
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
                text = "ID: ${userId.takeLast(10)}",
                fontSize = 10.sp,
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Строка 1: Текущий объект (Подъезд/Двор)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenZonePicker() }
                    .background(CleanChipBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val icon = if (currentZone?.category == ObjectCategory.OUTDOOR_YARD) Icons.Default.Park else Icons.Default.Domain
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Text(
                            text = "ОБЪЕКТ УБОРКИ",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        Text(
                            text = currentZone?.let { "${it.name} (${it.areaSquareMeters.toInt()} м²)" } ?: "Объект не выбран",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                }

                Surface(
                    color = PrimaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Сменить / Разметить",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = OnPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            // Строка 2: Режимы инвентаря клинера (Влажная, Сухая, Санобработка, Простой)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CleaningMode.values().forEach { mode ->
                    val isSelected = mode == activeMode
                    val chipBg = if (isSelected) PrimaryBlue else CleanChipBg
                    val contentColor = if (isSelected) Color.White else TextPrimary

                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(chipBg)
                            .clickable { onSelectMode(mode) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = mode.iconEmoji, fontSize = 12.sp)
                        Text(
                            text = mode.shortName,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = contentColor
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
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) PrimaryContainer else CleanChipBg)
                                .clickable { onSelectWidth(width) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
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
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Верхний ряд: локация, этаж
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
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(18.dp)
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
                        .clip(RoundedCornerShape(10.dp))
                        .background(CleanChipBg)
                        .padding(2.dp)
                ) {
                    IconButton(
                        onClick = { onFloorChange(pos.floor - 1) },
                        modifier = Modifier.size(24.dp)
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
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = { onFloorChange(pos.floor + 1) },
                        modifier = Modifier.size(24.dp)
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
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
            .clip(RoundedCornerShape(14.dp))
            .background(if (accent) PrimaryContainer.copy(alpha = 0.5f) else CleanChipBg)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = if (accent) PrimaryBlue else TextSecondary
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                fontSize = 19.sp,
                fontWeight = FontWeight.Light,
                color = TextPrimary
            )
            if (subLabel.isNotEmpty()) {
                Text(
                    text = subLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (accent) PrimaryBlue else TextMuted
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
    onAddPerimeterPoint: () -> Unit,
    onClosePerimeter: () -> Unit,
    onCancelPerimeter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state = uiState.trackerState
    val perimeter = state.perimeterState

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Diagnostics Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SLAM COVERAGE MAP",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Шагов: ${state.stepCount} • %.1f м".format(state.totalDistance),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (state.isTracking) PrimaryContainer else CleanChipBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (state.isTracking) "УБОРКА" else "ОЖИДАНИЕ",
                            fontSize = 10.sp,
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
                    modifier = Modifier.fillMaxSize()
                )

                // Плашка управления активной разметкой периметра (Perimeter SLAM Controls)
                if (perimeter.isMapping) {
                    Surface(
                        color = Color(0xFF1E293B).copy(alpha = 0.95f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .fillMaxWidth(0.94f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "РАЗМЕТКА КОНТУРА",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = "Точек: ${perimeter.perimeterPoints.size} (S: %.1f м²)".format(perimeter.computedAreaMeters),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = onAddPerimeterPoint,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("+ Угол", fontSize = 11.sp)
                                }

                                Button(
                                    onClick = onClosePerimeter,
                                    enabled = perimeter.perimeterPoints.size >= 3,
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Замкнуть", fontSize = 11.sp)
                                }

                                IconButton(
                                    onClick = onCancelPerimeter,
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Отмена", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
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
            .clip(RoundedCornerShape(18.dp))
            .background(CleanDarkCard)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(AccentGreen.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Column {
                    Text(
                        text = "ВРЕМЯ УБОРКИ",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        text = if (isTracking) formattedTime else "00:00:00",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "ПЛОЩАДЬ ПОКРЫТИЯ",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "%.1f м²".format(coveredAreaM2),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF38BDF8)
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "ПАКЕТОВ WSS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Text(
                    text = "$packetsCount",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Нижняя панель действий: Кнопка Старт/Стоп и навигация.
 */
@Composable
fun CleanBottomControlSection(
    isTracking: Boolean,
    isSimulating: Boolean,
    isPerimeterMapping: Boolean,
    onToggleCleaning: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Главная кнопка действия
        Button(
            onClick = onToggleCleaning,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("toggle_cleaning_button")
        ) {
            Icon(
                imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isTracking) "Завершить уборку" else "Начать уборку",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
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
                        .size(width = 40.dp, height = 26.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isPerimeterMapping) Color(0xFFFEE2E2) else PrimaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Polyline,
                        contentDescription = "Объекты",
                        tint = if (isPerimeterMapping) AccentRed else OnPrimaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Объекты",
                    fontSize = 10.sp,
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
                        .size(width = 40.dp, height = 26.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSimulating) PrimaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSimulating) Icons.Default.PauseCircle else Icons.Default.DirectionsWalk,
                        contentDescription = "Симуляция",
                        tint = if (isSimulating) PrimaryBlue else TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isSimulating) "Стоп тест" else "Тест шагов",
                    fontSize = 10.sp,
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
                        .size(width = 40.dp, height = 26.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Логи WSS",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Логи WSS",
                    fontSize = 10.sp,
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
                        .size(width = 40.dp, height = 26.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Настройки",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Настройки",
                    fontSize = 10.sp,
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
    onDismiss: () -> Unit,
    onSelectZone: (FacilityZone) -> Unit,
    onStartNewPerimeter: (String, ObjectCategory, Int) -> Unit,
    onDeleteZone: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var isCreatingNew by remember { mutableStateOf(false) }
    var newZoneName by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ObjectCategory.ENTRANCE_BUILDING) }
    var selectedFloor by remember { mutableStateOf(1) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CleanSurface
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    text = "Объекты уборки (Подъезды и Дворы)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Выберите объект для отслеживания покрытия уборки или разметьте новый контур обходом периметра.",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }

            if (!isCreatingNew) {
                // Кнопка начать разметку нового объекта
                item {
                    Button(
                        onClick = { isCreatingNew = true },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Разметить новый объект (Обход периметра)")
                    }
                }

                // Список сохраненных зон
                items(savedZones) { zone ->
                    val isSelected = currentZone?.id == zone.id
                    Card(
                        shape = RoundedCornerShape(14.dp),
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
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = zone.category.emoji,
                                    fontSize = 22.sp
                                )
                                Column {
                                    Text(
                                        text = zone.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${zone.category.title} • эт. ${zone.floor} • S ≈ %.0f м²".format(zone.areaSquareMeters),
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
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    IconButton(
                                        onClick = { onDeleteZone(zone.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Delete,
                                            contentDescription = "Удалить",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Форма создания нового объекта для разметки периметра
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = CleanChipBg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Параметры нового объекта",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

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
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("${cat.emoji} ${cat.title}", fontSize = 12.sp)
                                    }
                                }
                            }

                            // Название объекта
                            OutlinedTextField(
                                value = newZoneName,
                                onValueChange = { newZoneName = it },
                                label = { Text("Название (например: Подъезд 1 • Этаж 3)") },
                                placeholder = { Text("Введите название...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            )

                            // Выбор этажа
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Этаж здания:", fontSize = 13.sp, color = TextPrimary)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    IconButton(
                                        onClick = { if (selectedFloor > -1) selectedFloor-- },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                    Text("эт. $selectedFloor", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    IconButton(
                                        onClick = { selectedFloor++ },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }

                            // Кнопки Начать обход / Отмена
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { isCreatingNew = false },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Назад")
                                }

                                Button(
                                    onClick = {
                                        val finalName = newZoneName.ifEmpty {
                                            if (selectedCategory == ObjectCategory.ENTRANCE_BUILDING) "Подъезд • Этаж $selectedFloor" else "Дворовая территория"
                                        }
                                        onStartNewPerimeter(finalName, selectedCategory, selectedFloor)
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                    modifier = Modifier.weight(1.5f)
                                ) {
                                    Text("Начать обход")
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
