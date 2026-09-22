# MicRelay Wire Protocol Specification v1

## 1. Overview
MicRelay streams audio over UDP (and optionally TCP for ADB port forwarding) between an Android device (transmitter) and a PC receiver.
All multibyte integers are encoded in **Network Byte Order (Big-Endian)**.

---

## 2. Packet Structure

Each datagram packet contains a fixed **18-byte header** followed by a variable-length audio payload:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic: 0x4D 0x52        |  Version (1)  |  Payload Type |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                      Sequence Number (uint32)                 |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                      Timestamp Samples (uint64)               +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|      Payload Length (uint16)  |      Audio Payload Data...    |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
```

### Fields

| Field | Offset | Size (bytes) | Type | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Magic** | 0 | 2 | `[u8; 2]` | Fixed signature `0x4D 0x52` (`ASCII "MR"`). Drops malformed/alien packets. |
| **Version** | 2 | 1 | `u8` | Protocol version. Current is `0x01`. |
| **Payload Type** | 3 | 1 | `u8` | `0x01` = Opus (compressed), `0x02` = Linear PCM (16-bit LE, 48kHz mono), `0x00` = Heartbeat/Keepalive |
| **Sequence Number** | 4 | 4 | `uint32` | Monotonically increasing counter (wraps around). Used by Jitter Buffer for loss and reorder detection. |
| **Timestamp Samples** | 8 | 8 | `uint64` | Android presentation audio frame timestamp (cumulative sample count). Used for jitter measurement and clock drift compensation. |
| **Payload Length** | 16 | 2 | `uint16` | Length in bytes of the audio payload following immediately after the header. |
| **Payload Data** | 18 | `N` | `[u8; N]` | Encoded Opus frame or raw PCM samples. |

---

## 3. Audio Specifications

- **Sample Rate:** 48,000 Hz
- **Channels:** 1 (Mono)
- **Frame Duration:** 20 ms
  - Samples per frame: $48000 \times 0.02 = 960 \text{ samples}$
- **Payload Sizes:**
  - **Opus (`0x01`):** Variable, typically 40 – 160 bytes for 20ms at 16–64 kbps. Fits comfortably in single UDP datagram (< MTU 1500).
  - **Raw PCM (`0x02`):** 960 samples $\times$ 2 bytes = 1,920 bytes (if 20ms) or 960 bytes (if 10ms framing is selected).

---

## 4. Control & Discovery

### mDNS / DNS-SD Service
- **Service Type:** `_micrelay._udp.local.` (and `_micrelay._tcp.local.` for discovery)
- **Port:** `45454` (Default audio streaming port)
- **TXT Records:**
  - `v=1` (protocol version)
  - `dev=<Device Name>` (e.g. `Pixel 8 Pro`)
  - `mode=<audio|video|both>`
  - `codec=<opus|pcm>`

### Heartbeats / Keepalive
- If no audio is actively being captured (e.g. muted or paused), the transmitter sends a `Payload Type = 0x00` packet with `Payload Length = 0` every 1,000 ms to keep the NAT/firewall port mapping alive and signal connection health to the PC.
