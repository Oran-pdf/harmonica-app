"""Frame RMS matching librosa.feature.rms for a centered Hann-free power window."""

from __future__ import annotations

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view


def rms(y=None, S=None, frame_length: int = 2048, hop_length: int = 512, center: bool = True):
    audio = np.ascontiguousarray(y, dtype=np.float32).reshape(-1)
    if center:
        audio = np.pad(audio, (frame_length // 2, frame_length // 2), mode="constant")
    if audio.size < frame_length:
        audio = np.pad(audio, (0, frame_length - audio.size), mode="constant")
    count = 1 + (audio.size - frame_length) // hop_length
    frames = sliding_window_view(audio, frame_length)[::hop_length][:count]
    power = np.mean(frames.astype(np.float64) ** 2, axis=1)
    return np.sqrt(np.maximum(power, 0.0)).astype(np.float32)[None, :]
