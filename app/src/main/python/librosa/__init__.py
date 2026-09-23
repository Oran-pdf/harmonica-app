"""The pieces of librosa the reed detector actually calls."""

from __future__ import annotations

import wave

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

from . import feature


def fft_frequencies(sr: int = 22050, n_fft: int = 2048) -> np.ndarray:
    return np.fft.rfftfreq(int(n_fft), 1.0 / float(sr))


def frames_to_time(frames, sr: int = 22050, hop_length: int = 512, n_fft=None) -> np.ndarray:
    return np.asarray(frames, dtype=np.float64) * float(hop_length) / float(sr)


def stft(y, n_fft: int = 2048, hop_length: int | None = None, window: str = "hann", center: bool = True):
    if hop_length is None:
        hop_length = n_fft // 4
    audio = np.ascontiguousarray(y, dtype=np.float32).reshape(-1)
    if center:
        audio = np.pad(audio, (n_fft // 2, n_fft // 2), mode="constant")
    if audio.size < n_fft:
        audio = np.pad(audio, (0, n_fft - audio.size), mode="constant")
    win = np.hanning(n_fft).astype(np.float32)
    count = 1 + (audio.size - n_fft) // hop_length
    frames = sliding_window_view(audio, n_fft)[::hop_length][:count]
    return np.fft.rfft(frames * win, axis=1).T


def load(path, sr: int = 22050, mono: bool = True):
    with wave.open(str(path), "rb") as handle:
        file_sr = handle.getframerate()
        channels = handle.getnchannels()
        frames = handle.readframes(handle.getnframes())
    audio = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0
    if channels > 1:
        audio = audio.reshape(-1, channels)
        if mono:
            audio = audio.mean(axis=1)
    if sr is not None and file_sr != sr and audio.size:
        audio = _resample(audio, file_sr, sr)
    return np.ascontiguousarray(audio, dtype=np.float32), int(sr or file_sr)


def _resample(audio: np.ndarray, orig_sr: int, target_sr: int) -> np.ndarray:
    if audio.ndim > 1:
        return np.stack([_resample(audio[:, i], orig_sr, target_sr) for i in range(audio.shape[1])], axis=1)
    out_len = max(1, int(round(audio.size * float(target_sr) / float(orig_sr))))
    if orig_sr > target_sr:
        spectrum = np.fft.rfft(audio)
        keep = max(1, int(spectrum.size * float(target_sr) / float(orig_sr)))
        spectrum = spectrum[:keep]
        trimmed = np.fft.irfft(spectrum, n=audio.size).astype(np.float32)
    else:
        trimmed = audio
    old_t = np.linspace(0.0, 1.0, trimmed.size, endpoint=False)
    new_t = np.linspace(0.0, 1.0, out_len, endpoint=False)
    return np.interp(new_t, old_t, trimmed).astype(np.float32)
