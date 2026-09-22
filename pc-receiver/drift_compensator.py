"""
Dynamic Clock Drift Compensator for MicRelay
Maintains target jitter buffer depth across long streaming sessions by微-resampling.
"""

import numpy as np

class ClockDriftCompensator:
    def __init__(self, target_depth_frames: int = 3, min_depth: int = 1, max_depth: int = 6):
        self.target_depth_frames = target_depth_frames
        self.min_depth = min_depth
        self.max_depth = max_depth
        self.moving_avg_depth = float(target_depth_frames)
        self.alpha = 0.05  # Smoothing factor
        self.corrections_count = 0

    def observe_depth(self, current_depth: int):
        self.moving_avg_depth = (1.0 - self.alpha) * self.moving_avg_depth + self.alpha * current_depth

    def adjust_frame(self, pcm_samples: np.ndarray) -> np.ndarray:
        """
        pcm_samples: 1D int16 array of audio samples (e.g. 960 samples for 20ms).
        Returns adjusted pcm_samples (shortened by 1-2 samples if overflowing, lengthened if starving).
        """
        if len(pcm_samples) < 100:
            return pcm_samples

        # If buffer is consistently too high, remove 1-2 samples at zero-crossing
        if self.moving_avg_depth > self.max_depth:
            # Find zero crossing near middle
            mid = len(pcm_samples) // 2
            for i in range(mid - 50, mid + 50):
                if pcm_samples[i] * pcm_samples[i + 1] <= 0:
                    self.corrections_count += 1
                    return np.delete(pcm_samples, i)

        # If buffer is consistently too low, duplicate 1 sample at zero-crossing
        elif self.moving_avg_depth < self.min_depth:
            mid = len(pcm_samples) // 2
            for i in range(mid - 50, mid + 50):
                if pcm_samples[i] * pcm_samples[i + 1] <= 0:
                    self.corrections_count += 1
                    return np.insert(pcm_samples, i, pcm_samples[i])

        return pcm_samples
