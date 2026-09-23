"""Reed templates from the isolated-note dictionary, used to identify chords."""

from __future__ import annotations

from pathlib import Path

import librosa
import numpy as np

from harmonica_annotator.decoder import RawNote
from harmonica_annotator.layout import C_BLOW, C_DRAW, HarpLayout

SR = 22050
N_FFT = 4096
HOP = 256
FMIN = 160.0
FMAX = 5200.0
N_HOLES = 10
BREATHS = ("draw", "blow")

DATA_PATH = Path(__file__).resolve().parent / "data" / "reed_templates.npz"


def _band_slice(n_bins: int | None = None) -> tuple[int, int, np.ndarray]:
    freqs = librosa.fft_frequencies(sr=SR, n_fft=N_FFT)
    lo = int(np.searchsorted(freqs, FMIN))
    hi = int(np.searchsorted(freqs, FMAX))
    return lo, hi, freqs[lo:hi]


def _magnitude_spec(audio: np.ndarray) -> np.ndarray:
    spec = np.abs(librosa.stft(audio, n_fft=N_FFT, hop_length=HOP, window="hann"))
    lo, hi, _ = _band_slice()
    return spec[lo:hi]


def _l2norm(vec: np.ndarray) -> np.ndarray:
    n = float(np.linalg.norm(vec))
    if n < 1e-12:
        return vec
    return vec / n


def _rms_frames(audio: np.ndarray) -> np.ndarray:
    rms = librosa.feature.rms(y=audio, hop_length=HOP, frame_length=N_FFT)[0]
    return np.maximum(rms, 0.0)


def _core_frames(n_frames: int, t0: float, t1: float, rms: np.ndarray, times: np.ndarray) -> np.ndarray:
    """Frames that are actually sounding inside a labeled window."""
    i0 = int(np.searchsorted(times, t0))
    i1 = int(np.searchsorted(times, t1))
    i0 = max(0, min(n_frames - 1, i0))
    i1 = max(i0 + 1, min(n_frames, i1))
    sl = rms[i0:i1]
    if sl.size == 0 or float(np.max(sl)) <= 1e-8:
        return np.arange(i0, i1)
    thresh = max(float(np.max(sl)) * 0.28, 1e-8)
    on = np.where(sl >= thresh)[0]
    if on.size < 3:
        return np.arange(i0, i1)
    a, b = int(on[0]), int(on[-1]) + 1
    trim = max(1, int((b - a) * 0.18))
    a2, b2 = a + trim, max(a + trim + 1, b - trim)
    return np.arange(i0 + a2, i0 + b2)


def hud_runs_from_baseline(baseline: dict) -> list[tuple[float, float, int, str]]:
    raw: list[tuple[float, float, tuple[int, ...], str]] = []
    prev: tuple[tuple[int, ...], str] | None = None
    t0 = 0.0
    last_t = 0.0
    dt = 1.0 / float(baseline.get("fps") or 30.0)
    for f in baseline["frames"]:
        last_t = float(f["t"])
        holes = tuple(sorted({int(s["hole"]) for s in f["squares"]}))
        sides = {s["side"] for s in f["squares"]}
        breath = "draw" if "below" in sides else ("blow" if "above" in sides else "")
        key = (holes, breath)
        if key != prev:
            if prev and prev[0]:
                raw.append((t0, last_t, prev[0], prev[1]))
            prev = key
            t0 = last_t
    if prev and prev[0]:
        raw.append((t0, last_t + dt, prev[0], prev[1]))
    out: list[tuple[float, float, int, str]] = []
    for a, b, holes, breath in raw:
        if len(holes) == 1:
            out.append((a, b, holes[0], breath))
    return out


def rms_note_windows(audio: np.ndarray, n_notes: int = 20) -> list[tuple[float, float]]:
    rms = _rms_frames(audio)
    times = librosa.frames_to_time(np.arange(len(rms)), sr=SR, hop_length=HOP)
    thresh = max(float(np.max(rms)) * 0.16, 1e-8)
    active = rms >= thresh
    windows: list[tuple[float, float]] = []
    i = 0
    n = len(active)
    min_len = int(0.16 * SR / HOP)
    while i < n:
        if active[i]:
            j = i
            while j < n and active[j]:
                j += 1
            if j - i >= min_len:
                windows.append((float(times[i]), float(times[min(j, n - 1)])))
            i = j
        else:
            i += 1
    if len(windows) > n_notes:
        windows = sorted(windows, key=lambda w: w[1] - w[0], reverse=True)[:n_notes]
        windows.sort()
    return windows


