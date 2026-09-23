"""Non-negative least squares, Lawson-Hanson, for the reed templates."""

from __future__ import annotations

import numpy as np


def nnls(a, b, maxiter=None):
    matrix = np.asarray(a, dtype=np.float64)
    target = np.asarray(b, dtype=np.float64).reshape(-1)
    rows, cols = matrix.shape
    solution = np.zeros(cols, dtype=np.float64)
    passive = np.zeros(cols, dtype=bool)
    limit = maxiter or max(cols * 3, 30)
    for _ in range(limit):
        residual = target - matrix @ solution
        gradient = matrix.T @ residual
        if np.all(passive) or float(np.max(np.where(passive, -np.inf, gradient))) <= 1e-10:
            break
        entering = int(np.argmax(np.where(passive, -np.inf, gradient)))
        passive[entering] = True
        for _inner in range(cols + 1):
            trial = np.zeros(cols, dtype=np.float64)
            columns = matrix[:, passive]
            fitted, *_rest = np.linalg.lstsq(columns, target, rcond=None)
            trial[passive] = fitted
            if np.all(trial[passive] >= -1e-12):
                solution = np.maximum(trial, 0.0)
                break
            blocked = np.where(passive & (trial < 0.0))[0]
            alpha = np.min(solution[blocked] / (solution[blocked] - trial[blocked]))
            solution = solution + alpha * (trial - solution)
            passive[solution <= 1e-12] = False
            solution[~passive] = 0.0
    leftover = target - matrix @ solution
    return solution, float(leftover @ leftover)
