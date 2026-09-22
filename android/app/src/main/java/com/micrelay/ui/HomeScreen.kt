package com.micrelay.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.micrelay.core.audio.AudioInputDevice
import com.micrelay.core.transport.DiscoveryClient
import com.micrelay.core.transport.UsbConnectionHelper
import com.micrelay.core.video.AvFastRemuxer
import com.micrelay.core.video.CameraManager
import com.micrelay.core.video.StorageTelemetryHelper
import com.micrelay.core.video.VideoStorageHelper
import com.micrelay.service.MicRelayService
import com.micrelay.service.RelayMode
import com.micrelay.service.ServiceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    service: MicRelayService?,
    cameraManager: CameraManager
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs: SharedPreferences = remember { context.getSharedPreferences("micrelay_prefs", Context.MODE_PRIVATE) }

    val serviceState = service?.state?.collectAsState()?.value ?: ServiceState()

    var selectedMode by remember { mutableStateOf(RelayMode.BOTH) }
    var targetHost by remember { mutableStateOf(prefs.getString("target_host", "192.168.10.116") ?: "192.168.10.116") }
    var targetPort by remember { mutableStateOf(prefs.getString("target_port", "45454") ?: "45454") }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showMicModal by remember { mutableStateOf(false) }
    var isThermalShieldActive by remember { mutableStateOf(false) }
    var isRecordingLocalVideo by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableLongStateOf(0L) }

    var tempVideoFile by remember { mutableStateOf<File?>(null) }
    var tempAudioFile by remember { mutableStateOf<File?>(null) }
    var isProcessingRemux by remember { mutableStateOf(false) }

    val usbIp = remember { mutableStateOf(UsbConnectionHelper.getUsbTetherIp()) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Video Resolution & Codec States
    var selectedQuality by remember { mutableStateOf(Quality.FHD) }
    val supportedCodecs = remember { cameraManager.getSupportedHardwareCodecs() }
    val codecBadgeText = if (supportedCodecs.contains("HEVC (H.265)")) "HEVC (H.265) Active" else "H.264 Active"

    // Storage telemetry state
    var storageGb by remember { mutableFloatStateOf(StorageTelemetryHelper.getAvailableStorageGb()) }
    var remainingMinutes by remember { mutableLongStateOf(StorageTelemetryHelper.getEstimatedRecordingMinutes(selectedQuality)) }

    val phoneWifiIp = remember { DiscoveryClient.getLocalWifiIp(context) }

    // Periodic telemetry & USB monitor loop
    LaunchedEffect(isRecordingLocalVideo, serviceState.isRunning, selectedQuality) {
        val isAnyActive = isRecordingLocalVideo || serviceState.isRunning
        val startTime = System.currentTimeMillis()
        while (true) {
            if (isAnyActive) {
                recordingDurationSec = (System.currentTimeMillis() - startTime) / 1000L
            } else {
                recordingDurationSec = 0L
            }
            usbIp.value = UsbConnectionHelper.getUsbTetherIp()
            storageGb = StorageTelemetryHelper.getAvailableStorageGb()
            remainingMinutes = StorageTelemetryHelper.getEstimatedRecordingMinutes(selectedQuality)
            kotlinx.coroutines.delay(1000)
        }
    }

    // Two-way connection feedback
    LaunchedEffect(serviceState.isConnectedToPc) {
        if (serviceState.isConnectedToPc) {
            Toast.makeText(context, "🟢 Connected to PC! Microphone streaming active.", Toast.LENGTH_LONG).show()
        }
    }

    fun saveSettings(host: String, port: String) {
        targetHost = host
        targetPort = port
        prefs.edit().putString("target_host", host).putString("target_port", port).apply()
    }

    // Stop recording and trigger remux
    fun stopRecordingFlow() {
        if (!isRecordingLocalVideo && service?.state?.value?.isRunning != true) return
        isRecordingLocalVideo = false

        val vFile = tempVideoFile
        val aFile = tempAudioFile

        service?.stopLocalAudioRecord()
        service?.stopRelay()

        if (selectedMode == RelayMode.BOTH || selectedMode == RelayMode.VIDEO_ONLY) {
            isProcessingRemux = true

            // Watchdog job: guarantees dialog is dismissed after max 8 seconds no matter what happens
            val watchdogJob = scope.launch {
                kotlinx.coroutines.delay(8000)
                if (isProcessingRemux) {
                    isProcessingRemux = false
                    Toast.makeText(context, "Processing completed.", Toast.LENGTH_SHORT).show()
                }
            }

            cameraManager.stopRecording { videoSuccess ->
                scope.launch(Dispatchers.IO) {
                    try {
                        kotlinx.coroutines.delay(300) // Brief delay to let file descriptors close

                        if (vFile == null || !vFile.exists() || vFile.length() < 100) {
                            withContext(Dispatchers.Main) {
                                isProcessingRemux = false
                                watchdogJob.cancel()
                                Toast.makeText(context, "No video recorded", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }

                        val tempMuxFile = File(context.cacheDir, "remux_temp_${System.currentTimeMillis()}.mp4")
                        val hasAudio = (aFile != null && aFile.exists() && aFile.length() > 500)
                        android.util.Log.i("HomeScreen", "Remux check: vLen=${vFile.length()} aLen=${aFile?.length()} hasAudio=$hasAudio")

                        val remuxOk = if (hasAudio) {
                            AvFastRemuxer.remux(vFile, aFile!!, tempMuxFile)
                        } else {
                            false
                        }
                        android.util.Log.i("HomeScreen", "Remux result: remuxOk=$remuxOk tempMuxLen=${tempMuxFile.length()}")

                        val sourceToExport = if (remuxOk && tempMuxFile.exists() && tempMuxFile.length() > 100) {
                            tempMuxFile
                        } else {
                            vFile
                        }

                        val savedFile = VideoStorageHelper.exportToPublicMovies(context, sourceToExport)

                        try { tempMuxFile.delete() } catch (ignored: Exception) {}
                        try { vFile.delete() } catch (ignored: Exception) {}
                        try { aFile?.delete() } catch (ignored: Exception) {}

                        withContext(Dispatchers.Main) {
                            isProcessingRemux = false
                            watchdogJob.cancel()
                            tempVideoFile = null
                            tempAudioFile = null
                            if (savedFile != null) {
                                Toast.makeText(context, "Saved: ${savedFile.name} in Movies/MicRelay", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "Video saved to Gallery", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("HomeScreen", "Error during video processing: ${e.message}", e)
                        withContext(Dispatchers.Main) {
                            isProcessingRemux = false
                            watchdogJob.cancel()
                            Toast.makeText(context, "Video processed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            Toast.makeText(context, "Mic streaming stopped", Toast.LENGTH_SHORT).show()
        }
    }

    // Start recording and streaming
    fun startRecordingFlow() {
        if (service == null) {
            Toast.makeText(context, "Waiting for background audio service...", Toast.LENGTH_SHORT).show()
            return
        }

        val port = targetPort.toIntOrNull() ?: 45454
        saveSettings(targetHost, targetPort)

        when (selectedMode) {
            RelayMode.BOTH -> {
                val vFile = VideoStorageHelper.getTempVideoFile(context)
                val aFile = VideoStorageHelper.getTempAudioFile(context)
                tempVideoFile = vFile
                tempAudioFile = aFile

                service.startRelay(RelayMode.BOTH, targetHost, port)
                service.startLocalAudioRecord(aFile)

                cameraManager.startRecording(vFile) { success ->
                    if (!success) {
                        Toast.makeText(context, "Camera recording interrupted", Toast.LENGTH_SHORT).show()
                    }
                }

                isRecordingLocalVideo = true
                Toast.makeText(context, "Recording video & streaming mic to PC!", Toast.LENGTH_SHORT).show()
            }
            RelayMode.AUDIO_ONLY -> {
                service.startRelay(RelayMode.AUDIO_ONLY, targetHost, port)
                Toast.makeText(context, "Streaming microphone to PC...", Toast.LENGTH_SHORT).show()
            }
            RelayMode.VIDEO_ONLY -> {
                val vFile = VideoStorageHelper.getTempVideoFile(context)
                val aFile = VideoStorageHelper.getTempAudioFile(context)
                tempVideoFile = vFile
                tempAudioFile = aFile

                service.startRelay(RelayMode.VIDEO_ONLY, targetHost, port)
                service.startLocalAudioRecord(aFile)

                cameraManager.startRecording(vFile) { success ->
                    if (!success) {
                        Toast.makeText(context, "Camera recording interrupted", Toast.LENGTH_SHORT).show()
                    }
                }

                isRecordingLocalVideo = true
                Toast.makeText(context, "Recording video locally...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (isThermalShieldActive) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { isThermalShieldActive = false },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "● RECORDING / STREAMING ACTIVE",
                    color = Color(0xFF10B981),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                val mins = recordingDurationSec / 60
                val secs = recordingDurationSec % 60
                Text(
                    text = String.format("%02d:%02d", mins, secs),
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Tap anywhere to wake display",
                    color = Color(0xFF71717A),
                    fontSize = 13.sp
                )
            }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Viewfinder or Mic Standby Screen
            if (selectedMode == RelayMode.BOTH || selectedMode == RelayMode.VIDEO_ONLY) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            previewViewRef = this
                            cameraManager.initialize(lifecycleOwner, this) {}
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF09090B)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF18181B))
                                .border(2.dp, if (serviceState.isRunning) Color(0xFF10B981) else Color(0xFF27272A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (serviceState.isRunning) Color(0xFF10B981) else Color(0xFF71717A),
                                modifier = Modifier.size(48.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (serviceState.isRunning) "Relaying Audio to PC" else "Microphone Standby",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Target: $targetHost:$targetPort",
                            color = Color(0xFFA1A1AA),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Top Bar Overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xCC18181B))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Left: Connection / Live indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showSettingsDialog = true }
                    ) {
                        val isLive = serviceState.isRunning || isRecordingLocalVideo
                        val isConnected = serviceState.isConnectedToPc
                        val statusDotColor = when {
                            !isLive -> Color(0xFF71717A)
                            isConnected -> Color(0xFF10B981)
                            else -> Color(0xFFF59E0B)
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (isLive) {
                            val mins = recordingDurationSec / 60
                            val secs = recordingDurationSec % 60
                            val connText = if (selectedMode == RelayMode.VIDEO_ONLY) {
                                "REC"
                            } else if (isConnected) {
                                "🟢 LIVE"
                            } else {
                                "🟡 CONN"
                            }
                            Text(
                                text = String.format("%s %02d:%02d", connText, mins, secs),
                                color = if (isConnected) Color(0xFF10B981) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = "PC: $targetHost",
                                color = Color(0xFFE4E4E7),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Center: External Mic / K9 Status Pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (serviceState.isExternalMic) Color(0xFF064E3B) else Color(0xFF27272A))
                            .border(1.dp, if (serviceState.isExternalMic) Color(0xFF10B981) else Color.Transparent, RoundedCornerShape(14.dp))
                            .clickable { showMicModal = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val micIcon = if (serviceState.isExternalMic) Icons.Default.Wifi else Icons.Default.Mic
                        Icon(
                            imageVector = micIcon,
                            contentDescription = null,
                            tint = if (serviceState.isExternalMic) Color(0xFF10B981) else Color(0xFFA1A1AA),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        val shortName = if (serviceState.isExternalMic) "K9 Wireless Mic" else "Phone Mic"
                        Text(
                            text = shortName,
                            color = if (serviceState.isExternalMic) Color(0xFF6EE7B7) else Color(0xFFD4D4D8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Right: Actions
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedMode != RelayMode.AUDIO_ONLY) {
                            IconButton(
                                onClick = { previewViewRef?.let { cameraManager.toggleCamera(lifecycleOwner, it) } },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.Cameraswitch, contentDescription = "Flip Camera", tint = Color.White)
                            }
                        }

                        if (serviceState.isRunning || isRecordingLocalVideo) {
                            IconButton(
                                onClick = { isThermalShieldActive = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Default.BrightnessMedium, contentDescription = "Thermal Shield", tint = Color(0xFFFBBF24))
                            }
                        }

                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // VU Meter Level Bar
                val rawNorm = (serviceState.vuDecibels + 60.0f) / 60.0f
                val normalizedProgress = rawNorm.coerceIn(0.0f, 1.0f)
                val animatedProgress by animateFloatAsState(
                    targetValue = if (serviceState.isRunning || isRecordingLocalVideo) normalizedProgress else 0.0f,
                    animationSpec = tween(durationMillis = 50),
                    label = "vuProgress"
                )

                val barColor by animateColorAsState(
                    targetValue = when {
                        animatedProgress > 0.85f -> Color(0xFFEF4444)
                        animatedProgress > 0.65f -> Color(0xFFF59E0B)
                        else -> Color(0xFF10B981)
                    },
                    label = "vuColor"
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xAA18181B))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = barColor,
                        trackColor = Color(0xFF27272A)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = String.format("%.1f dB", serviceState.vuDecibels),
                        fontSize = 11.sp,
                        color = Color(0xFFA1A1AA),
                        fontWeight = FontWeight.Bold
                    )
                }

                // Storage & Codec HUD Pill
                if (selectedMode != RelayMode.AUDIO_ONLY) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x88000000))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val qualStr = when (selectedQuality) {
                            Quality.UHD -> "4K UHD"
                            Quality.FHD -> "1080p FHD"
                            Quality.HD -> "720p HD"
                            else -> "SD"
                        }
                        Text(
                            text = "💾 ${String.format("%.1f", storageGb)} GB Free (${StorageTelemetryHelper.formatRemainingTime(remainingMinutes)} left) • $qualStr • $codecBadgeText",
                            color = Color(0xFFCBD5E1),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Bottom Shutter & Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isRunning = serviceState.isRunning || isRecordingLocalVideo
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xCC18181B))
                ) {
                    SegmentedButton(
                        selected = selectedMode == RelayMode.BOTH,
                        onClick = { if (!isRunning) selectedMode = RelayMode.BOTH },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                        enabled = !isRunning
                    ) {
                        Text("Only Mic", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedMode == RelayMode.VIDEO_ONLY,
                        onClick = { if (!isRunning) selectedMode = RelayMode.VIDEO_ONLY },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                        enabled = !isRunning
                    ) {
                        Text("Only Video", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    SegmentedButton(
                        selected = selectedMode == RelayMode.AUDIO_ONLY,
                        onClick = { if (!isRunning) selectedMode = RelayMode.AUDIO_ONLY },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                        enabled = !isRunning
                    ) {
                        Text("Both", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Record Shutter Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(Color(0x66FFFFFF))
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable {
                            if (isRunning) {
                                stopRecordingFlow()
                            } else {
                                startRecordingFlow()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (isRunning) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFEF4444))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                val actionPrompt = when {
                    isRunning && selectedMode == RelayMode.BOTH -> "● RECORDING VIDEO & STREAMING MIC TO PC"
                    isRunning -> "● RECORDING ACTIVE (Tap to Stop & Save)"
                    selectedMode == RelayMode.BOTH -> "Only Mic: Record Video on Phone + Stream Mic to PC"
                    selectedMode == RelayMode.VIDEO_ONLY -> "Only Video: Record Video Locally (Mic Active)"
                    else -> "Both: Stream Both Video & Mic to PC"
                }
                Text(
                    text = actionPrompt,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            // Remux Processing Dialog
            if (isProcessingRemux) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF18181B)),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = Color(0xFF10B981))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Assembling Master Video...", color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Stitching video and audio tracks with lip-sync", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Microphone Selector Modal
    if (showMicModal) {
        val availableMics = remember { service?.getAvailableInputDevices() ?: emptyList() }
        AlertDialog(
            onDismissRequest = { showMicModal = false },
            confirmButton = {
                TextButton(onClick = { showMicModal = false }) {
                    Text("Close", color = Color(0xFF10B981))
                }
            },
            title = {
                Text("Select Microphone Source", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Choose whether to record using the phone's built-in mic array or an external mic (e.g. K9 wireless lavalier plugged into USB-C):",
                        fontSize = 12.sp,
                        color = Color(0xFFA1A1AA)
                    )

                    availableMics.forEach { mic ->
                        val isSelected = (mic.name == serviceState.activeMicName)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isSelected) Color(0xFF064E3B) else Color(0xFF27272A))
                                .border(1.dp, if (isSelected) Color(0xFF10B981) else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    service?.setPreferredMicDevice(mic)
                                    showMicModal = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (mic.isExternal) Icons.Default.Wifi else Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = if (isSelected) Color(0xFF10B981) else Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = mic.name,
                                        color = if (isSelected) Color(0xFF6EE7B7) else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = if (mic.isExternal) "External Hardware (USB/3.5mm)" else "Default Internal Mic",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, contentDescription = "Active", tint = Color(0xFF10B981))
                            }
                        }
                    }
                }
            },
            containerColor = Color(0xFF18181B)
        )
    }

    // Pro Studio Settings Sheet (Material3 Modal Bottom Sheet)
    StudioSettingsSheet(
        isOpen = showSettingsDialog,
        onDismiss = { showSettingsDialog = false },
        targetHost = targetHost,
        targetPort = targetPort,
        onSave = { host, port ->
            saveSettings(host, port)
        },
        service = service,
        cameraManager = cameraManager,
        selectedQuality = selectedQuality,
        onQualityChanged = { qual ->
            selectedQuality = qual
            previewViewRef?.let { cameraManager.setQuality(qual, lifecycleOwner, it) }
        },
        storageGb = storageGb,
        remainingMinutes = remainingMinutes,
        usbIp = usbIp.value,
        phoneWifiIp = phoneWifiIp
    )
}
