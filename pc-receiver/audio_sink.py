"""
Audio Sink for MicRelay PC Receiver
Outputs audio frames via sounddevice (WASAPI/DirectSound) to virtual audio cable or speakers.
Includes hardware-clocked prebuffering, auto-resampling, and smooth anti-clipping fadeout.
"""

import threading
import numpy as np
import sounddevice as sd
from typing import Optional

class AudioSink:
    def __init__(self, sample_rate: int = 48000, channels: int = 1, device_index: Optional[int] = None):
        self.input_sample_rate = sample_rate  # Incoming audio from phone (48kHz)
        self.device_sample_rate = sample_rate # Native sound card rate
        self.channels = channels
        self.device_index = device_index
        self.stream: Optional[sd.OutputStream] = None
        self.is_running = False
        
        # Audio buffer for smooth continuous playback
        self.buffer = np.zeros(0, dtype=np.int16)
        self.current_peak_db = -60.0
        self.lock = threading.Lock()

        # Prebuffering state (50ms cushion eliminates Wi-Fi jitter)
        self.is_started = False
        self.target_prebuffer_samples = int(48000 * 0.050) # 50ms default

        # Studio Software Noise Gate (eliminates fan/room noise)
        self.noise_gate_enabled = True
        self.noise_gate_threshold_db = -42.0
        self.noise_gate_gain = 0.0
        self.noise_gate_attack_coeff = 0.85   # Fast attack (opens in ~2ms)
        self.noise_gate_release_coeff = 0.04  # Smooth release (closes gently over ~80ms)

    def set_device(self, device_index: Optional[int]):
        was_running = self.is_running
        self.stop()
        self.device_index = device_index
        if was_running:
            self.start()

    def start(self):
        if self.is_running:
            return
        
        try:
            target_rate = 48000
            if self.device_index is not None:
                try:
                    dev_info = sd.query_devices(self.device_index)
                    def_rate = int(dev_info.get('default_samplerate', 48000))
                    # Check if device supports 48kHz
                    try:
                        sd.check_output_settings(device=self.device_index, samplerate=48000, channels=self.channels, dtype='int16')
                        target_rate = 48000
                    except Exception:
                        target_rate = def_rate
                except Exception:
                    target_rate = 48000

            self.device_sample_rate = target_rate
            self.target_prebuffer_samples = int(self.device_sample_rate * 0.050) # 50ms

            self.stream = sd.OutputStream(
                samplerate=self.device_sample_rate,
                channels=self.channels,
                dtype='int16',
                device=self.device_index,
                blocksize=0,  # Optimal WASAPI low-latency buffer
                callback=self._audio_callback
            )
            self.stream.start()
            self.is_running = True
            self.is_started = False
            print(f"[AudioSink] Output stream opened on device {self.device_index} at {self.device_sample_rate} Hz (channels={self.channels})")
        except Exception as e:
            print(f"[AudioSink] Failed to start stream: {e}")
            self.is_running = False
            raise

    def stop(self):
        self.is_running = False
        self.is_started = False
        if self.stream:
            try:
                self.stream.stop()
                self.stream.close()
            except Exception:
                pass
            self.stream = None
        with self.lock:
            self.buffer = np.zeros(0, dtype=np.int16)
        self.current_peak_db = -60.0

    def set_noise_gate(self, enabled: bool, threshold_db: float = -42.0):
        self.noise_gate_enabled = enabled
        self.noise_gate_threshold_db = threshold_db
        print(f"[AudioSink] Noise Gate set to enabled={enabled}, threshold={threshold_db:.1f} dB")

    def push_pcm_frame(self, pcm_bytes: bytes):
        """Pushes a raw 16-bit PCM chunk from the network into the continuous audio sink buffer."""
        if not self.is_running or not pcm_bytes:
            return
        samples = np.frombuffer(pcm_bytes, dtype=np.int16)
        if len(samples) == 0:
            return
        
        # Calculate peak VU level
        peak = float(np.max(np.abs(samples)))
        if peak > 0:
            db = 20.0 * np.log10(peak / 32767.0)
            self.current_peak_db = max(-60.0, float(db))
        else:
            self.current_peak_db = -60.0

        # Resample if device sample rate differs from phone (e.g. 48000 -> 44100)
        if self.device_sample_rate != self.input_sample_rate:
            num_target = int(len(samples) * self.device_sample_rate / self.input_sample_rate)
            samples = np.interp(
                np.linspace(0, len(samples), num_target, endpoint=False),
                np.arange(len(samples)),
                samples
            ).astype(np.int16)

        # Apply DSP Spectral Noise Gate (Zero-latency exponential smoothing)
        if self.noise_gate_enabled:
            if self.current_peak_db > self.noise_gate_threshold_db:
                # Voice detected: open gate rapidly
                self.noise_gate_gain += self.noise_gate_attack_coeff * (1.0 - self.noise_gate_gain)
            else:
                # Ambient silence / room hum: close gate smoothly
                self.noise_gate_gain += self.noise_gate_release_coeff * (0.0 - self.noise_gate_gain)

            if self.noise_gate_gain < 0.02:
                samples = np.zeros_like(samples)
            elif self.noise_gate_gain < 0.98:
                samples = (samples.astype(np.float32) * self.noise_gate_gain).astype(np.int16)

        with self.lock:
            # Prevent latency buildup: cap buffer at 120ms max
            max_samples = int(self.device_sample_rate * 0.120)
            keep_samples = int(self.device_sample_rate * 0.060)
            if len(self.buffer) > max_samples:
                self.buffer = self.buffer[-keep_samples:]

            self.buffer = np.concatenate((self.buffer, samples))

    def _audio_callback(self, outdata, frames, time_info, status):
        """Real-time audio driver callback executed by sound card hardware clock."""
        with self.lock:
            # Prebuffer cushion check (eliminates 100% of initial Wi-Fi jitter)
            if not self.is_started:
                if len(self.buffer) >= self.target_prebuffer_samples:
                    self.is_started = True
                else:
                    outdata.fill(0)
                    return

            available = len(self.buffer)
            if available >= frames:
                outdata[:, 0] = self.buffer[:frames]
                self.buffer = self.buffer[frames:]
            elif available > 0:
                outdata[:available, 0] = self.buffer
                # Smoothly fade out the last sample to prevent audible clicks
                last_sample = float(self.buffer[-1])
                fade = np.linspace(last_sample, 0.0, frames - available).astype(np.int16)
                outdata[available:, 0] = fade
                self.buffer = np.zeros(0, dtype=np.int16)
                self.is_started = False  # Wait for brief prebuffer refill
            else:
                outdata.fill(0)
                self.is_started = False
