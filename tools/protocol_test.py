#!/usr/bin/env python3
"""
MicRelay Wire Protocol Validation & Audio Stream Simulator
Validates packet packing/unpacking and provides synthetic UDP packet streaming for testing.
"""

import struct
import socket
import time
import math
import sys

MAGIC = b'MR'  # 0x4D, 0x52
VERSION = 1
PAYLOAD_TYPE_HEARTBEAT = 0
PAYLOAD_TYPE_OPUS = 1
PAYLOAD_TYPE_PCM = 2

HEADER_FORMAT = '>2sBBIIH'  # magic(2), version(1), type(1), seq(4), ts_high(4), ts_low(4) -- wait, 64-bit TS is 8 bytes!
# Let's use '>2sBBIQH' -> magic(2s), version(B), type(B), seq(I), ts(Q: 8 bytes), length(H: 2 bytes) = 2+1+1+4+8+2 = 18 bytes!
HEADER_FORMAT_PACK = '>2sBBIQH'
HEADER_SIZE = struct.calcsize(HEADER_FORMAT_PACK)

class MicRelayPacket:
    def __init__(self, seq: int, timestamp: int, payload_type: int, payload: bytes):
        self.magic = MAGIC
        self.version = VERSION
        self.payload_type = payload_type
        self.seq = seq
        self.timestamp = timestamp
        self.payload = payload

    def serialize(self) -> bytes:
        header = struct.pack(
            HEADER_FORMAT_PACK,
            self.magic,
            self.version,
            self.payload_type,
            self.seq,
            self.timestamp,
            len(self.payload)
        )
        return header + self.payload

    @classmethod
    def deserialize(cls, data: bytes):
        if len(data) < HEADER_SIZE:
            raise ValueError(f"Packet too short: {len(data)} < {HEADER_SIZE}")
        magic, version, ptype, seq, ts, length = struct.unpack_from(HEADER_FORMAT_PACK, data, 0)
        if magic != MAGIC:
            raise ValueError(f"Invalid magic: {magic}")
        if version != VERSION:
            raise ValueError(f"Unsupported version: {version}")
        payload = data[HEADER_SIZE:HEADER_SIZE + length]
        if len(payload) != length:
            raise ValueError(f"Payload length mismatch: expected {length}, got {len(payload)}")
        return cls(seq, ts, ptype, payload)

def run_unit_tests():
    print("Running protocol serialization tests...")
    test_payload = b"\x01\x02\x03\x04\x05" * 20  # 100 bytes
    pkt = MicRelayPacket(seq=12345, timestamp=960000, payload_type=PAYLOAD_TYPE_OPUS, payload=test_payload)
    serialized = pkt.serialize()
    assert len(serialized) == HEADER_SIZE + len(test_payload), f"Expected {HEADER_SIZE + 100}, got {len(serialized)}"

    deserialized = MicRelayPacket.deserialize(serialized)
    assert deserialized.seq == 12345
    assert deserialized.timestamp == 960000
    assert deserialized.payload_type == PAYLOAD_TYPE_OPUS
    assert deserialized.payload == test_payload
    print(f"Header size: {HEADER_SIZE} bytes (Correct!)")
    print("Serialization & deserialization unit test PASSED.")

def simulate_sine_stream(target_host="127.0.0.1", target_port=45454, duration_sec=5):
    """
    Streams 48kHz 16-bit mono PCM sine wave (440Hz) in 10ms or 20ms chunks.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sample_rate = 48000
    freq = 440.0  # A4 tone
    frame_ms = 20  # 20ms = 960 samples
    samples_per_frame = int(sample_rate * (frame_ms / 1000.0))
    bytes_per_sample = 2  # 16-bit
    frame_bytes = samples_per_frame * bytes_per_sample

    print(f"Simulating stream to {target_host}:{target_port} for {duration_sec}s...")
    seq = 0
    total_samples = 0
    start_time = time.time()
    next_tick = start_time

    while time.time() - start_time < duration_sec:
        # Generate 16-bit PCM sine wave
        pcm_data = bytearray(frame_bytes)
        for i in range(samples_per_frame):
            sample_val = int(32767.0 * 0.5 * math.sin(2.0 * math.pi * freq * (total_samples + i) / sample_rate))
            struct.pack_into('<h', pcm_data, i * 2, sample_val)

        pkt = MicRelayPacket(
            seq=seq,
            timestamp=total_samples,
            payload_type=PAYLOAD_TYPE_PCM,
            payload=bytes(pcm_data)
        )
        sock.sendto(pkt.serialize(), (target_host, target_port))

        seq += 1
        total_samples += samples_per_frame
        next_tick += (frame_ms / 1000.0)
        sleep_dur = next_tick - time.time()
        if sleep_dur > 0:
            time.sleep(sleep_dur)

    print(f"Stream simulation finished. Sent {seq} frames ({total_samples} samples).")

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "send":
        simulate_sine_stream(duration_sec=float(sys.argv[2]) if len(sys.argv) > 2 else 5)
    else:
        run_unit_tests()
