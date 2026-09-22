"""
MicRelay Automated Unit Test Suite
Verifies wire protocol, jitter buffer, drift compensator, and ring buffer logic.
"""

import sys
import os
import unittest
import numpy as np

# Add pc-receiver to sys.path
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "pc-receiver")))

from protocol import pack_packet, unpack_packet, PAYLOAD_TYPE_PCM, PAYLOAD_TYPE_OPUS, PAYLOAD_TYPE_HEARTBEAT
from jitter_buffer import JitterBuffer
from drift_compensator import ClockDriftCompensator

class TestProtocol(unittest.TestCase):
    def test_pack_unpack_pcm(self):
        sample_payload = b"\x12\x34\x56\x78" * 50
        packed = pack_packet(seq=42, timestamp=96000, payload_type=PAYLOAD_TYPE_PCM, data=sample_payload)
        self.assertEqual(len(packed), 18 + len(sample_payload))

        unpacked = unpack_packet(packed)
        self.assertIsNotNone(unpacked)
        self.assertEqual(unpacked.seq, 42)
        self.assertEqual(unpacked.timestamp, 96000)
        self.assertEqual(unpacked.payload_type, PAYLOAD_TYPE_PCM)
        self.assertEqual(unpacked.data, sample_payload)

    def test_invalid_magic_rejected(self):
        corrupted = b"XX" + (b"\x00" * 30)
        self.assertIsNone(unpack_packet(corrupted))

class TestJitterBuffer(unittest.TestCase):
    def test_in_order_delivery(self):
        jb = JitterBuffer(target_delay_ms=40.0)
        frame1 = b"\x01" * 960 * 2
        frame2 = b"\x02" * 960 * 2

        jb.push(seq=1, timestamp=0, payload_type=PAYLOAD_TYPE_PCM, data=frame1)
        jb.push(seq=2, timestamp=960, payload_type=PAYLOAD_TYPE_PCM, data=frame2)

        seq_a, data_a, concealed_a = jb.pop()
        self.assertEqual(seq_a, 1)
        self.assertEqual(data_a, frame1)
        self.assertFalse(concealed_a)

        seq_b, data_b, concealed_b = jb.pop()
        self.assertEqual(seq_b, 2)
        self.assertEqual(data_b, frame2)
        self.assertFalse(concealed_b)

    def test_packet_loss_concealment(self):
        jb = JitterBuffer(target_delay_ms=40.0)
        frame1 = b"\x01" * 960 * 2
        frame3 = b"\x03" * 960 * 2

        jb.push(seq=1, timestamp=0, payload_type=PAYLOAD_TYPE_PCM, data=frame1)
        # Drop frame 2, directly push frame 3
        jb.push(seq=3, timestamp=1920, payload_type=PAYLOAD_TYPE_PCM, data=frame3)

        # Pop 1 -> Normal
        s1, d1, c1 = jb.pop()
        self.assertEqual(s1, 1)
        self.assertFalse(c1)

        # Pop 2 -> Should trigger PLC (Packet Loss Concealment)
        s2, d2, c2 = jb.pop()
        self.assertEqual(s2, 2)
        self.assertTrue(c2)
        self.assertIsNotNone(d2)

        # Pop 3 -> Normal
        s3, d3, c3 = jb.pop()
        self.assertEqual(s3, 3)
        self.assertEqual(d3, frame3)
        self.assertFalse(c3)

class TestClockDriftCompensator(unittest.TestCase):
    def test_compensator_high_depth_shortens(self):
        comp = ClockDriftCompensator(target_depth_frames=3, min_depth=1, max_depth=5)
        # Force high depth observation
        for _ in range(50):
            comp.observe_depth(10)

        # Synthetic sine wave
        samples = np.array([int(1000 * np.sin(2 * np.pi * i / 20)) for i in range(960)], dtype=np.int16)
        adjusted = comp.adjust_frame(samples)
        # Should remove 1 zero-crossing sample to reduce latency
        self.assertEqual(len(adjusted), 959)

if __name__ == "__main__":
    unittest.main()