def extract_bank(audio: np.ndarray, labeled: list[tuple[float, float, int, str]]) -> np.ndarray:
    """Return (2, 10, n_bins) bank: index 0=draw, 1=blow."""
    spec = _magnitude_spec(audio)
    rms = _rms_frames(audio)
    times = librosa.frames_to_time(np.arange(spec.shape[1]), sr=SR, hop_length=HOP)
    n_bins = spec.shape[0]
    bank = np.zeros((2, N_HOLES, n_bins), dtype=np.float32)
    for t0, t1, hole, breath in labeled:
        if hole < 1 or hole > 10 or breath not in BREATHS:
            continue
        idx = _core_frames(spec.shape[1], t0, t1, rms, times)
        if idx.size == 0:
            continue
        vec = np.median(spec[:, idx], axis=1)
        bank[BREATHS.index(breath), hole - 1] = _l2norm(vec).astype(np.float32)
    return bank


def transpose_bank(bank: np.ndarray, semitones: int) -> np.ndarray:
    if semitones == 0:
        return bank
    _lo, _hi, freqs = _band_slice()
    ratio = 2.0 ** (semitones / 12.0)
    out = np.zeros_like(bank)
    sample_at = freqs / ratio
    for bi in range(bank.shape[0]):
        for hi in range(bank.shape[1]):
            out[bi, hi] = np.interp(sample_at, freqs, bank[bi, hi], left=0.0, right=0.0)
            out[bi, hi] = _l2norm(out[bi, hi])
    return out


def legal_combos() -> list[tuple[int, tuple[int, ...]]]:
    """(breath_index, holes 1-based)."""
    combos: list[tuple[int, tuple[int, ...]]] = []
    for bi in (0, 1):
        for n in (1, 2, 3):
            for start in range(1, N_HOLES - n + 2):
                combos.append((bi, tuple(range(start, start + n))))
    return combos


def _midi_list(offset: int = 0) -> list[int]:
    return [m + offset for m in (*C_DRAW, *C_BLOW)]


def _band_energy_matrix(spec: np.ndarray, freqs: np.ndarray, midis: list[int], cents: float = 45.0) -> np.ndarray:
    """Return (n_midis, n_frames) peak magnitude in a cents window around each midi."""
    out = np.zeros((len(midis), spec.shape[1]), dtype=np.float32)
    safe = np.maximum(freqs, 1.0)
    log_safe = np.log2(safe)
    for i, midi in enumerate(midis):
        freq = 440.0 * (2.0 ** ((midi - 69.0) / 12.0))
        cents_arr = 1200.0 * (log_safe - np.log2(max(freq, 1.0)))
        mask = np.abs(cents_arr) <= cents
        if not np.any(mask):
            idx = int(np.argmin(np.abs(freqs - freq)))
            out[i] = spec[idx]
        else:
            out[i] = spec[mask].max(axis=0)
    return out


def _template_matrix(bank: np.ndarray, freqs: np.ndarray, midis: list[int]) -> np.ndarray:
    """20 x 20 matrix: columns are L2-normalized draw1-10 then blow1-10 band templates."""
    cols = []
    for bi in range(2):
        for hole in range(N_HOLES):
            vec = _band_energy_matrix(bank[bi, hole][:, None], freqs, midis)[:, 0]
            cols.append(_l2norm(vec))
    return np.stack(cols, axis=1).astype(np.float32)


def _reassign_unison(x: np.ndarray) -> np.ndarray:
    """Draw 2 and blow 3 share a pitch; give it to the breath with active neighbors."""
    y = x.copy()
    blow_nb = float(y[10 + 1] + y[10 + 3])  # blow 2 and 4
    draw_nb = float(y[0] + y[2])  # draw 1 and 3
    if blow_nb >= draw_nb and blow_nb > 0.12:
        y[12] += y[1]
        y[1] = 0.0
    elif draw_nb > 0.12:
        y[1] += y[12]
        y[12] = 0.0
    elif y[12] >= y[1]:
        y[12] += y[1]
        y[1] = 0.0
    else:
        y[1] += y[12]
        y[12] = 0.0
    # A lone draw 2 with a blow-6 partial is blow 3. Blow 6 is the octave of
    # blow 3, and a real draw 2 carries draw 8 instead.
    if y[1] > 0.0 and y[12] == 0.0 and float(y[1]) >= 0.40 and float(y[1]) >= float(np.max(y)) * 0.80:
        blow6 = float(x[15])
        draw8 = float(x[7])
        other_blow = max(float(y[10 + i]) for i in range(N_HOLES) if i != 5)
        if blow6 >= 0.25 and blow6 >= draw8 * 0.80 and other_blow < 0.15:
            y[12] = y[1]
            y[1] = 0.0
            y[15] = 0.0
    return y


