# 🎙️ MicRelay

> Ultra-low latency studio microphone relay & synchronized 4K/FHD camera recorder for Android & Windows PC.

MicRelay turns your Android phone into an ultra-low latency wireless studio microphone for your Windows PC (usable in OBS Studio, Discord, NVIDIA Broadcast, DAWs, and games like WO Mic), while simultaneously recording local high-resolution video and audio directly to your phone's storage.

---

## 🌟 Key Features

- **Dual Action ("Only Mic" Mode):** Records crisp camera video locally on your phone (`Movies/MicRelay/VID_...mp4`) while simultaneously streaming live studio microphone audio to your PC with near-zero latency.
- **Broadcast-Grade Studio Audio:** 48,000 Hz 16-bit uncompressed PCM framing (10ms chunks, 978-byte packets) for bit-exact microphone capture with zero IP fragmentation over Wi-Fi.
- **Dual Transport:**
  - **Wi-Fi:** High-speed UDP streaming on port `45454`.
  - **USB Tethering / ADB:** Lossless wired TCP streaming via `adb forward tcp:45454 tcp:45454` (`127.0.0.1`).
- **Seamless OBS & NVIDIA Broadcast Integration:** Routes audio directly into virtual microphone devices (VB-Audio Virtual Cable) so Windows recognizes your phone as a native audio input device.
- **Hardware-Clocked Audio Engine:** Decoupled buffer architecture driven directly by Windows WASAPI hardware clocks to eliminate jitter, stutter, and audio gaps.
- **Automatic mDNS & Handshake:** Bidirectional ACK verification displaying instant live connection confirmation on both devices.

---

## 📁 Repository Structure

```
MicRelay/
├── android/                   # Android App (Kotlin, Jetpack Compose, CameraX, AudioRecord)
│   ├── app/src/main/java/com/micrelay/
│   │   ├── core/audio/        # AudioCaptureManager, RingBuffer, AacAudioEncoder
│   │   ├── core/transport/    # UdpTransport (Dual UDP/TCP), WireProtocol
│   │   ├── core/video/        # CameraManager, AvFastRemuxer, VideoStorageHelper
│   │   ├── service/           # MicRelayService (Foreground Audio Service)
│   │   └── ui/                # Camera Viewfinder HomeScreen, Jetpack Compose UI
├── pc-receiver/               # Windows PC Receiver (Python, CustomTkinter, SoundDevice)
│   ├── app_gui.py             # Native Dark Mode Windows GUI
│   ├── audio_sink.py          # WASAPI Low-Latency Prebuffered Audio Output
│   ├── udp_listener.py        # Dual UDP/TCP Network Listener
│   ├── protocol.py            # 18-byte MicRelay Binary Protocol Unpacker
│   ├── jitter_buffer.py       # Jitter Buffer & Packet Loss Concealment
│   └── drift_compensator.py   # Sample-level Clock Drift Compensation
├── tools/                     # Helper utilities and drivers
│   └── vbcable/               # VB-Audio Cable Virtual Microphone Driver Installer
├── docs/                      # Architectural and protocol specifications
│   └── spec/wire_protocol.md  # Binary wire specification (18-byte header)
├── tests/                     # Automated component & network integration tests
├── run_pc_receiver.bat        # 1-Click launcher for PC Receiver
├── allow_firewall.bat         # 1-Click Windows Firewall port 45454 unblocker
└── install_virtual_mic_driver.bat # 1-Click Virtual Microphone Driver setup
```

---

## 🚀 Quick Start Guide

### 1. PC Receiver Setup
1. Run `allow_firewall.bat` as Administrator (opens port 45454 in Windows Firewall).
2. (Optional) Run `install_virtual_mic_driver.bat` to install the virtual microphone driver for OBS / NVIDIA Broadcast.
3. Launch the PC Receiver:
   ```bash
   run_pc_receiver.bat
   ```
4. Select your audio device:
   - For listening directly: Select **Speakers**.
   - For OBS Studio / Discord / NVIDIA Broadcast: Select **CABLE Input**.
5. Click **Start Listening**.

### 2. Android Mobile App Setup
1. Build and install the APK (`./gradlew assembleDebug` or install `MicRelay-Latest.apk`).
2. Open **MicRelay** on your phone.
3. Tap **⚙️ Settings**, enter your PC's IP address (e.g., `192.168.10.116`), and tap **Save & Close**.
4. Select **`Only Mic`** mode (default).
5. Tap the big red **Record** button:
   - Your phone records local video to `Movies/MicRelay/`.
   - Your PC immediately receives your voice as a real-time microphone!
   - Both devices turn green showing `🟢 Connected`.

---

## ⚙️ Wire Protocol Specification

MicRelay uses a compact 18-byte binary packet header followed by raw audio payload:

| Offset | Type | Field | Description |
|---|---|---|---|
| 0..1 | `uint8[2]` | `0x4D 0x52` | Magic bytes ('MR') |
| 2 | `uint8` | Version | Protocol Version (1) |
| 3 | `uint8` | PayloadType | 0: Heartbeat, 1: Opus, 2: PCM, 3: Handshake, 4: ACK |
| 4..7 | `uint32` | Sequence | Sequential packet counter (big-endian) |
| 8..15 | `uint64` | Timestamp | Presentation timestamp (samples, big-endian) |
| 16..17 | `uint16` | PayloadLen | Length of subsequent payload bytes |
| 18..N | `bytes` | Payload | 16-bit 48kHz Little-Endian PCM audio data |

---

## 📄 License
MIT License. Created by [CodeFaisalDev](https://github.com/CodeFaisalDev).
