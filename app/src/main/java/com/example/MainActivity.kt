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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
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
import com.example.model.ConnectionState
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
 * Главный экран приложения трекинга клинеров в стиле Clean Minimalism.
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

    // Управление фоновым сервисом для непрерывной работы при переключении в браузер
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
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Верхняя панель: Indoor Track v1.0, Cleaner Console, Socket status, ID
            CleanHeaderBar(
                userId = uiState.userId,
                cleanerName = uiState.cleanerName,
                connectionState = uiState.connectionState,
                onReconnect = { viewModel.reconnectSocket() },
                onOpenSettings = { showSettingsSheet = true }
            )

            // 2. Основная карточка текущего положения (Current Position)
            CleanPositionCard(
                uiState = uiState,
                onFloorChange = { newFloor -> viewModel.setFloor(newFloor) },
                onReset = { viewModel.resetPosition(0.0, 0.0, 0f) }
            )

            // 3. IMU Diagnostics & 2D Карта-холст
            CleanDiagnosticsAndMapCard(
                uiState = uiState,
                otherUsers = otherUsers,
                modifier = Modifier.weight(1f)
            )

            // 4. Карточка активной сессии (Dark Summary Pill)
            CleanActiveSessionCard(
                isTracking = uiState.trackerState.isTracking,
                durationSeconds = sessionDuration,
                packetsCount = uiState.trackerState.packetsSentCount
            )

            // 5. Нижняя панель действий (Завершить/Начать уборку + Симуляция + Логи + Настройки)
            CleanBottomControlSection(
                isTracking = uiState.trackerState.isTracking,
                isSimulating = isSimulating,
                onToggleCleaning = { viewModel.toggleCleaningSession() },
                onToggleSimulation = { viewModel.toggleSimulation() },
                onOpenLogs = { showLogsDialog = true },
                onOpenSettings = { showSettingsSheet = true }
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
 * Верхняя строка: Indoor Track v1.0, Заголовок, статус wss://icv.dotozen.ru и ID клинера.
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
                text = "INDOOR TRACK v1.0",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue,
                letterSpacing = 1.sp
            )
            Text(
                text = cleanerName.ifEmpty { "Cleaner Console" },
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
        }

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            // Сокет статус с зеленой пульсирующей точкой
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
 * Карточка текущего положения (Current Position) с координатами X, Y и переключателем этажей.
 */
@Composable
fun CleanPositionCard(
    uiState: TrackerUiState,
    onFloorChange: (Int) -> Unit,
    onReset: () -> Unit
) {
    val pos = uiState.trackerState.currentPosition

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("status_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Верхняя плашка карточки: иконка локации + этаж / зона
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrimaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "CURRENT POSITION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Этаж %d • Сайт [0..800, 0..600]".format(pos.floor),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = PrimaryBlue
                        )
                    }

                    // Компактные кнопки переключения этажа
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
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "Этаж вниз",
                                modifier = Modifier.size(12.dp),
                                tint = TextPrimary
                            )
                        }
                        IconButton(
                            onClick = { onFloorChange(pos.floor + 1) },
                            modifier = Modifier.size(26.dp)
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
            }

            // Сетка 2 колонок для координат X и Y
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // X-Coordinate
                CleanCoordinateBox(
                    label = "X (МЕТРЫ)",
                    value = "%.2f".format(pos.x),
                    subLabel = "Web: %.0f px".format(uiState.serverX),
                    modifier = Modifier.weight(1f)
                )

                // Y-Coordinate
                CleanCoordinateBox(
                    label = "Y (МЕТРЫ)",
                    value = "%.2f".format(pos.y),
                    subLabel = "Web: %.0f px".format(uiState.serverY),
                    modifier = Modifier.weight(1f)
                )
            }

            if (uiState.isSimulating) {
                Surface(
                    color = PrimaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(PrimaryBlue)
                        )
                        Text(
                            text = "Активна симуляция шагов: маркер перемещается на сайте",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = OnPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CleanCoordinateBox(
    label: String,
    value: String,
    subLabel: String = "",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CleanChipBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = value,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Light,
                    color = TextPrimary,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "m",
                    fontSize = 13.sp,
                    color = TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            if (subLabel.isNotEmpty()) {
                Text(
                    text = subLabel,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = PrimaryBlue,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/**
 * IMU Diagnostics & 2D Indoor Map Container.
 */
@Composable
fun CleanDiagnosticsAndMapCard(
    uiState: TrackerUiState,
    otherUsers: List<com.example.model.UserPosition>,
    modifier: Modifier = Modifier
) {
    val state = uiState.trackerState

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = CleanSurface),
        border = BorderStroke(1.dp, CleanBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Diagnostics Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IMU DIAGNOSTICS (PDR)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary,
                    letterSpacing = 0.5.sp
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFFE8F0FE))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (state.isTracking) "ACTIVE" else "STANDBY",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (state.isTracking) PrimaryBlue else TextSecondary
                    )
                }
            }

            // Metrics subrow
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Steps: ${state.stepCount}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
                Text(
                    text = "Dist: %.1f m".format(state.totalDistance),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
                Text(
                    text = "Heading: %.1f°".format(state.headingDegrees),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
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
                    otherUsers = otherUsers,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Карточка активной сессии (Dark Summary Pill) в стиле Clean Minimalism.
 */
@Composable
fun CleanActiveSessionCard(
    isTracking: Boolean,
    durationSeconds: Long,
    packetsCount: Long
) {
    val hours = durationSeconds / 3600
    val minutes = (durationSeconds % 3600) / 60
    val seconds = durationSeconds % 60
    val formattedTime = "%02d:%02d:%02d".format(hours, minutes, seconds)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(CleanDarkCard)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AccentGreen.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = AccentGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = "ACTIVE SESSION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.6f),
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = if (isTracking) formattedTime else "00:00:00",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "PACKETS SENT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.6f),
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "$packetsCount",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Нижняя строка действий: Кнопка Старт/Стоп и навигационные элементы.
 */
@Composable
fun CleanBottomControlSection(
    isTracking: Boolean,
    isSimulating: Boolean,
    onToggleCleaning: () -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Главная кнопка действия
        Button(
            onClick = onToggleCleaning,
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("toggle_cleaning_button")
        ) {
            Icon(
                imageVector = if (isTracking) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isTracking) "Завершить уборку" else "Начать уборку",
                fontSize = 16.sp,
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
            // 1. Вкладка Главная
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(PrimaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Главная",
                        tint = OnPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Главная",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }

            // 2. Кнопка Симуляция движения для теста сайта
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
                        .size(width = 44.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSimulating) PrimaryContainer else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isSimulating) Icons.Default.PauseCircle else Icons.Default.DirectionsWalk,
                        contentDescription = "Симуляция шагов",
                        tint = if (isSimulating) PrimaryBlue else TextSecondary,
                        modifier = Modifier.size(18.dp)
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

            // 3. Кнопка Логи WSS
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenLogs() }
                    .padding(2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "Логи WSS",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
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

            // 4. Кнопка Настройки
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
                        .size(width = 44.dp, height = 28.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Настройки",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
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

