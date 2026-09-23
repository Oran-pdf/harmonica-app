"""Map transcribed pitches onto harmonica notes and chords."""

from __future__ import annotations

from dataclasses import asdict, dataclass
from itertools import combinations, product
from typing import Iterable, Sequence

from harmonica_annotator.layout import (
    HarpLayout,
    HoleTone,
    OCTAVE_SPLITS,
    is_legal_combination,
    name_chord,
    tab_string,
)

# Common harmonic intervals in semitones (overtones of a louder fundamental).
OVERTONE_INTERVALS = frozenset({12, 19, 24, 28, 31, 36})


@dataclass
class RawNote:
    t0: float
    t1: float
    midi: float
    amplitude: float = 1.0
    hole: int | None = None
    breath: str | None = None


@dataclass
class TimelineEvent:
    t0: float
    t1: float
    midi: list[int]
    names: list[str]
    chord: str | None
    tab: str
    breath: str | None
    bent: bool
    holes: list[HoleTone]

    def to_dict(self) -> dict:
        payload = asdict(self)
        return payload

    def label(self) -> str:
        if self.chord:
            return f"{self.chord}\n{self.tab}"
        if not self.names:
            return self.tab
        extra = " bent" if self.bent else ""
        return f"{self.names[0]}{extra}\n{self.tab}"


@dataclass
class _Active:
    t0: float
    t1: float
    midi: int
    amplitude: float
    candidates: tuple[HoleTone, ...]
    locked: bool = False


def decode_notes(
    raw_notes: Sequence[RawNote],
    harp: HarpLayout,
    *,
    max_cents: float = 60.0,
    min_duration: float = 0.06,
    merge_gap: float = 0.04,
) -> list[TimelineEvent]:
    snapped = _snap(raw_notes, harp, max_cents)
    snapped = _merge_same_pitch(snapped, merge_gap)
    snapped = _drop_overtones(snapped)
    snapped = _drop_weak_bends(snapped)
    events = _segment(snapped, harp, min_duration)
    events = _merge_events(events, merge_gap)
    events = _reinterpret_fast_bends(events, harp)
    events = _drop_brief_glitches(events)
    return _drop_isolated_blips(events)


def _snap(raw_notes: Sequence[RawNote], harp: HarpLayout, max_cents: float) -> list[_Active]:
    out: list[_Active] = []
    for note in raw_notes:
        if note.t1 <= note.t0:
            continue
        locked = False
        matched: tuple[HoleTone, ...] | None = None
        if note.hole is not None and note.breath is not None:
            locked_tones = tuple(
                t for t in harp.tones if t.hole == note.hole and t.breath == note.breath and t.bend == 0
            )
            if locked_tones:
                matched = locked_tones
                locked = True
        if matched is None:
            matched = harp.nearest(note.midi, max_cents=max_cents)
        if matched is None:
            continue
        out.append(
            _Active(
                t0=float(note.t0),
                t1=float(note.t1),
                midi=matched[0].midi,
                amplitude=float(note.amplitude),
                candidates=matched,
                locked=locked,
            )
        )
    return out


def _merge_same_pitch(notes: list[_Active], gap: float) -> list[_Active]:
    by_pitch: dict[int, list[_Active]] = {}
    for note in sorted(notes, key=lambda n: (n.midi, n.t0)):
        by_pitch.setdefault(note.midi, []).append(note)
    merged: list[_Active] = []
    for group in by_pitch.values():
        current = group[0]
        for nxt in group[1:]:
            same_lock = True
            if current.locked and nxt.locked and current.candidates and nxt.candidates:
                a, b = current.candidates[0], nxt.candidates[0]
                same_lock = (a.hole, a.breath) == (b.hole, b.breath)
            if nxt.t0 <= current.t1 + gap and same_lock:
                current = _Active(
                    t0=current.t0,
                    t1=max(current.t1, nxt.t1),
                    midi=current.midi,
                    amplitude=max(current.amplitude, nxt.amplitude),
                    candidates=current.candidates if current.locked else nxt.candidates,
                    locked=current.locked or nxt.locked,
                )
            else:
                merged.append(current)
                current = nxt
        merged.append(current)
    return merged


def _overlap(a: _Active, b: _Active) -> bool:
    return a.t0 < b.t1 and b.t0 < a.t1


