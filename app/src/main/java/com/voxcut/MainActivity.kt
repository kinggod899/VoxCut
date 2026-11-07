package com.voxcut

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voxcut.engine.VoxAudioEngine
import com.voxcut.service.formatClock
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoxCutTheme {
                VoxCutScreen(viewModel = viewModel())
            }
        }
    }
}

@Composable
private fun VoxCutTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF4DD0A7),
        onPrimary = Color(0xFF06231C),
        secondary = Color(0xFFFFB74D),
        background = Color(0xFF0D1117),
        surface = Color(0xFF161B22),
        surfaceVariant = Color(0xFF21262D),
        onBackground = Color(0xFFE6EDF3),
        onSurface = Color(0xFFE6EDF3),
        error = Color(0xFFFF5252)
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun VoxCutScreen(viewModel: VoxViewModel) {
    val context = LocalContext.current
    val snapshot by viewModel.snapshot.collectAsState()
    val alive by viewModel.serviceAlive.collectAsState()
    val params by viewModel.params.collectAsState()
    val files by viewModel.files.collectAsState()
    val playingPath by viewModel.playingPath.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val fatalError by viewModel.fatalError.collectAsState()

    val requiredPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            viewModel.clearError()
            viewModel.startService()
        } else {
            viewModel.postError("需要麦克风权限才能启动声控监听")
        }
    }

    val startClick = {
        val micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            viewModel.clearError()
            viewModel.startService()
        } else {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "VOXCUT",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            letterSpacing = 3.sp
        )

        DashboardCard(
            db = snapshot.smoothedDb,
            thresholdDb = snapshot.thresholdDb,
            stateCode = snapshot.stateCode,
            isRunning = snapshot.isRunning
        )

        StatsCard(
            monitorMs = snapshot.monitorDurationMs,
            effectiveMs = snapshot.effectiveDurationMs,
            triggerCount = snapshot.triggerCount,
            savedPercent = snapshot.savedPercent
        )

        if (fatalError != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF3A1D1D)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = fatalError.orEmpty(),
                    color = Color(0xFFFF8A80),
                    modifier = Modifier.padding(12.dp),
                    fontSize = 13.sp
                )
            }
        }

        ParamsCard(
            params = params,
            onThreshold = { viewModel.setThresholdDb(it.toDouble()) },
            onPreRoll = { viewModel.setPreRollSeconds(it) },
            onHangover = { viewModel.setHangoverSeconds(it) },
            onGap = { viewModel.setGapSeconds(it) }
        )

        Button(
            onClick = { if (alive) viewModel.stopService() else startClick() },
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (alive) Color(0xFFB3261E) else MaterialTheme.colorScheme.primary,
                contentColor = if (alive) Color.White else Color(0xFF06231C)
            )
        ) {
            Text(
                text = if (alive) "停止并保存 WAV" else "开始声控监听",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "历史录音 (${files.size})",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold
        )

        if (files.isEmpty()) {
            Text(
                text = "暂无录音。点击上方按钮开始监听，有效声音将自动切片合成到单个 WAV 文件。",
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        } else {
            files.forEach { item ->
                RecordingRow(
                    item = item,
                    playing = playingPath == item.file.absolutePath && isPlaying,
                    onPlay = { viewModel.togglePlayback(item) },
                    onShare = { context.startActivity(viewModel.buildShareIntent(item)) },
                    onDelete = { viewModel.deleteRecording(item) }
                )
            }
        }
    }
}

