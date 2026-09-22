package com.micrelay.ui

import android.content.Context
import android.widget.Toast
import androidx.camera.video.Quality
import androidx.compose.animation.*
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micrelay.core.audio.AudioInputDevice
import com.micrelay.core.transport.DiscoveryClient
import com.micrelay.core.transport.UsbConnectionHelper
import com.micrelay.core.video.CameraManager
import com.micrelay.core.video.StorageTelemetryHelper
import com.micrelay.service.MicRelayService
import kotlinx.coroutines.launch

enum class SettingsTab(val title: String) {
    NETWORK("📡 Network"),
    AUDIO("🎙️ Audio & Mic"),
    VIDEO("📹 Video & Codec"),
    STORAGE("💾 Storage & System")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSettingsSheet(
    isOpen: Boolean,
    onDismiss: () -> Unit,
    targetHost: String,
    targetPort: String,
    onSave: (host: String, port: String) -> Unit,
    service: MicRelayService?,
    cameraManager: CameraManager,
    selectedQuality: Quality,
    onQualityChanged: (Quality) -> Unit,
    storageGb: Float,
    remainingMinutes: Long,
    usbIp: String?,
    phoneWifiIp: String?
) {
    if (!isOpen) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var tempHost by remember(targetHost) { mutableStateOf(targetHost) }
    var tempPort by remember(targetPort) { mutableStateOf(targetPort) }

    var activeTab by remember { mutableStateOf(SettingsTab.NETWORK) }
    var isScanningLan by remember { mutableStateOf(false) }
    var discoveryStatusMsg by remember { mutableStateOf<String?>(null) }
    var discoveredPcInfo by remember { mutableStateOf<Pair<String, String>?>(null) }

    val serviceState = service?.state?.collectAsState()?.value
    val availableMics = remember(service) { service?.getAvailableInputDevices() ?: emptyList() }
    val supportedCodecs = remember { cameraManager.getSupportedHardwareCodecs() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F1117),
        scrimColor = Color(0x99000000),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3F3F46))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 20.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Studio Settings",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF064E3B))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PRO",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                    Text(
                        text = "Configure PC connectivity, external mics, and 4K recording",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E222D))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Category Navigation Tabs
            ScrollableTabRow(
                selectedTabIndex = activeTab.ordinal,
                containerColor = Color.Transparent,
                contentColor = Color(0xFF10B981),
                edgePadding = 0.dp,
                divider = {},
                indicator = {}
            ) {
                SettingsTab.values().forEach { tab ->
                    val isSelected = activeTab == tab
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp, bottom = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFF10B981) else Color(0xFF1A1D27))
                            .clickable { activeTab = tab }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = tab.title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color(0xFF022C22) else Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            // Scrollable Settings Content Body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (activeTab) {
                    SettingsTab.NETWORK -> {
                        // --- SECTION 1: NETWORK & DISCOVERY ---

                        // 1. One-Tap LAN Auto-Discovery Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B26)),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF1E3A8A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Wifi,
                                            contentDescription = null,
                                            tint = Color(0xFF60A5FA),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Auto-Detect PC on Wi-Fi",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Finds your computer automatically without typing IPs",
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Button(
                                    onClick = {
                                        isScanningLan = true
                                        discoveryStatusMsg = "Scanning local network for PC Receiver..."
                                        scope.launch {
                                            val pc = DiscoveryClient.discoverPc()
                                            isScanningLan = false
                                            if (pc != null) {
                                                tempHost = pc.ip
                                                tempPort = pc.port.toString()
                                                discoveredPcInfo = Pair(pc.hostname, pc.ip)
                                                discoveryStatusMsg = "Found PC: ${pc.hostname} (${pc.ip})!"
                                                Toast.makeText(context, "Connected to ${pc.hostname}!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                discoveryStatusMsg = "No PC responded on UDP :45454. Ensure PC app is open."
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isScanningLan
                                ) {
                                    if (isScanningLan) {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Scanning Wi-Fi...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    } else {
                                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Scan for PC Now", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                if (discoveryStatusMsg != null) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = discoveryStatusMsg!!,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (discoveredPcInfo != null) Color(0xFF10B981) else Color(0xFFFBBF24)
                                    )
                                }
                            }
                        }

                        // 2. Subnet Diagnostics Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF272D3D))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Network Diagnostics", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                val isSameSubnet = phoneWifiIp != null && tempHost.substringBeforeLast(".") == phoneWifiIp.substringBeforeLast(".")
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("📱 Phone Local IP:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = phoneWifiIp ?: "Disconnected",
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("🖥️ Target PC IP:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = tempHost,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSameSubnet) Color(0xFF064E3B) else Color(0xFF451A03))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (isSameSubnet) "🟢 Both devices are on the same subnet (Ready)" else "⚠️ Warning: Different subnets. Ensure both devices are on the same Wi-Fi router!",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSameSubnet) Color(0xFF6EE7B7) else Color(0xFFFDE68A)
                                    )
                                }
                            }
                        }

                        // 3. Manual IP & Port Inputs
                        Text("Manual Connection Coordinates:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        OutlinedTextField(
                            value = tempHost,
                            onValueChange = { tempHost = it },
                            label = { Text("PC IP Address") },
                            leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null, tint = Color(0xFF38BDF8)) },
                            trailingIcon = {
                                if (tempHost.isNotEmpty()) {
                                    IconButton(onClick = { tempHost = "" }) {
                                        Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF64748B))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF10B981),
                                unfocusedBorderColor = Color(0xFF2A2F42),
                                focusedLabelColor = Color(0xFF10B981),
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            )
                        )

                        OutlinedTextField(
                            value = tempPort,
                            onValueChange = { tempPort = it },
                            label = { Text("UDP / TCP Port") },
                            leadingIcon = { Icon(Icons.Default.Tag, contentDescription = null, tint = Color(0xFF38BDF8)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF10B981),
                                unfocusedBorderColor = Color(0xFF2A2F42),
                                focusedLabelColor = Color(0xFF10B981),
                                unfocusedLabelColor = Color(0xFF94A3B8)
                            )
                        )

                        // 4. Quick Presets (High Contrast)
                        Text("Quick Connect Presets:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Preset 1: WiFi
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .border(1.dp, Color(0xFF3B82F6), RoundedCornerShape(12.dp))
                                    .clickable { tempHost = "192.168.10.116" }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("📶 Wi-Fi Preset", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("192.168.10.116", fontSize = 11.sp, color = Color(0xFF60A5FA), fontFamily = FontFamily.Monospace)
                                }
                            }

                            // Preset 2: USB Cable ADB
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF1E293B))
                                    .border(1.dp, Color(0xFF10B981), RoundedCornerShape(12.dp))
                                    .clickable { tempHost = "127.0.0.1" }
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("⚡ USB Cable ADB", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text("127.0.0.1 (Zero Latency)", fontSize = 11.sp, color = Color(0xFF34D399), fontFamily = FontFamily.Monospace)
                                }
                            }
                        }

                        // 5. USB Tethering Helper
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF272D3D))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Usb, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("USB Tethering Mode", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (usbIp != null) "🟢 Active: IP $usbIp" else "⚪ Inactive: Connect phone to PC via USB cable for 1ms latency.",
                                    fontSize = 11.sp,
                                    color = if (usbIp != null) Color(0xFF10B981) else Color(0xFF94A3B8)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(
                                    onClick = { UsbConnectionHelper.openTetheringSettings(context) },
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Text("Open Phone Tethering Settings ➔", fontSize = 12.sp, color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    SettingsTab.AUDIO -> {
                        // --- SECTION 2: AUDIO & MICROPHONE HARDWARE ---

                        Text("Microphone Hardware Selector:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("Select which physical microphone captures your voice:", fontSize = 11.sp, color = Color(0xFF94A3B8))

                        availableMics.forEach { mic ->
                            val isSelected = (mic.name == serviceState?.activeMicName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (isSelected) Color(0xFF064E3B) else Color(0xFF161922))
                                    .border(1.dp, if (isSelected) Color(0xFF10B981) else Color(0xFF272D3D), RoundedCornerShape(14.dp))
                                    .clickable {
                                        service?.setPreferredMicDevice(mic)
                                        Toast.makeText(context, "Switched to: ${mic.name}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) Color(0xFF047857) else Color(0xFF27272A)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (mic.isExternal) Icons.Default.Wifi else Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = if (isSelected) Color.White else Color(0xFFA1A1AA),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = mic.name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF6EE7B7) else Color.White
                                        )
                                        Text(
                                            text = if (mic.isExternal) "External Wireless/USB Dongle" else "Studio Internal Array",
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = Color(0xFF10B981))
                                }
                            }
                        }

                        // K9 Wireless Mic Auto-Detection Tip
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Plug your K9 wireless receiver dongle into the phone's USB-C port. MicRelay will detect it automatically and switch to 48kHz wireless audio on the fly.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("DSP Noise Suppression & Acoustics:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        // Hardware Noise Suppressor Switch Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF272D3D))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Studio Noise Suppression", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                    Text(
                                        text = "Hardware DSP filter. Eliminates air conditioning, PC fans, and room reverb without voice muffling.",
                                        fontSize = 11.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                                Switch(
                                    checked = serviceState?.isNoiseSuppressionActive == true,
                                    onCheckedChange = { service?.setNoiseSuppression(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF10B981)
                                    )
                                )
                            }
                        }

                        // Studio Broadcast Audio Specifications
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Broadcast Audio Pipeline Specs", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                                Text("• Sample Rate: 48,000 Hz (48 kHz Studio Standard)", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                                Text("• Bit Depth: 16-bit Linear PCM (Uncompressed to PC)", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                                Text("• Local Recording Format: 256 kbps Hardware AAC", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                                Text("• Clock Synchronization: Nanosecond Monotonic PTS Lip-Sync", fontSize = 11.sp, color = Color(0xFFCBD5E1))
                            }
                        }
                    }

                    SettingsTab.VIDEO -> {
                        // --- SECTION 3: VIDEO RECORDING & CODECS ---

                        Text("Video Resolution Preset:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        val resolutions = listOf(
                            Triple("4K Ultra HD", Quality.UHD, "3840 × 2160 • High Bitrate (~35 Mbps) • Ultimate Detail"),
                            Triple("1080p Full HD", Quality.FHD, "1920 × 1080 • Recommended (~14 Mbps) • Optimal"),
                            Triple("720p Standard HD", Quality.HD, "1280 × 720 • Lightweight (~6 Mbps) • Maximum Storage")
                        )

                        resolutions.forEach { (name, qual, desc) ->
                            val isSelected = (selectedQuality == qual)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) Color(0xFF064E3B) else Color(0xFF161922)
                                ),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF10B981) else Color(0xFF272D3D)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onQualityChanged(qual)
                                        Toast.makeText(context, "Resolution set to $name", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column {
                                        Text(
                                            text = name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF6EE7B7) else Color.White
                                        )
                                        Text(
                                            text = desc,
                                            fontSize = 11.sp,
                                            color = Color(0xFF94A3B8)
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Next-Gen Hardware Video Encoding:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        // Hardware Codec Info Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF272D3D))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Memory, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("HEVC / H.265 Hardware Acceleration", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                                Text(
                                    text = "Your phone hardware encodes video in HEVC (H.265). This delivers the exact same visual sharpness as H.264 while cutting the file size in half (50% storage savings).",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF1E293B))
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Supported Codecs on this Phone: ${supportedCodecs.joinToString(" • ")}",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }
                            }
                        }
                    }

                    SettingsTab.STORAGE -> {
                        // --- SECTION 4: STORAGE & TELEMETRY ---

                        Text("Device Storage Telemetry:", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF161922)),
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF272D3D))
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("💾 Available Storage:", fontSize = 13.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = "${String.format("%.1f", storageGb)} GB Free",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF10B981)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("⏱️ Est. Recording Time Left:", fontSize = 13.sp, color = Color(0xFF94A3B8))
                                    Text(
                                        text = StorageTelemetryHelper.formatRemainingTime(remainingMinutes),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF38BDF8)
                                    )
                                }

                                HorizontalDivider(color = Color(0xFF2A2F42), thickness = 1.dp)

                                Text(
                                    text = "📁 Video Storage Destination: Internal Storage / Movies / MicRelay",
                                    fontSize = 11.sp,
                                    color = Color(0xFF94A3B8)
                                )
                                Text(
                                    text = "All completed studio recordings are automatically saved to your phone's Gallery in pristine MP4 format with synchronized audio.",
                                    fontSize = 11.sp,
                                    color = Color(0xFFCBD5E1)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            // Bottom Sticky Save Button
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            ) {
                Button(
                    onClick = {
                        onSave(tempHost, tempPort)
                        onDismiss()
                        Toast.makeText(context, "Settings Applied & Saved!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF022C22))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Apply & Save Settings",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF022C22)
                    )
                }
            }
        }
    }
}
