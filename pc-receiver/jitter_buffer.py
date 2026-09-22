"""
Adaptive Jitter Buffer for MicRelay
Reorders UDP packets, handles network jitter, and performs packet loss concealment.
"""

import time
import collections
from typing import Optional, Tuple
import numpy as np

class JitterBuffer:
    def __init__(self, target_delay_ms: float = 60.0, frame_duration_ms: float = 20.0, sample_rate: int = 48000):
        self.target_delay_ms = target_delay_ms
        self.frame_duration_ms = frame_duration_ms
        self.sample_rate = sample_rate
        self.samples_per_frame = int(sample_rate * (frame_duration_ms / 1000.0))
        
        # Buffer holds mapping: seq -> (timestamp, payload_type, data)
        self.buffer = {}
        self.expected_seq = None
        self.last_pop_time = None
        
        # Statistics
        self.packets_received = 0
        self.packets_lost = 0
        self.packets_late = 0
        self.packets_reordered = 0
        self.jitter_ms = 0.0
        self.last_transit_time = None
        
        # Concealment buffer (last played samples for smooth fade out)
        self.last_good_samples = np.zeros(self.samples_per_frame, dtype=np.int16)

    def push(self, seq: int, timestamp: int, payload_type: int, data: bytes):
        now = time.time()
        self.packets_received += 1
        
        # Initial synchronization
        if self.expected_seq is None:
            self.expected_seq = seq
            self.last_pop_time = now
            
        # Reorder detection
        if seq < self.expected_seq:
            # Check for sequence wrap-around (uint32)
            if self.expected_seq - seq > 0x80000000:
                pass # Wrapped around
            else:
                self.packets_late += 1
                return # Discard late packet

        if seq in self.buffer:
            return # Duplicate

        self.buffer[seq] = (timestamp, payload_type, data)

    def pop(self) -> Tuple[int, Optional[bytes], bool]:
        """
        Pulls the next sequential audio frame.
        Returns: (seq, pcm_bytes, is_concealed)
        """
        if self.expected_seq is None:
            return (0, None, False)

        current_seq = self.expected_seq
        self.expected_seq = (self.expected_seq + 1) & 0xFFFFFFFF

        if current_seq in self.buffer:
            _, ptype, data = self.buffer.pop(current_seq)
            # Update concealment buffer
            if ptype == 2 and len(data) >= self.samples_per_frame * 2: # PCM
                try:
                    self.last_good_samples = np.frombuffer(data[:self.samples_per_frame * 2], dtype=np.int16).copy()
                except Exception:
                    pass
            return (current_seq, data, False)
        else:
            # Packet loss! Synthesize concealment frame
            self.packets_lost += 1
            concealed = self._generate_plc()
            return (current_seq, concealed, True)

    def _generate_plc(self) -> bytes:
        """Simple smooth attenuation packet loss concealment (decay last samples by 50%)."""
        decayed = (self.last_good_samples.astype(np.float32) * 0.5).astype(np.int16)
        self.last_good_samples = decayed
        return decayed.tobytes()

    def get_stats(self) -> dict:
        total = self.packets_received + self.packets_lost
        loss_rate = (self.packets_lost / total * 100.0) if total > 0 else 0.0
        return {
            "buffered_frames": len(self.buffer),
            "packets_received": self.packets_received,
            "packets_lost": self.packets_lost,
            "loss_rate_pct": loss_rate,
            "packets_late": self.packets_late
        }