def _drop_overtones(notes: list[_Active], amp_ratio: float = 2.6) -> list[_Active]:
    keep: list[_Active] = []
    for note in notes:
        if note.locked:
            keep.append(note)
            continue
        hi_hole = min((t.hole for t in note.candidates), default=99)
        clustered = False
        harmonic = False
        for other in notes:
            if other is note or not _overlap(note, other):
                continue
            other_hole = min((t.hole for t in other.candidates), default=99)
            if abs(hi_hole - other_hole) <= 2:
                clustered = True
            interval = note.midi - other.midi
            if interval in OVERTONE_INTERVALS or (interval % 12 == 0 and interval > 0):
                harmonic = True
        if harmonic and not clustered:
            continue
        keep.append(note)
    return keep


def _drop_weak_bends(notes: list[_Active]) -> list[_Active]:
    """If a bend overlaps a nearby natural, keep the natural unless the bend is clearly louder."""
    keep: list[_Active] = []
    for note in notes:
        cands = note.candidates
        if not cands or cands[0].bend == 0:
            keep.append(note)
            continue
        hole = cands[0].hole
        breath = cands[0].breath
        stronger_natural = False
        for other in notes:
            if other is note or not _overlap(note, other):
                continue
            oc = other.candidates
            if not oc or oc[0].bend != 0:
                continue
            if oc[0].hole == hole and oc[0].breath == breath and other.amplitude >= note.amplitude * 0.55:
                stronger_natural = True
                break
        if not stronger_natural:
            keep.append(note)
    return keep


def _segment(notes: list[_Active], harp: HarpLayout, min_duration: float) -> list[TimelineEvent]:
    if not notes:
        return []
    bounds = sorted({round(n.t0, 4) for n in notes} | {round(n.t1, 4) for n in notes})
    events: list[TimelineEvent] = []
    prev_holes: tuple[HoleTone, ...] = ()
    prev2_holes: tuple[HoleTone, ...] = ()
    for t0, t1 in zip(bounds, bounds[1:]):
        mid = (t0 + t1) / 2.0
        active = [n for n in notes if n.t0 - 1e-6 <= mid < n.t1]
        if not active:
            continue
        decoded = _decode_slice(active, harp, prefer=prev_holes, prefer2=prev2_holes)
        if decoded is None:
            continue
        decoded.t0 = t0
        decoded.t1 = t1
        if decoded.t1 - decoded.t0 >= min_duration * 0.5:
            events.append(decoded)
            prev2_holes = prev_holes
            prev_holes = tuple(decoded.holes)
    return events


def _is_scale_walk(prev: Sequence[HoleTone], prev2: Sequence[HoleTone]) -> bool:
    if len(prev) != 1 or len(prev2) != 1:
        return False
    older, newer = prev2[0], prev[0]
    return (
        older.bend == 0
        and newer.bend == 0
        and older.breath == newer.breath
        and newer.hole == older.hole + 1
    )


def _decode_slice(
    active: Sequence[_Active],
    harp: HarpLayout,
    prefer: Sequence[HoleTone] = (),
    prefer2: Sequence[HoleTone] = (),
) -> TimelineEvent | None:
    if active and all(n.locked and n.candidates for n in active):
        tones = [n.candidates[0] for n in active]
        if is_legal_combination(tones):
            return _event_from_tones(tones, harp, active[0].t0, active[0].t1)
        legal = _best_locked_subset(active)
        if legal:
            return _event_from_tones(legal, harp, active[0].t0, active[0].t1)
    assignment = _best_assignment(active, prefer=prefer, prefer2=prefer2)
    if assignment is None:
        loudest = max(active, key=lambda n: n.amplitude)
        tone = _prefer_natural(loudest.candidates, prefer=prefer, prefer2=prefer2)
        return _event_from_tones([tone], harp, loudest.t0, loudest.t1)
    return _event_from_tones(assignment, harp, active[0].t0, active[0].t1)


def _best_locked_subset(active: Sequence[_Active]) -> list[HoleTone] | None:
    notes = [(n.candidates[0], n.amplitude) for n in active if n.candidates]
    if not notes:
        return None
    by_breath: dict[str, list[tuple[HoleTone, float]]] = {}
    for tone, amp in notes:
        by_breath.setdefault(tone.breath, []).append((tone, amp))
    breath = max(by_breath, key=lambda b: sum(a for _t, a in by_breath[b]))
    group = sorted(by_breath[breath], key=lambda ta: ta[0].hole)
    amps = {t.hole: a for t, a in group}
    peak = max(amps.values()) or 1.0
    best: list[HoleTone] | None = None
    best_score = -1.0
    tones_by_hole = {t.hole: t for t, _a in group}
    for n in (1, 2, 3):
        for start in range(0, 10 - n + 1):
            win = list(range(start + 1, start + 1 + n))
            if any(h not in amps for h in win):
                continue
            if min(amps[h] for h in win) < (0.18 if n >= 2 else 0.12) * peak:
                continue
            score = sum(amps[h] for h in win) + 0.08 * (n - 1) - 0.03 * start
            if score > best_score:
                best_score = score
                best = [tones_by_hole[h] for h in win]
    return best