@Composable
private fun DashboardCard(
    db: Double,
    thresholdDb: Double,
    stateCode: Int,
    isRunning: Boolean
) {
    val (label, badgeColor) = when (stateCode) {
        VoxAudioEngine.STATE_ACTIVE -> "ACTIVE 采集中" to Color(0xFF4DD0A7)
        VoxAudioEngine.STATE_HANGOVER -> "HANGOVER 尾音保护" to Color(0xFFFFB74D)
        else -> "IDLE 监听中" to Color(0xFF8B949E)
    }
    val barColor = when (stateCode) {
        VoxAudioEngine.STATE_ACTIVE -> Color(0xFF4DD0A7)
        VoxAudioEngine.STATE_HANGOVER -> Color(0xFFFFB74D)
        else -> Color(0xFF3B82F6)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "%.1f dB", db),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .background(badgeColor, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isRunning) label else "已停止",
                        color = Color(0xFF0D1117),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
            ) {
                val w = size.width
                val h = size.height
                drawRect(
                    color = Color(0xFF21262D),
                    topLeft = Offset.Zero,
                    size = Size(w, h)
                )
                val ratio = (db / 100.0).toFloat().coerceIn(0f, 1f)
                drawRect(
                    color = barColor,
                    topLeft = Offset.Zero,
                    size = Size(w * ratio, h)
                )
                // 红色警戒线：阈值在 0..100 dB 显示域上的位置。
                val tx = (thresholdDb / 100.0).toFloat().coerceIn(0f, 1f) * w
                drawLine(
                    color = Color(0xFFFF5252),
                    start = Offset(tx, 0f),
                    end = Offset(tx, h),
                    strokeWidth = 3.dp.toPx()
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0 dB", color = Color(0xFF8B949E), fontSize = 11.sp)
                Text(
                    "触发线 ${String.format(Locale.US, "%.0f", thresholdDb)} dB",
                    color = Color(0xFFFF8A80),
                    fontSize = 11.sp
                )
                Text("100 dB", color = Color(0xFF8B949E), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatsCard(
    monitorMs: Long,
    effectiveMs: Long,
    triggerCount: Int,
    savedPercent: Double
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                StatCell("已监听总时长", formatClock(monitorMs), Modifier.weight(1f))
                StatCell("有效保存时长", formatClock(effectiveMs), Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                StatCell("累计触发次数", "$triggerCount", Modifier.weight(1f))
                StatCell(
                    "压缩省流比",
                    String.format(Locale.US, "%.1f%%", savedPercent),
                    Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = Color(0xFF8B949E), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ParamsCard(
    params: com.voxcut.service.VoxParams,
    onThreshold: (Float) -> Unit,
    onPreRoll: (Float) -> Unit,
    onHangover: (Float) -> Unit,
    onGap: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "参数调节（监听中实时生效）",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            ParamSlider(
                "触发阈值 X",
                String.format(Locale.US, "%.0f dB", params.thresholdDb),
                params.thresholdDb.toFloat(), 20f..90f, onThreshold
            )
            ParamSlider(
                "前置预录 Pre-roll",
                String.format(Locale.US, "%.2f 秒", params.preRollMs / 1000f),
                params.preRollMs / 1000f, 0.1f..2.0f, onPreRoll
            )
            ParamSlider(
                "尾音保持 Hangover",
                String.format(Locale.US, "%.2f 秒", params.hangoverMs / 1000f),
                params.hangoverMs / 1000f, 0.5f..5.0f, onHangover
            )
            ParamSlider(
                "段间留白 Gap",
                String.format(Locale.US, "%.2f 秒", params.gapMs / 1000f),
                params.gapMs / 1000f, 0.0f..1.0f, onGap
            )
        }
    }
}

@Composable
private fun ParamSlider(
    label: String,
    display: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit
) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFC9D1D9), fontSize = 13.sp)
            Text(display, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color(0xFF30363D)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun RecordingRow(
    item: RecordingFile,
    playing: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                item.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatClock(item.durationMs)}  ·  ${formatFileSize(item.sizeBytes)}",
                color = Color(0xFF8B949E),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    contentPadding = ButtonDefaults.ContentPadding
                ) {
                    Text(if (playing) "暂停" else "试听", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onShare) {
                    Text("分享", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onDelete) {
                    Text("删除", fontSize = 12.sp, color = Color(0xFFFF8A80))
                }
            }
        }
    }
}
