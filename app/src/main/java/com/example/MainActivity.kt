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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
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

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
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

            // 5. Нижняя панель действий (Завершить/Начать уборку + Навигационные контролы)
            CleanBottomControlSection(
                isTracking = uiState.trackerState.isTracking,
                onToggleCleaning = { viewModel.toggleCleaningSession() },
                onOpenLogs = { showLogsDialog = true },
                onOpenSettings = { showSettingsSheet = true }
            )
        }

        // Модальное окно калибровки и настроек PDR
        if (showSettingsSheet) {
            SettingsBottomSheet(
                config = pdrConfig,
                currentPosition = uiState.trackerState.currentPosition,
                onDismiss = { showSettingsSheet = false },
                onUpdateStepLength = { viewModel.updateStepLength(it) },
                onUpdateThreshold = { viewModel.updateStepThreshold(it) },
                onReset = { viewModel.resetPosition(0.0, 0.0, 0f) }
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
                text = "Cleaner Console",
                fontSize = 22.sp,
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
                    text = "wss://icv.dotozen.ru",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextMuted
                )
            }

            Text(
                text = "ID: ${userId.takeLast(12)}",
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
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
                            text = "Floor %02d • Zone Main".format(pos.floor),
                            fontSize = 13.sp,
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
                    label = "X-COORDINATE",
                    value = "%.2f".format(pos.x),
                    modifier = Modifier.weight(1f)
                )

                // Y-Coordinate
                CleanCoordinateBox(
                    label = "Y-COORDINATE",
                    value = "%.2f".format(pos.y),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun CleanCoordinateBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(CleanChipBg)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = value,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                    color = TextPrimary,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-0.5).sp
                )
                Text(
                    text = "m",
                    fontSize = 15.sp,
                    color = TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.padding(bottom = 3.dp)
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
    onToggleCleaning: () -> Unit,
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
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Вкладка Главная
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

            // Кнопка Логи WSS
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

            // Кнопка Настройки
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
 * Bottom Sheet для калибровки PDR алгоритма и сброса координат.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    config: PdrConfig,
    currentPosition: Position,
    onDismiss: () -> Unit,
    onUpdateStepLength: (Double) -> Unit,
    onUpdateThreshold: (Float) -> Unit,
    onReset: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var stepLen by remember { mutableFloatStateOf(config.stepLength.toFloat()) }
    var threshold by remember { mutableFloatStateOf(config.stepThreshold) }

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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Калибровка PDR и датчиков",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            // 1. Длина шага
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

            // 2. Порог акселерометра для детекции шага
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

            // 3. Статус WiFi модуля
            Surface(
                color = CleanChipBg,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = PrimaryBlue,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "WiFi Scanning Interface",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Заглушка готова (задел под Fingerprinting / трилатерацию)",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            // 4. Кнопки сброса координат
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
                    Text("Сброс в (0,0)")
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

