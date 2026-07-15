package com.subcast.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import com.subcast.cast.CastPhase
import com.subcast.dlna.RendererDevice
import com.subcast.ui.CastViewModel
import com.subcast.subtitle.SubtitlePosition
import com.subcast.transcode.TranscodeMode

@Composable
fun CastScreen(vm: CastViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    if (state.showDevicePicker) {
        DevicePickerDialog(
            devices = state.devices,
            onPick = { vm.engine.pickDeviceAndCast(it) },
            onRefresh = { vm.engine.refreshDevices() },
            onDismiss = { vm.engine.dismissDevicePicker() },
        )
    }

    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.engine.setVideo(it, queryDisplayName(context, it)) }
    }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.engine.setSubtitle(it, queryDisplayName(context, it)) }
    }
    val secondaryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.engine.setSecondarySubtitle(it, queryDisplayName(context, it)) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("字幕投屏", style = MaterialTheme.typography.headlineSmall)

        // --- source ---
        SectionCard("视频源") {
            PickerRow(
                label = state.video?.name ?: "未选择视频",
                action = "选择视频",
                onClick = { videoPicker.launch(arrayOf("video/*")) }
            )
            Spacer(Modifier.height(8.dp))
            PickerRow(
                label = state.subtitle?.name ?: "主字幕（如中文，可选）",
                action = "选择"
            ) { subtitlePicker.launch(arrayOf("*/*")) }
            if (state.subtitle != null) {
                TextButton(onClick = { vm.engine.clearSubtitle() }) { Text("清除主字幕") }
            }
            PickerRow(
                label = state.secondarySubtitle?.name ?: "副字幕（如英文，可选·双语）",
                action = "选择"
            ) { secondaryPicker.launch(arrayOf("*/*")) }
            if (state.secondarySubtitle != null) {
                TextButton(onClick = { vm.engine.clearSecondarySubtitle() }) { Text("清除副字幕") }
            }
            Text(
                "同时选择主+副字幕即输出双语（上下两行显示）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // --- mode ---
        SectionCard("转码模式") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.mode == TranscodeMode.PRE_TRANSCODE,
                    onClick = { vm.engine.setMode(TranscodeMode.PRE_TRANSCODE) },
                    label = { Text("预转码（等完再投）") }
                )
                FilterChip(
                    selected = state.mode == TranscodeMode.STREAM,
                    onClick = { vm.engine.setMode(TranscodeMode.STREAM) },
                    label = { Text("实时流式") }
                )
            }
            Text(
                "实时流式无需等待，但高分辨率/老旧手机可能卡顿；预转码更稳定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // --- adjustments ---
        SectionCard("字幕调整") {
            val adj = state.adjustments
            LabeledSlider(
                label = "时间偏移 ${adj.syncOffsetMs / 1000.0}s",
                value = (adj.syncOffsetMs / 1000f),
                range = -60f..60f,
                onValue = { v -> vm.engine.setAdjustments(adj.copy(syncOffsetMs = (v * 1000).toLong())) }
            )
            LabeledSlider(
                label = "字号 ×${"%.1f".format(adj.fontSizeScale)}",
                value = adj.fontSizeScale,
                range = 0.5f..2.5f,
                onValue = { v -> vm.engine.setAdjustments(adj.copy(fontSizeScale = v)) }
            )
            Text("位置", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                POSITIONS.forEach { (pos, label) ->
                    FilterChip(
                        selected = adj.position == pos,
                        onClick = { vm.engine.setAdjustments(adj.copy(position = pos)) },
                        label = { Text(label) }
                    )
                }
            }
            Text("颜色", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                COLOR_CHOICES.forEach { c ->
                    val selected = adj.colorArgb == c.argb
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(c.argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            )
                            .clickable { vm.engine.setAdjustments(adj.copy(colorArgb = c.argb)) }
                    )
                }
            }
            // Live preview: proves the chosen color/size actually apply, on a TV-like bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF101010))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "字幕预览 Subtitle Preview",
                    color = Color(adj.colorArgb),
                    fontSize = (16f * adj.fontSizeScale).coerceIn(10f, 32f).sp,
                    fontWeight = FontWeight.Bold
                )
            }
            OutlinedTextField(
                value = adj.fontName ?: "",
                onValueChange = { v -> vm.engine.setAdjustments(adj.copy(fontName = v.ifBlank { null })) },
                label = { Text("字体名称（如 Arial / Microsoft YaHei，可空）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // --- start / status ---
        Button(
            onClick = { vm.engine.onStartCastClicked() },
            enabled = state.canStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.phase == CastPhase.CASTING) "投屏中" else "开始投屏")
        }

        when (state.phase) {
            CastPhase.TRANSCODING -> {
                Text(state.message ?: "处理中…")
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            CastPhase.PREPARING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(state.message ?: "准备中…")
                }
            }
            CastPhase.ERROR -> {
                Text(
                    "错误：${state.message ?: "未知"}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> {}
        }

        // --- playback controls ---
        if (state.phase == CastPhase.CASTING) {
            SectionCard("播放控制") {
                state.selectedDevice?.let {
                    Text("投屏到：${it.friendlyName}", style = MaterialTheme.typography.bodySmall)
                }
                val duration = state.durationMs.coerceAtLeast(1L)
                var seeking by remember { mutableStateOf(false) }
                var seekFrac by remember { mutableFloatStateOf(0f) }
                val frac = if (seeking) seekFrac else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                Text("状态：${state.playbackState.name}")
                Slider(
                    value = frac,
                    onValueChange = { seekFrac = it; seeking = true },
                    onValueChangeFinished = {
                        vm.engine.seekTo((seekFrac * duration).toLong())
                        seeking = false
                    }
                )
                Text("${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { vm.engine.pause() }) { Text("暂停") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = { vm.engine.play() }) { Text("播放") }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { vm.engine.stop() }) { Text("停止") }
                }
                Text("音量 ${state.volume}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = state.volume.toFloat(),
                    onValueChange = { vm.engine.setVolume(it.toInt()) },
                    valueRange = 0f..100f
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/**
 * On-demand device picker, shown after "开始投屏". Lists discovered renderers
 * live; tapping one selects it and kicks off the cast. Refresh re-fires the
 * SSDP M-SEARCH; cancel returns to the source screen without casting.
 */
@Composable
private fun DevicePickerDialog(
    devices: List<RendererDevice>,
    onPick: (RendererDevice) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择投屏设备") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (devices.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "正在搜索电视…\n请确保手机与电视在同一 WiFi",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    devices.forEach { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(d) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(d.friendlyName, modifier = Modifier.weight(1f))
                            Text("›", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh) { Text("重新搜索") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PickerRow(label: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onValue, valueRange = range)
    }
}

private data class ColorChoice(val name: String, val argb: Int)

private val COLOR_CHOICES = listOf(
    ColorChoice("白", 0xFFFFFFFF.toInt()),
    ColorChoice("黄", 0xFFFFFF00.toInt()),
    ColorChoice("青", 0xFF00FFFF.toInt()),
    ColorChoice("红", 0xFFFF0000.toInt()),
)

private val POSITIONS = listOf(
    SubtitlePosition.BOTTOM_CENTER to "底部居中",
    SubtitlePosition.TOP_CENTER to "顶部居中",
    SubtitlePosition.CENTER to "居中",
)

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3600
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

private fun queryDisplayName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else uri.lastPathSegment ?: "file" }
            ?: "file"
    }.getOrDefault("file")