def _prefer_natural(
    candidates: Sequence[HoleTone],
    prefer: Sequence[HoleTone] = (),
    prefer2: Sequence[HoleTone] = (),
) -> HoleTone:
    prev = list(prefer)
    walk = _is_scale_walk(prefer, prefer2)

    def key(t: HoleTone) -> tuple[int, int, int, int]:
        step = 0
        if prev:
            pb = prev[0]
            if walk and t.breath == pb.breath and t.bend == 0 and abs(t.hole - pb.hole) == 1:
                step = -3
            elif (t.hole, t.breath, t.bend) == (pb.hole, pb.breath, pb.bend):
                step = -2
        return (t.bend, step, 1 if t.is_blow_three_unison else 0, t.hole)

    return sorted(candidates, key=key)[0]


def _best_assignment(
    active: Sequence[_Active],
    prefer: Sequence[HoleTone] = (),
    prefer2: Sequence[HoleTone] = (),
) -> list[HoleTone] | None:
    notes = list(active)
    if any(len(n.candidates) == 0 for n in notes):
        return None
    max_amp = max(n.amplitude for n in notes) or 1.0
    notes = [n for n in notes if n.amplitude >= max_amp * 0.30]
    if not notes:
        return None
    if len(notes) > 6:
        notes = sorted(notes, key=lambda n: -n.amplitude)[:6]

    n = len(notes)
    best: list[HoleTone] | None = None
    best_score = -1e9
    prefer_keys = {(t.hole, t.breath, t.bend) for t in prefer}
    walk = _is_scale_walk(prefer, prefer2)
    for size in range(n, 0, -1):
        size_floor = 0.32 if size <= 2 else 0.34
        for subset in combinations(notes, size):
            if any(note.amplitude < max_amp * size_floor for note in subset):
                continue
            options = [note.candidates for note in subset]
            for combo in product(*options):
                if not is_legal_combination(combo):
                    continue
                holes = tuple(sorted(t.hole for t in combo))
                if len(set(holes)) < len(combo):
                    continue
                span = holes[-1] - holes[0] + 1
                contiguous_bonus = 2.2 if span == len(holes) else -1.0
                bend_penalty = sum(t.bend for t in combo) * 1.3
                amp = sum(note.amplitude for note in subset)
                omitted = [note for note in notes if id(note) not in {id(n) for n in subset}]
                omit_penalty = sum(
                    note.amplitude for note in omitted if note.amplitude >= max_amp * (0.42 if size >= 3 else 0.32)
                )
                contiguous = span == len(holes)
                high_penalty = 0.0 if contiguous and size >= 2 else 2.4 * sum(1 for t in combo if t.hole >= 7)
                pair_bonus = 2.4 if size == 2 and span == 2 else 0.0
                trio_bonus = 2.4 if size == 3 and span == 3 else 0.0
                octave_penalty = 8.0 if len(holes) == 2 and tuple(holes) in OCTAVE_SPLITS else 0.0
                hole_penalty = 0.18 * sum(t.hole for t in combo)
                unison_penalty = 1.4 * sum(1 for t in combo if t.is_blow_three_unison)
                stay_bonus = 1.6 * sum(1 for t in combo if (t.hole, t.breath, t.bend) in prefer_keys)
                step_bonus = 0.0
                if walk:
                    for t in combo:
                        if any(
                            t.breath == p.breath and t.bend == 0 and p.bend == 0 and abs(t.hole - p.hole) == 1
                            for p in prefer
                        ):
                            step_bonus += 2.2
                score = (
                    amp * 2.4
                    + contiguous_bonus
                    + pair_bonus
                    + trio_bonus
                    + stay_bonus
                    + step_bonus
                    - bend_penalty
                    - high_penalty
                    - unison_penalty
                    - hole_penalty
                    - omit_penalty * 2.8
                    - octave_penalty
                )
                if score > best_score:
                    best_score = score
                    best = list(combo)
    return best


