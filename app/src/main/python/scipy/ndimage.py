"""Median filter for the two windows the detector uses."""

from __future__ import annotations

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view


def median_filter(input, size=3, mode="reflect"):
    values = np.asarray(input)
    if np.isscalar(size):
        return _median_last_axis(values, int(size))
    width = int(size[-1])
    if values.ndim == 1 or width <= 1:
        return _median_last_axis(values, width)
    flat = values.reshape(-1, values.shape[-1])
    filtered = np.stack([_median_last_axis(row, width) for row in flat], axis=0)
    return filtered.reshape(values.shape)


def _median_last_axis(values, size):
    if size <= 1:
        return values.copy()
    pad = size // 2
    padded = np.pad(values, [(0, 0)] * (values.ndim - 1) + [(pad, pad)], mode="reflect")
    windows = sliding_window_view(padded, size, axis=-1)
    return np.median(windows, axis=-1)