def _suppress_overtone_acts(x: np.ndarray, midis: list[int]) -> np.ndarray:
    """Zero +12/+19/… of any sounding reed, including the other breath."""
    y = x.copy()
    peak = float(np.max(y)) or 1.0
    order = np.argsort(np.asarray(midis))
    for i in order:
        if y[i] < 0.10 * peak:
            continue
        mi = midis[int(i)]
        for j, mj in enumerate(midis):
            if j == int(i) or y[j] <= 0:
                continue
            interval = mj - mi
            if interval not in {12, 19, 24, 28, 31, 36}:
                continue
            # Keep a high clustered hole when the "fundamental" is just a leaky neighbor
            # (blow 7 into 8-9-10). Still zero true overtones of a distant lower reed.
            hole_i = int(i) % N_HOLES
            hole_j = j % N_HOLES
            same_breath = (int(i) // N_HOLES) == (j // N_HOLES)
            if (
                same_breath
                and abs(hole_j - hole_i) <= 3
                and y[j] > y[i] * 1.15
                and _has_loud_neighbor(j, y, 0.22 * peak)
            ):
                continue
            y[j] = 0.0
    return y


def _has_loud_neighbor(index: int, acts: np.ndarray, thresh: float) -> bool:
    hole = index % N_HOLES
    base = index - hole
    for delta in (-1, 1):
        other = hole + delta
        if 0 <= other < N_HOLES and float(acts[base + other]) >= thresh:
            return True
    return False


def _best_window(acts: np.ndarray) -> tuple[int, ...] | None:
    peak = float(np.max(acts)) if acts.size else 0.0
    if peak < 0.10:
        return None
    best: tuple[int, ...] | None = None
    best_score = -1.0
    for n in (1, 2, 3):
        floor = 0.13 * peak if n == 3 else (0.18 * peak if n == 2 else 0.12 * peak)
        for start in range(0, N_HOLES - n + 1):
            w = acts[start : start + n]
            if float(np.min(w)) < floor:
                continue
            score = float(np.sum(w)) + 0.05 * (n - 1)
            if score > best_score:
                best_score = score
                best = tuple(range(start + 1, start + 1 + n))
    return best


def _window_quality(acts: np.ndarray, holes: tuple[int, ...] | None) -> float:
    if not holes:
        return -1.0
    energy = float(sum(float(acts[h - 1]) for h in holes))
    if energy < 0.10:
        return -1.0
    return energy + 0.05 * (len(holes) - 1)


def _cosine_reed(spec_col: np.ndarray, reed_mat: np.ndarray) -> tuple[str, int, float]:
    sims = reed_mat.T @ spec_col
    k = int(np.argmax(sims))
    return BREATHS[k // N_HOLES], (k % N_HOLES) + 1, float(sims[k])


def _octave_pair(acts: np.ndarray, cos: np.ndarray | None) -> tuple[int, int] | None:
    """Lowest blow/draw octave split (holes 3 apart) that is really two reeds.

    A single reed's octave partial is either a tiny NNLS leak, or it fails to
    match the upper reed's own spectrum. A played octave has both holes and a
    quiet gap between them.
    """
    found: list[int] = []
    for i in range(7):
        low = float(acts[i])
        high = float(acts[i + 3])
        if min(low, high) < 0.10:
            continue
        between = float(np.max(acts[i + 1 : i + 3]))
        if between > 0.06:
            continue
        clo = 0.0 if cos is None else float(cos[i])
        chi = 0.0 if cos is None else float(cos[i + 3])
        weaker = min(low, high) / max(low, high)
        neighbor = False
        for hole in range(N_HOLES):
            if hole in (i, i + 3):
                continue
            if abs(hole - i) == 1 or abs(hole - (i + 3)) == 1:
                if float(acts[hole]) > max(0.12, 0.55 * min(low, high)):
                    neighbor = True
        if neighbor:
            continue
        upper_is_real = high >= low and chi >= 0.80
        # lower_leads: the upper reed is the quiet partner and still matches its
        # own template a little. A melody note's octave partial is about as
        # strong as the note and barely matches the upper reed.
        lower_leads = (
            low >= high
            and low >= 0.20
            and 0.18 <= weaker <= 0.60
            and clo < 0.50
            and (chi >= 0.15 or high >= 0.16)
        )
        buried_lower = high >= 0.75 and 0.08 <= low <= 0.18 and chi <= 0.45
        both_clear = (
            low >= 0.20 and high >= 0.20 and weaker >= 0.40 and clo < 0.35 and chi >= clo + 0.10
        )
        # soft_upper: hole 1+4, where the lower reed is only a small activation.
        soft_upper = low <= 0.20 and weaker >= 0.30 and high >= 0.20 and chi < 0.55 and clo >= chi
        if upper_is_real or lower_leads or buried_lower or both_clear or soft_upper:
            found.append(i)
    if not found:
        return None
    start = min(found)
    return start + 1, start + 4


def _collapse_harmonic_window(
    bands: np.ndarray, holes: tuple[int, ...] | None
) -> tuple[int, ...] | None:
    """A single reed with a loud octave partial is not a three-hole chord.

    Real octave splits are decided earlier. This only runs when that test
    already said the upper hole is a harmonic.
    """
    if not holes or len(holes) < 2:
        return holes
    peak = int(np.argmax(bands))
    lower = peak - 3
    if lower < 0 or (lower + 1) not in holes:
        return holes
    if float(bands[peak]) < float(bands[lower]) * 1.15:
        return holes
    fundamental = float(bands[lower])
    for hole in holes:
        if hole == lower + 1:
            continue
        if float(bands[hole - 1]) > 0.85 * max(fundamental, 1e-6):
            return holes
    return (lower + 1,)


def _unique_band_breath(draw_bands: np.ndarray, blow_bands: np.ndarray) -> str | None:
    """Draw 2 and blow 3 share a pitch. The other holes do not."""
    draw_unique = float(np.sum(draw_bands)) - float(draw_bands[1])
    blow_unique = float(np.sum(blow_bands)) - float(blow_bands[2])
    if blow_unique > draw_unique * 1.8 and blow_unique > draw_unique + 8.0:
        return "blow"
    if draw_unique > blow_unique * 1.8 and draw_unique > blow_unique + 8.0:
        return "draw"
    return None


def _window_from_bands(bands: np.ndarray) -> tuple[int, ...] | None:
    """Contiguous holes from raw pitch bands, ignoring a louder octave partial."""
    kept = np.array(bands, dtype=float, copy=True)
    for i in range(7):
        if kept[i] >= 0.20 * kept[i + 3] and kept[i + 3] > kept[i] * 1.15:
            kept[i + 3] = 0.0
    peak = float(np.max(kept))
    if peak < 1e-6:
        return None
    floor = 0.20 * peak
    peak_i = int(np.argmax(kept))
    lo = hi = peak_i
    while lo > 0 and kept[lo - 1] >= floor and hi - (lo - 1) <= 2:
        lo -= 1
    while hi < N_HOLES - 1 and kept[hi + 1] >= floor and (hi + 1) - lo <= 2:
        hi += 1
    return tuple(range(lo + 1, hi + 2))


def _decode_activation(
    x: np.ndarray,
    midis: list[int],
    spec_col: np.ndarray | None = None,
    reed_mat: np.ndarray | None = None,
    bands: np.ndarray | None = None,
) -> tuple[str, tuple[int, ...]] | None:
    base = _reassign_unison(x)
    cos = None if spec_col is None or reed_mat is None else reed_mat.T @ spec_col
    draw_oct = _octave_pair(base[:10], None if cos is None else cos[:10])
    blow_oct = _octave_pair(base[10:], None if cos is None else cos[10:])
    if draw_oct or blow_oct:
        draw_s = float(sum(base[h - 1] for h in draw_oct)) if draw_oct else -1.0
        blow_s = float(sum(base[10 + h - 1] for h in blow_oct)) if blow_oct else -1.0
        if blow_s > draw_s and blow_oct:
            return "blow", blow_oct
        if draw_oct:
            return "draw", draw_oct
    y = _suppress_overtone_acts(base, midis)
    draw, blow = y[:10], y[10:]
    draw_h = _best_window(draw)
    blow_h = _best_window(blow)
    qd = _window_quality(draw, draw_h)
    qb = _window_quality(blow, blow_h)
    if qb > qd:
        breath, holes = "blow", blow_h
    else:
        breath, holes = "draw", draw_h
    if spec_col is not None and reed_mat is not None:
        cb, ch, sim = _cosine_reed(spec_col, reed_mat)
        # Isolated dictionary notes sit at ~0.95–1.0; chords stay below ~0.65.
        if sim >= 0.88:
            return cb, (ch,)
    if bands is not None and holes:
        draw_bands, blow_bands = bands[:10], bands[10:]
        bias = _unique_band_breath(draw_bands, blow_bands)
        if bias is not None and bias != breath and len(holes) >= 2:
            switched = _window_from_bands(blow_bands if bias == "blow" else draw_bands)
            if switched:
                breath, holes = bias, switched
        elif breath == "draw" and holes == (1, 2):
            blow_win = _window_from_bands(blow_bands)
            draw_unique = float(np.sum(draw_bands)) - float(draw_bands[1])
            blow_unique = float(np.sum(blow_bands)) - float(blow_bands[2])
            if (
                blow_win
                and len(blow_win) == 3
                and blow_win[0] <= 2
                and blow_unique > draw_unique
            ):
                breath, holes = "blow", blow_win
        chosen_bands = blow_bands if breath == "blow" else draw_bands
        holes = _collapse_harmonic_window(chosen_bands, holes)
    # Use the pre-suppress vector. The missing hole's octave partial is often
    # exactly +12 or +19 of a hole that is already in the pair, so suppress
    # would erase the evidence.
    holes = _promote_buried_end(base[:10] if breath == "draw" else base[10:], holes)
    if not holes:
        return None
    return breath, holes


def _promote_buried_end(acts: np.ndarray, holes: tuple[int, ...] | None) -> tuple[int, ...] | None:
    """Add a missing end hole when its energy landed on the octave reed.

    Draw 1–2–3 in a melody often shows up as holes 1 and 2 plus a loud draw 6,
    with hole 3 itself near zero. A real 1–2 pair does not light draw 6.
    """
    if not holes or len(holes) != 2:
        return holes
    low, high = holes
    if high != low + 1 or high >= N_HOLES:
        return holes
    missing = high + 1
    octave = missing + 3
    if octave > N_HOLES:
        return holes
    if float(acts[missing - 1]) >= 0.10:
        return holes
    partial = float(acts[octave - 1])
    pair = max(float(acts[low - 1]), float(acts[high - 1]))
    if partial < 0.30 or partial < 0.55 * pair:
        return holes
    if float(np.max(acts[missing : octave - 1])) > 0.12:
        return holes
    return (low, high, missing)


def _majority_smooth(
    labels: list[tuple[str, tuple[int, ...]] | None], width: int = 9
) -> list[tuple[str, tuple[int, ...]] | None]:
    half = width // 2
    out = list(labels)
    for i in range(len(labels)):
        window = [
            labels[j]
            for j in range(max(0, i - half), min(len(labels), i + half + 1))
            if labels[j] is not None
        ]
        if not window:
            continue
        counts: dict[tuple[str, tuple[int, ...]], int] = {}
        for lab in window:
            counts[lab] = counts.get(lab, 0) + 1
        out[i] = max(counts.items(), key=lambda kv: kv[1])[0]
    return out


_UNISON_LABELS = {("draw", (2,)), ("blow", (3,))}


def _stabilize_unison_islands(
    labels: list[tuple[str, tuple[int, ...]] | None],
    sounding: np.ndarray,
) -> list[tuple[str, tuple[int, ...]] | None]:
    """If a sounding island only flickers between draw 2 and blow 3, keep the majority."""
    out = list(labels)
    n = len(labels)
    i = 0
    while i < n:
        if not sounding[i]:
            i += 1
            continue
        j = i
        while j < n and sounding[j]:
            j += 1
        present = {lab for lab in out[i:j] if lab is not None}
        if present and present <= _UNISON_LABELS:
            counts = {lab: 0 for lab in present}
            for lab in out[i:j]:
                if lab in counts:
                    counts[lab] += 1
            maj = max(counts.items(), key=lambda kv: kv[1])[0]
            for k in range(i, j):
                out[k] = maj
        i = j
    return out


def _stabilize_octave_islands(
    labels: list[tuple[str, tuple[int, ...]] | None],
    sounding: np.ndarray,
) -> list[tuple[str, tuple[int, ...]] | None]:
    """If a phrase flickers between an octave split and its two single holes, keep the split."""
    out = list(labels)
    n = len(labels)
    i = 0
    while i < n:
        if not sounding[i]:
            i += 1
            continue
        j = i
        while j < n and sounding[j]:
            j += 1
        counts: dict[tuple[str, tuple[int, ...]], int] = {}
        for lab in out[i:j]:
            if lab is not None:
                counts[lab] = counts.get(lab, 0) + 1
        octaves = [lab for lab in counts if len(lab[1]) == 2 and lab[1][1] - lab[1][0] == 3]
        if len(octaves) == 1:
            breath, holes = octaves[0]
            votes = counts[(breath, holes)]
            others_ok = all(
                lab[0] == breath and set(lab[1]) <= set(holes)
                for lab in counts
            )
            if others_ok and votes >= 0.55 * sum(counts.values()) and votes >= 8:
                for k in range(i, j):
                    out[k] = (breath, holes)
        i = j
    return _glue_octave_edges(out, sounding)


def _glue_octave_edges(
    labels: list[tuple[str, tuple[int, ...]] | None],
    sounding: np.ndarray,
) -> list[tuple[str, tuple[int, ...]] | None]:
    """Keep an octave split through a short attack blip or a one-hole release."""
    out = list(labels)
    spans: list[tuple[int, int, tuple[str, tuple[int, ...]]]] = []
    n = len(out)
    i = 0
    while i < n:
        if not sounding[i] or out[i] is None:
            i += 1
            continue
        j = i
        lab = out[i]
        while j < n and sounding[j] and out[j] == lab:
            j += 1
        spans.append((i, j, lab))
        i = j
    for idx, (a, b, lab) in enumerate(spans):
        breath, holes = lab
        if len(holes) == 1 and idx > 0:
            _pa, pb, prev = spans[idx - 1]
            prev_holes = prev[1]
            prev_is_octave = len(prev_holes) == 2 and prev_holes[1] - prev_holes[0] == 3
            if (
                prev_is_octave
                and b - a <= int(0.26 * SR / HOP)
                and (pb - _pa) >= (b - a)
                and prev[0] == breath
                and holes[0] in prev_holes
                and a <= pb + 3
            ):
                for k in range(a, b):
                    out[k] = prev
        elif len(holes) == 2 and holes[1] - holes[0] == 3 and idx + 1 < len(spans):
            na, nb, nxt = spans[idx + 1]
            if (
                b - a <= int(0.22 * SR / HOP)
                and nxt[0] == breath
                and len(nxt[1]) == 2
                and nxt[1][1] - nxt[1][0] == 3
                and nb - na > b - a
                and set(holes) & set(nxt[1])
                and na <= b + 3
            ):
                for k in range(a, b):
                    out[k] = nxt
    return out


def _stabilize_majority_islands(
    labels: list[tuple[str, tuple[int, ...]] | None],
    sounding: np.ndarray,
    min_frac: float = 0.55,
) -> list[tuple[str, tuple[int, ...]] | None]:
    """If one legal combo owns most of a sounding island, use it throughout."""
    out = list(labels)
    n = len(labels)
    i = 0
    while i < n:
        if not sounding[i]:
            i += 1
            continue
        j = i
        while j < n and sounding[j]:
            j += 1
        counts: dict[tuple[str, tuple[int, ...]], int] = {}
        for lab in out[i:j]:
            if lab is not None:
                counts[lab] = counts.get(lab, 0) + 1
        if counts:
            total = sum(counts.values())
            maj, votes = max(counts.items(), key=lambda kv: kv[1])
            if votes >= min_frac * total:
                for k in range(i, j):
                    out[k] = maj
        i = j
    return out


def save_templates(path: Path, banks: dict[str, np.ndarray]) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {f"bank_{key}": value for key, value in banks.items()}
    _lo, _hi, freqs = _band_slice()
    payload["freqs"] = freqs.astype(np.float32)
    np.savez_compressed(path, **payload)
    return path


def load_templates(path: Path | None = None) -> dict[str, np.ndarray] | None:
    path = Path(path) if path is not None else DATA_PATH
    if not path.exists():
        return None
    data = np.load(path)
    return {k.replace("bank_", ""): data[k] for k in data.files if k.startswith("bank_")}


def bank_for_harp(harp: HarpLayout, banks: dict[str, np.ndarray]) -> np.ndarray:
    key = harp.key
    if key in banks:
        return banks[key]
    if "C" not in banks:
        raise KeyError("template bank missing C harmonica")
    return transpose_bank(banks["C"], harp.offset)


def match_audio(audio: np.ndarray, bank: np.ndarray, *, offset: int = 0) -> list[RawNote]:
    from scipy.optimize import nnls

    spec = _magnitude_spec(audio)
    rms = _rms_frames(audio)
    n_frames = spec.shape[1]
    rms = np.pad(rms, (0, max(0, n_frames - rms.shape[0])))[:n_frames]
    times = librosa.frames_to_time(np.arange(n_frames), sr=SR, hop_length=HOP)
    _lo, _hi, freqs = _band_slice()
    midis = _midi_list(offset)
    feats = _band_energy_matrix(spec, freqs, midis)
    T = _template_matrix(bank, freqs, midis)
    reed_mat = np.stack([bank[bi, h] for bi in range(2) for h in range(N_HOLES)], axis=1)
    col_n = np.linalg.norm(spec, axis=0, keepdims=True)
    spec_n = spec / np.maximum(col_n, 1e-12)
    acts = np.zeros((20, n_frames), dtype=np.float32)
    for i in range(n_frames):
        obs = feats[:, i]
        nrm = float(np.linalg.norm(obs))
        if nrm < 1e-8:
            continue
        coef, _resid = nnls(T, obs / nrm)
        acts[:, i] = coef
    try:
        from scipy.ndimage import median_filter

        acts = median_filter(acts, size=(1, 5))
    except Exception:
        pass

    labels: list[tuple[str, tuple[int, ...]] | None] = []
    for i in range(n_frames):
        labels.append(_decode_activation(acts[:, i], midis, spec_n[:, i], reed_mat, feats[:, i]))
    labels = _majority_smooth(labels, width=9)

    rms_gate = max(float(np.max(rms)) * 0.09, 1e-8)
    sounding = np.array([rms[i] >= rms_gate and labels[i] is not None for i in range(n_frames)])
    try:
        from scipy.ndimage import median_filter as _med

        sounding = _med(sounding.astype(np.uint8), size=5) > 0
    except Exception:
        pass
    # A real breath pause falls well below the note, even when a short blink
    # in the middle of a phrase would otherwise be smoothed over.
    pause_level = float(np.max(rms)) * 0.16
    i = 0
    while i < n_frames:
        if rms[i] >= pause_level:
            i += 1
            continue
        j = i
        while j < n_frames and rms[j] < pause_level:
            j += 1
        if j - i >= 4:
            sounding[i:j] = False
        i = j
    labels = _stabilize_unison_islands(labels, sounding)
    labels = _stabilize_octave_islands(labels, sounding)
    labels = _stabilize_majority_islands(labels, sounding)

    notes: list[RawNote] = []
    i = 0
    dt = HOP / SR
    while i < n_frames:
        if not sounding[i] or labels[i] is None:
            i += 1
            continue
        j = i
        lab = labels[i]
        while j < n_frames and sounding[j] and labels[j] == lab:
            j += 1
        t0 = float(times[i])
        t1 = float(times[min(j, n_frames - 1)] + dt)
        breath, holes = lab
        if t1 - t0 < (0.14 if len(holes) >= 2 else 0.08):
            i = j
            continue
        amp = float(np.mean(rms[i:j]))
        for hole in holes:
            midi = _NATURAL_MIDI[breath][hole - 1] + offset
            notes.append(RawNote(t0, t1, float(midi), amp, hole=hole, breath=breath))
        i = j
    return notes


_NATURAL_MIDI = {
    "blow": C_BLOW,
    "draw": C_DRAW,
}


def template_raw_notes(audio_path: Path, harp: HarpLayout) -> list[RawNote] | None:
    banks = load_templates()
    if not banks:
        return None
    audio, _sr = librosa.load(str(audio_path), sr=SR, mono=True)
    if audio.size == 0:
        return []
    bank = bank_for_harp(harp, banks)
    return match_audio(audio, bank, offset=harp.offset)
