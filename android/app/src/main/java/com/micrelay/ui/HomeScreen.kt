package com.micrelay.ui

import android.content.Context
import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.Toast
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.micrelay.core.transport.UsbConnectionHelper
import com.micrelay.core.video.AvFastRemuxer
import com.micrelay.core.video.CameraManager
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
    var isThermalShieldActive by remember { mutableStateOf(false) }
    var isRecordingLocalVideo by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableLongStateOf(0L) }

    var tempVideoFile by remember { mutableStateOf<File?>(null) }
    var tempAudioFile by remember { mutableStateOf<File?>(null) }
    var isProcessingRemux by remember { mutableStateOf(false) }

    val usbIp = remember { mutableStateOf(UsbConnectionHelper.getUsbTetherIp()) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Periodic USB interface scan & recording timer
    LaunchedEffect(isRecordingLocalVideo, serviceState.isRunning) {
        val isAnyActive = isRecordingLocalVideo || serviceState.isRunning
        if (isAnyActive) {
            val startTime = System.currentTimeMillis()
            while (isRecordingLocalVideo || serviceState.isRunning) {
                recordingDurationSec = (System.currentTimeMillis() - startTime) / 1000L
                usbIp.value = UsbConnectionHelper.getUsbTetherIp()
                kotlinx.coroutines.delay(1000)
            }
        } else {
            recordingDurationSec = 0L
        }
    }

    // Two-way connection feedback: Notify user as soon as PC handshake ACK is received
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

        // Stop background streaming and local audio recording
        service?.stopLocalAudioRecord()
        service?.stopRelay()

        if (selectedMode == RelayMode.BOTH || selectedMode == RelayMode.VIDEO_ONLY) {
            isProcessingRemux = true
            cameraManager.stopRecording { videoSuccess ->
                scope.launch(Dispatchers.IO) {
                    // Small delay to ensure all OS file buffers are closed
                    kotlinx.coroutines.delay(250)

                    if (vFile == null || !vFile.exists()) {
                        withContext(Dispatchers.Main) {
                            isProcessingRemux = false
                            Toast.makeText(context, "No video file found", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val finalMp4 = VideoStorageHelper.getOutputVideoFile(context)
                    val success = if (aFile != null && aFile.exists() && aFile.length() > 500) {
                        val remuxOk = AvFastRemuxer.remux(vFile, aFile, finalMp4)
                        if (!remuxOk) {
                            // Fallback: copy video file directly so user never loses recording
                            vFile.copyTo(finalMp4, overwrite = true)
                            true
                        } else {
                            true
                        }
                    } else {
                        vFile.copyTo(finalMp4, overwrite = true)
                        true
                    }

                    try { vFile.delete() } catch (ignored: Exception) {}
                    try { aFile?.delete() } catch (ignored: Exception) {}

                    withContext(Dispatchers.Main) {
                        isProcessingRemux = false
                        tempVideoFile = null
                        tempAudioFile = null
                        if (success) {
                            VideoStorageHelper.notifyMediaScanner(context, finalMp4)
                            Toast.makeText(context, "Saved: ${finalMp4.name} in Movies/MicRelay", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Error saving video file", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            // Pure mic stream mode
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
                // 1. Prepare temporary files
                val vFile = VideoStorageHelper.getTempVideoFile(context)
                val aFile = VideoStorageHelper.getTempAudioFile(context)
                tempVideoFile = vFile
                tempAudioFile = aFile

                // 2. Start hardware mic and network stream to PC
                service.startRelay(RelayMode.BOTH, targetHost, port)

                // 3. Start local AAC encoder
                service.startLocalAudioRecord(aFile)

                // 4. Start CameraX video capture
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
        // OLED Thermal Blackout Screen (Protects against battery drain and camera heating)
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
            // Background Viewfinder (Camera Preview) or Audio Visualizer
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
                // Mic-Only Dark Studio Visualizer
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

            // Top Bar Overlay (Floating translucent pill)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xCC18181B))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { showSettingsDialog = true }
                    ) {
                        val isLive = serviceState.isRunning || isRecordingLocalVideo
                        val isConnected = serviceState.isConnectedToPc
                        val statusDotColor = when {
                            !isLive -> Color(0xFF71717A)
                            isConnected -> Color(0xFF10B981) // Green
                            else -> Color(0xFFF59E0B) // Yellow / Connecting
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
                                "REC LOCAL"
                            } else if (isConnected) {
                                "🟢 LIVE MIC • REC"
                            } else {
                                "🟡 CONNECTING... REC"
                            }
                            Text(
                                text = String.format("%s %02d:%02d", connText, mins, secs),
                                color = if (isConnected) Color(0xFF10B981) else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = "PC: $targetHost:$targetPort",
                                color = Color(0xFFE4E4E7),
                                fontWeight = FontWeight.Medium,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedMode != RelayMode.AUDIO_ONLY) {
                            IconButton(
                                onClick = { previewViewRef?.let { cameraManager.toggleCamera(lifecycleOwner, it) } },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Cameraswitch, contentDescription = "Flip Camera", tint = Color.White)
                            }
                        }

                        if (serviceState.isRunning || isRecordingLocalVideo) {
                            IconButton(
                                onClick = { isThermalShieldActive = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.BrightnessMedium, contentDescription = "Thermal Shield", tint = Color(0xFFFBBF24))
                            }
                        }

                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Live Audio Level Bar on Top
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
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .weight(1f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
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
            }

            // Bottom Shutter & Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Mode Selector
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

                Spacer(modifier = Modifier.height(18.dp))

                // Big Camera Shutter Record Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
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
                        // Red rounded square (Stop)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFEF4444))
                        )
                    } else {
                        // Red circle (Record)
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFEF4444))
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                val actionPrompt = when {
                    isRunning && selectedMode == RelayMode.BOTH -> "● RECORDING VIDEO & STREAMING MIC TO PC (Tap to Stop & Save)"
                    isRunning -> "● RECORDING ACTIVE (Tap to Stop & Save)"
                    selectedMode == RelayMode.BOTH -> "Only Mic: Record Video on Phone + Stream Mic to PC"
                    selectedMode == RelayMode.VIDEO_ONLY -> "Only Video: Record Video Locally (Mic Active)"
                    else -> "Both: Stream Both Video & Mic to PC"
                }
                Text(
                    text = actionPrompt,
                    color = Color.White,
                    fontSize = 12.sp,
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
                            Text("Stitching video and audio tracks", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Connection & Settings Dialog
    if (showSettingsDialog) {
        var tempHost by remember { mutableStateOf(targetHost) }
        var tempPort by remember { mutableStateOf(targetPort) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        saveSettings(tempHost, tempPort)
                        showSettingsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                ) {
                    Text("Save & Close")
                }
            },
            title = {
                Text("Connection Settings", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Configure your PC's IP address to receive live microphone audio in OBS, Discord, or DAWs.",
                        fontSize = 12.sp,
                        color = Color(0xFFA1A1AA)
                    )

                    OutlinedTextField(
                        value = tempHost,
                        onValueChange = { tempHost = it },
                        label = { Text("PC IP Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = tempPort,
                        onValueChange = { tempPort = it },
                        label = { Text("UDP Port") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    // Quick buttons for known IPs
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { tempHost = "192.168.10.116" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use WiFi IP\n192.168.10.116", fontSize = 10.sp, textAlign = TextAlign.Center)
                        }
                        Button(
                            onClick = { tempHost = "127.0.0.1" },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27272A)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Use USB ADB\n127.0.0.1", fontSize = 10.sp, textAlign = TextAlign.Center)
                        }
                    }

                    // USB Tether Status & Guide
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF27272A)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = if (usbIp.value != null) "⚡ USB Tether: ${usbIp.value}" else "ℹ️ USB Tethering: Inactive",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (usbIp.value != null) Color(0xFF10B981) else Color(0xFFA1A1AA)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = { UsbConnectionHelper.openTetheringSettings(context) },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text("Open Phone Tethering Settings", fontSize = 11.sp, color = Color(0xFF38BDF8))
                            }
                        }
                    }
                }
            },
            containerColor = Color(0xFF18181B)
        )
    }
}
