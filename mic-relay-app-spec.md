# MicRelay — Phone-as-Mic + Video Capture App
### Architecture Spec v1

## 1. Core Concept

One Android app that owns the microphone exclusively, then fans that single
audio stream out to two places at once:

```
                 ┌────────────────────┐
   Mic Hardware →│   AudioRecord (Android)  │
                 └──────────┬─────────┘
                             │ raw PCM (48kHz/16-bit mono)
              ┌──────────────┴───────────────┐
              ▼                               ▼
     [Local Mux Path]                 [Network Stream Path]
     MediaCodec AAC +                 Opus/PCM → Socket →
     CameraX video →                  PC Receiver → Virtual
     local .mp4 file                  Audio Cable → OBS
```

Because your own app is the only thing touching `AudioRecord`, there's no
exclusivity conflict between "recording video" and "streaming mic" — both
just read from the same buffer.

## 2. Modes (user-selectable, per your requirement)

| Mode | What runs | Output |
|---|---|---|
| **Audio only** | AudioRecord → network stream | Live "mic" in OBS, nothing saved on phone |
| **Video only** | CameraX + AudioRecord → local muxed mp4 | Video file on phone, no network stream |
| **Both** | Both pipelines from the same AudioRecord instance | Live mic in OBS *and* local mp4 backup |

## 3. Transport Layer (WiFi + USB, both supported)

Build one `TransportInterface` with two implementations so the rest of the
app doesn't care which is active:

- **WiFi (LAN)**
  - Phone advertises itself via **NSD/mDNS** (`_micrelay._tcp`) — PC app
    auto-discovers it, no manual IP entry.
  - Audio sent over **UDP** (lower latency, tolerates dropped frames far
    better than audio glitching from TCP retransmits). Add a lightweight
    sequence-number header so the PC side can detect drops/reorder.
  - Optional Opus compression toggle — raw PCM if bandwidth's fine, Opus
    if WiFi is congested.

- **USB**
  - Use **USB tethering (RNDIS)**, not ADB. This is the public-release-friendly
    choice: it just creates a network interface over USB, so the *same*
    socket code as WiFi works — no "Enable USB debugging" requirement for
    end users. PC just sees a new virtual NIC at a known subnet.
  - Same UDP protocol runs over this link, just with a wired IP instead of
    WiFi's.

- **Runtime behavior:** try USB link first (lower latency, more stable for
  gaming/streaming), fall back to WiFi discovery if no USB tether detected.

## 4. Android App (Kotlin)

- **CameraX** for video capture (`VideoCapture` use case) — handles
  encoder complexity for you, still lets you swap in `Camera2` later if you
  need manual control (bitrate ladder, HDR, etc.)
- **AudioRecord** for raw mic capture — single instance, mode-gated consumers
- **Foreground Service** (required on Android 8+) with two foreground
  service types declared: `microphone` and `camera` (mandatory on Android 14+,
  Play Store will reject the build without this)
- **MediaMuxer + MediaCodec (AAC)** for muxing audio into the local mp4
  alongside the CameraX video track
- **NSD (Network Service Discovery)** for WiFi auto-pairing
- Settings via **DataStore**, streaming logic on **Coroutines/Flow**

**Permissions needed:** `RECORD_AUDIO`, `CAMERA`, `INTERNET`,
`ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (for mDNS),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`,
`FOREGROUND_SERVICE_CAMERA`.

## 5. PC App (Windows-first)

Recommend **C#/.NET (WPF or WinUI 3) + NAudio** for v1 — fastest path to a
polished installer, and NAudio has mature WASAPI support for writing into a
virtual audio device.

- **Virtual audio device:** don't try to ship your own signed audio driver
  for v1 (WDK + driver signing is a huge lift). Bundle **VB-Audio Virtual
  Cable** (free, redistributable with attribution) in your installer, or
  prompt a one-time install on first run. App writes decoded audio into
  "CABLE Input"; user points OBS at "CABLE Output".
- **Discovery UI:** shows found devices (USB tether / WiFi), connect button,
  live latency/buffer health indicator.
- **Jitter buffer:** small adaptive buffer (~40-80ms) to smooth UDP
  packet timing before writing to the virtual cable — this is the main
  thing that'll make or break perceived audio quality.

*(Cross-platform note: if you later want Mac support, BlackHole is the
equivalent virtual cable, and Tauri/Rust would let you share more code
between platforms than WPF would. Worth deferring past v1.)*

## 6. Play Store Considerations (since public release is the goal)

- **Privacy policy required** — any app requesting `CAMERA`/`RECORD_AUDIO`
  needs one, even for local-network-only use.
- **Prominent disclosure** dialog needed before first mic/camera use,
  explaining why (Google enforces this for sensitive permissions).
- **Foreground service types** must be declared and justified in the
  Play Console questionnaire — "microphone" and "camera" types have
  specific policy language you'll need to match.
- Local network access on Android 12+ needs `NEARBY_WIFI_DEVICES` handling
  depending on target SDK.

## 7. Suggested Build Order

1. **Phase 1** — Audio-only, WiFi only. Prove the AudioRecord → UDP →
   PC → VB-Cable → OBS path end-to-end. This is the riskiest technical
   piece; nail it first.
2. **Phase 2** — Add USB tethering transport alongside WiFi.
3. **Phase 3** — Add video-only mode (CameraX + local mux, no network).
4. **Phase 4** — Add "both" mode combining the two pipelines.
5. **Phase 5** — Polish: auto file transfer of recorded video to PC,
   reconnect handling, PC installer, Play Store compliance pass.

## 8. Open Design Question for Later

Local mp4 audio and the live-streamed PC audio come from the same source
but play out on different clocks (network latency vs. instant local write).
If you ever want to auto-sync the phone's video file with the OBS
recording in post, consider having the PC app embed a periodic timestamp
beacon back to the phone, or just rely on waveform sync in editing — much
simpler and good enough for v1.