def _event_from_tones(
    tones: Sequence[HoleTone],
    harp: HarpLayout,
    t0: float,
    t1: float,
) -> TimelineEvent:
    unique = []
    seen = set()
    for tone in sorted(tones, key=lambda t: (t.midi, t.hole)):
        if tone.midi in seen:
            continue
        seen.add(tone.midi)
        unique.append(tone)
    chord = name_chord(unique, harp.key)
    breath = unique[0].breath if len({t.breath for t in unique}) == 1 else None
    return TimelineEvent(
        t0=t0,
        t1=t1,
        midi=[t.midi for t in unique],
        names=[harp.name(t.midi) for t in unique],
        chord=chord,
        tab=tab_string(unique),
        breath=breath,
        bent=any(t.bent for t in unique),
        holes=list(unique),
    )


def _merge_events(events: Sequence[TimelineEvent], gap: float) -> list[TimelineEvent]:
    if not events:
        return []
    ordered = sorted(events, key=lambda e: (e.t0, e.t1))
    merged = [ordered[0]]
    for event in ordered[1:]:
        prev = merged[-1]
        same = (
            event.midi == prev.midi
            and event.tab == prev.tab
            and event.chord == prev.chord
            and event.t0 <= prev.t1 + gap
        )
        if same:
            merged[-1] = TimelineEvent(
                t0=prev.t0,
                t1=max(prev.t1, event.t1),
                midi=prev.midi,
                names=prev.names,
                chord=prev.chord,
                tab=prev.tab,
                breath=prev.breath,
                bent=prev.bent,
                holes=prev.holes,
            )
        else:
            merged.append(event)
    return merged


def _reinterpret_fast_bends(events: list[TimelineEvent], harp: HarpLayout) -> list[TimelineEvent]:
    """A 2-draw bend passes through pitches that snap to blow 2; don't flip breath that fast."""
    deep = next((t for t in harp.tones if t.hole == 2 and t.breath == "draw" and t.bend == 2), None)
    if deep is None or len(events) < 2:
        return events
    out: list[TimelineEvent] = []
    last_draw2_t1 = -1.0
    for event in events:
        if any(t.hole == 2 and t.breath == "draw" for t in event.holes):
            last_draw2_t1 = event.t1
        short = event.t1 - event.t0 < 0.16
        blow2 = (
            len(event.holes) == 1
            and event.holes[0].hole == 2
            and event.holes[0].breath == "blow"
            and event.holes[0].bend == 0
        )
        if blow2 and short and event.t0 - last_draw2_t1 <= 0.14:
            converted = _event_from_tones([deep], harp, event.t0, event.t1)
            out.append(converted)
            last_draw2_t1 = converted.t1
        else:
            out.append(event)
    cleaned = []
    for event in out:
        dur = event.t1 - event.t0
        if event.breath == "blow" and dur < 0.09:
            continue
        cleaned.append(event)
    return _merge_events(cleaned, 0.06)


def _drop_isolated_blips(
    events: list[TimelineEvent], max_dur: float = 0.10, min_gap: float = 0.12
) -> list[TimelineEvent]:
    """Drop a tenth-of-a-second single that sits alone between phrases."""
    if len(events) < 2:
        return events
    kept: list[TimelineEvent] = []
    for i, event in enumerate(events):
        dur = event.t1 - event.t0
        if dur >= max_dur or len(event.holes) != 1:
            kept.append(event)
            continue
        prev_gap = event.t0 - events[i - 1].t1 if i else 999.0
        next_gap = events[i + 1].t0 - event.t1 if i + 1 < len(events) else 999.0
        if min(prev_gap, next_gap) > min_gap:
            continue
        kept.append(event)
    return kept


def _drop_brief_glitches(events: list[TimelineEvent], min_dur: float = 0.09) -> list[TimelineEvent]:
    """Drop isolated flickers; keep short bends that continue the previous hole."""
    if not events:
        return events
    kept: list[TimelineEvent] = []
    for i, event in enumerate(events):
        dur = event.t1 - event.t0
        if dur >= min_dur:
            kept.append(event)
            continue
        prev = kept[-1] if kept else None
        same_holes = bool(
            prev
            and {(t.hole, t.breath) for t in event.holes} <= {(t.hole, t.breath) for t in prev.holes}
            or (prev and event.bent and any(t.hole == p.hole for t in event.holes for p in prev.holes))
        )
        if same_holes:
            kept.append(event)
    return _merge_events(kept, 0.05)


def events_to_jsonable(events: Iterable[TimelineEvent]) -> list[dict]:
    return [e.to_dict() for e in events]
