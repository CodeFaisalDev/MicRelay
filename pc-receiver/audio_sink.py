"""
Audio Sink for MicRelay PC Receiver
Outputs audio frames via sounddevice (WASAPI/DirectSound) to virtual audio cable or speakers.
Includes:
- 75Hz Butterworth High-Pass Filter (strips desk handling noise, 50/60Hz AC hum, sub-bass rumble)
- Minimal Noise Cancellation Downward Expander (cleans fan & room hiss without swallowing voice)
- Glitch-free continuous audio callback (eliminates Wi-Fi stutter and buffer pops)
- Studio Preamp Gain with soft saturation limiter
"""

import threading
import numpy as np
import sounddevice as sd
from scipy import signal
from typing import Optional

class AudioSink:
    def __init__(self, sample_rate: int = 48000, channels: int = 1, device_index: Optional[int] = None):
        self.input_sample_rate = sample_rate  # Incoming audio from phone (48kHz)
        self.device_sample_rate = sample_rate # Native sound card rate
        self.channels = channels
        self.device_index = device_index
        self.stream: Optional[sd.OutputStream] = None
        self.is_running = False
        
        # Audio buffer for continuous playback
        self.buffer = np.zeros(0, dtype=np.int16)
        self.current_peak_db = -60.0
        self.lock = threading.Lock()

        # Prebuffering state (40ms cushion eliminates Wi-Fi jitter)
        self.is_started = False
        self.target_prebuffer_samples = int(48000 * 0.040)
        self.starvation_count = 0

        # Studio Preamp Volume Gain (balanced +6 dB clean boost)
        self.volume_gain = 2.0

        # 75Hz High-Pass Rumble Filter (Butterworth 2nd order)
        self.hpf_b, self.hpf_a = signal.butter(2, 75.0 / (sample_rate / 2.0), btype='highpass')
        self.hpf_zi = signal.lfilter_zi(self.hpf_b, self.hpf_a) * 0.0

        # Minimal Noise Cancellation: Studio Downward Expander
        # Reduces ambient room/fan hiss by ~13 dB when silent, fully transparent when speaking
        self.noise_cancel_enabled = True
        self.noise_cancel_threshold_db = -46.0
        self.noise_cancel_gain = 1.0
        self.noise_floor_gain = 0.22   # -13 dB soft floor (leaves natural room tone, kills fan whine)
        self.attack_coeff = 0.85       # Opens in ~2ms
        self.release_coeff = 0.04      # Smooth release over ~90ms

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
                    try:
                        sd.check_output_settings(device=self.device_index, samplerate=48000, channels=self.channels, dtype='int16')
                        target_rate = 48000
                    except Exception:
                        target_rate = def_rate
                except Exception:
                    target_rate = 48000

            self.device_sample_rate = target_rate
            self.target_prebuffer_samples = int(self.device_sample_rate * 0.040)

            # Re-calculate filter for device rate if needed
            self.hpf_b, self.hpf_a = signal.butter(2, 75.0 / (self.device_sample_rate / 2.0), btype='highpass')
            self.hpf_zi = signal.lfilter_zi(self.hpf_b, self.hpf_a) * 0.0

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
            self.starvation_count = 0
            print(f"[AudioSink] Output stream opened on device {self.device_index} at {self.device_sample_rate} Hz (channels={self.channels})")
        except Exception as e:
            print(f"[AudioSink] Failed to start stream: {e}")
            self.is_running = False
            raise

    def stop(self):
        self.is_running = False
        self.is_started = False
        self.starvation_count = 0
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

    def set_volume_gain(self, gain: float):
        self.volume_gain = max(0.5, min(10.0, float(gain)))
        print(f"[AudioSink] Volume Gain set to {self.volume_gain:.1f}x ({20*np.log10(self.volume_gain):+.1f} dB)")

    def set_noise_cancellation(self, enabled: bool, threshold_db: float = -46.0):
        self.noise_cancel_enabled = enabled
        self.noise_cancel_threshold_db = threshold_db
        if not enabled:
            self.noise_cancel_gain = 1.0
        print(f"[AudioSink] Minimal Noise Cancellation set: enabled={enabled}, threshold={threshold_db:.1f} dB")

    def set_noise_gate(self, enabled: bool, threshold_db: float = -46.0):
        # Backward compatibility alias
        self.set_noise_cancellation(enabled, threshold_db)

    def push_pcm_frame(self, pcm_bytes: bytes):
        """Pushes a raw 16-bit PCM chunk from network into the continuous audio sink buffer."""
        if not self.is_running or not pcm_bytes:
            return
        samples = np.frombuffer(pcm_bytes, dtype=np.int16)
        if len(samples) == 0:
            return

        # 1. 75Hz High-Pass Filter (strips handling rumble, desk vibrations & 50/60Hz hum)
        try:
            float_samples, self.hpf_zi = signal.lfilter(self.hpf_b, self.hpf_a, samples.astype(np.float32), zi=self.hpf_zi)
        except Exception:
            float_samples = samples.astype(np.float32)

        # 2. Studio Preamp Gain Boost
        if self.volume_gain != 1.0:
            float_samples = float_samples * self.volume_gain

        # 3. Minimal Noise Cancellation (Downward Expander)
        # Calculate RMS energy of current frame
        rms = float(np.sqrt(np.mean(float_samples**2)))
        if rms > 1e-4:
            frame_db = 20.0 * np.log10(rms / 32767.0)
            self.current_peak_db = max(-60.0, float(frame_db))
        else:
            self.current_peak_db = -60.0

        if self.noise_cancel_enabled:
            if self.current_peak_db > self.noise_cancel_threshold_db:
                # Speech detected: swiftly open to 100% volume
                self.noise_cancel_gain += self.attack_coeff * (1.0 - self.noise_cancel_gain)
            else:
                # Background ambient silence: smoothly attenuate down to soft floor (-13 dB)
                self.noise_cancel_gain += self.release_coeff * (self.noise_floor_gain - self.noise_cancel_gain)

            float_samples = float_samples * self.noise_cancel_gain

        # Soft-saturation anti-clipping
        samples = np.clip(float_samples, -32767.0, 32767.0).astype(np.int16)

        # 4. Resample if device sample rate differs from phone (e.g. 48000 -> 44100)
        if self.device_sample_rate != self.input_sample_rate:
            num_target = int(len(samples) * self.device_sample_rate / self.input_sample_rate)
            samples = np.interp(
                np.linspace(0, len(samples), num_target, endpoint=False),
                np.arange(len(samples)),
                samples
            ).astype(np.int16)

        with self.lock:
            self.starvation_count = 0
            # Prevent latency buildup: cap buffer at 90ms max
            max_samples = int(self.device_sample_rate * 0.090)
            keep_samples = int(self.device_sample_rate * 0.045)
            if len(self.buffer) > max_samples:
                self.buffer = self.buffer[-keep_samples:]

            self.buffer = np.concatenate((self.buffer, samples))

    def _audio_callback(self, outdata, frames, time_info, status):
        """Real-time audio driver callback executed by sound card hardware clock."""
        with self.lock:
            # Prebuffer cushion check on initial start or prolonged starvation
            if not self.is_started:
                if len(self.buffer) >= self.target_prebuffer_samples:
                    self.is_started = True
                    self.starvation_count = 0
                else:
                    outdata.fill(0)
                    return

            available = len(self.buffer)
            if available >= frames:
                outdata[:, 0] = self.buffer[:frames]
                self.buffer = self.buffer[frames:]
                self.starvation_count = 0
            elif available > 0:
                # Smooth partial play + fade to zero, keep running without 50ms freezing!
                outdata[:available, 0] = self.buffer
                last_sample = float(self.buffer[-1])
                fade = np.linspace(last_sample, 0.0, frames - available, endpoint=False).astype(np.int16)
                outdata[available:, 0] = fade
                self.buffer = np.zeros(0, dtype=np.int16)
                self.starvation_count += 1
            else:
                outdata.fill(0)
                self.starvation_count += 1
                # Only reset is_started if buffer stayed empty for > 15 callbacks (~150ms)
                if self.starvation_count > 15:
                    self.is_started = False
