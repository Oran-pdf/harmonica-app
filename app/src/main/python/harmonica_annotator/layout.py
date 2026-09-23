"""10-hole diatonic harmonica layout (Richter tuning)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable, Literal

Breath = Literal["blow", "draw"]

SHARP_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
FLAT_NAMES = ["C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B"]
FLAT_KEYS = {"F", "BB", "EB", "AB", "DB", "GB"}

# Pitch-class of the harmonica key. C = 0.
_KEY_PC = {
    "C": 0,
    "C#": 1,
    "DB": 1,
    "D": 2,
    "D#": 3,
    "EB": 3,
    "E": 4,
    "F": 5,
    "F#": 6,
    "GB": 6,
    "G": 7,
    "G#": 8,
    "AB": 8,
    "A": 9,
    "A#": 10,
    "BB": 10,
    "B": 11,
}

# C harmonica, hole 1 blow = C4 (MIDI 60).
C_BLOW = (60, 64, 67, 72, 76, 79, 84, 88, 91, 96)
C_DRAW = (62, 67, 71, 74, 77, 81, 83, 86, 89, 93)

# Extra half-steps below the natural note, deepest first in the lists.
C_DRAW_BENDS: dict[int, tuple[int, ...]] = {
    1: (61,),
    2: (66, 65),
    3: (70, 69, 68),
    4: (73,),
    5: (76,),
    6: (80,),
}
C_BLOW_BENDS: dict[int, tuple[int, ...]] = {
    8: (87,),
    9: (90, 89),
    10: (95, 94, 93),
}

OCTAVE_SPLITS = {(1, 4), (2, 5), (3, 6), (4, 7), (5, 8), (6, 9), (7, 10)}


def normalize_key(key: str) -> str:
    raw = key.strip().replace("♯", "#").replace("♭", "b")
    token = raw.upper()
    if len(raw) >= 2 and raw[1] == "b":
        token = raw[0].upper() + "B"
    if token not in _KEY_PC:
        raise ValueError(f"Unknown harmonica key {key!r}. Try C, G, A, Bb, …")
    # Canonical spelling: prefer the user-ish form.
    spelling = {
        "C": "C",
        "C#": "C#",
        "DB": "Db",
        "D": "D",
        "D#": "D#",
        "EB": "Eb",
        "E": "E",
        "F": "F",
        "F#": "F#",
        "GB": "Gb",
        "G": "G",
        "G#": "G#",
        "AB": "Ab",
        "A": "A",
        "A#": "A#",
        "BB": "Bb",
        "B": "B",
    }
    return spelling[token]


def key_pc(key: str) -> int:
    return _KEY_PC[_key_token(key)]


def _key_token(key: str) -> str:
    key = normalize_key(key)
    return key.upper() if len(key) == 1 or key[1] == "#" else key[0].upper() + "B"


def key_offset(key: str) -> int:
    """Semitone shift from a C harp, keeping the instrument in a realistic octave."""
    pc = _KEY_PC[_key_token(key)]
    return pc if pc <= 6 else pc - 12


def midi_to_name(midi: int, key: str = "C") -> str:
    names = FLAT_NAMES if _key_token(key) in FLAT_KEYS else SHARP_NAMES
    return f"{names[midi % 12]}{midi // 12 - 1}"


def pc_to_name(pc: int, key: str = "C") -> str:
    names = FLAT_NAMES if _key_token(key) in FLAT_KEYS else SHARP_NAMES
    return names[pc % 12]


@dataclass(frozen=True)
class HoleTone:
    hole: int
    breath: Breath
    midi: int
    bend: int = 0

    @property
    def tab(self) -> str:
        prefix = "" if self.breath == "blow" else "-"
        return f"{prefix}{self.hole}{"'" * self.bend}"

    @property
    def bent(self) -> bool:
        return self.bend > 0

    @property
    def is_blow_three_unison(self) -> bool:
        """Same pitch as draw 2; prefer draw 2 unless a blow chord needs hole 3."""
        return self.hole == 3 and self.breath == "blow" and self.bend == 0


def _build_c_tones(include_bends: bool) -> tuple[HoleTone, ...]:
    tones: list[HoleTone] = []
    for hole, midi in enumerate(C_BLOW, start=1):
        tones.append(HoleTone(hole, "blow", midi, 0))
        if include_bends:
            for i, bent_midi in enumerate(C_BLOW_BENDS.get(hole, ()), start=1):
                tones.append(HoleTone(hole, "blow", bent_midi, i))
    for hole, midi in enumerate(C_DRAW, start=1):
        tones.append(HoleTone(hole, "draw", midi, 0))
        if include_bends:
            for i, bent_midi in enumerate(C_DRAW_BENDS.get(hole, ()), start=1):
                tones.append(HoleTone(hole, "draw", bent_midi, i))
    return tuple(tones)


def _tone_rank(tone: HoleTone) -> tuple[int, int, int, int]:
    """Sort key: naturals first, draw-2 before blow-3 unison, then hole number."""
    blow_three = 1 if tone.is_blow_three_unison else 0
    breath = 0 if tone.breath == "draw" else 1
    return (tone.bend, blow_three, tone.hole, breath)


class HarpLayout:
    """Richter 10-hole diatonic layout transposed to ``key``."""

    def __init__(self, key: str = "C", include_bends: bool = True) -> None:
        self.key = normalize_key(key)
        self.offset = key_offset(self.key)
        self.include_bends = include_bends
        self.tones: tuple[HoleTone, ...] = tuple(
            HoleTone(t.hole, t.breath, t.midi + self.offset, t.bend)
            for t in _build_c_tones(include_bends)
        )
        by_midi: dict[int, list[HoleTone]] = {}
        for tone in self.tones:
            by_midi.setdefault(tone.midi, []).append(tone)
        self._by_midi = {
            midi: tuple(sorted(group, key=_tone_rank))
            for midi, group in by_midi.items()
        }
        self.legal_midis = frozenset(self._by_midi)
        self.tonic_pc = (_KEY_PC[_key_token(self.key)]) % 12

    @property
    def min_midi(self) -> int:
        return min(self.legal_midis)

    @property
    def max_midi(self) -> int:
        return max(self.legal_midis)

    def tones_for_midi(self, midi: int) -> tuple[HoleTone, ...]:
        return self._by_midi.get(midi, ())

    def nearest(self, midi: float, max_cents: float = 60.0) -> tuple[HoleTone, ...] | None:
        best_midi = min(self.legal_midis, key=lambda m: abs(m - midi))
        cents = abs(best_midi - midi) * 100.0
        if cents > max_cents:
            return None
        natural_midis = [t.midi for t in self.tones if t.bend == 0]
        best_nat = min(natural_midis, key=lambda m: abs(m - midi))
        nat_cents = abs(best_nat - midi) * 100.0
        # Intonation often sits a bit flat of a natural; don't prefer a nearby bend.
        if nat_cents <= max(max_cents, 70.0) and nat_cents <= cents + 55:
            return self.tones_for_midi(best_nat)
        return self.tones_for_midi(best_midi)

    def detection_midis(self) -> list[int]:
        """Pitches to listen for: naturals, plus the common 2-draw bends."""
        midis = {t.midi for t in self.tones if t.bend == 0}
        midis.update(
            t.midi for t in self.tones if t.hole == 2 and t.breath == "draw" and t.bend > 0
        )
        return sorted(midis)

    def name(self, midi: int) -> str:
        return midi_to_name(midi, self.key)


def is_legal_holes(holes: Iterable[int], breath_count: int = 1) -> bool:
    """True if the holes can be played together (adjacent, or an octave split)."""
    unique = sorted(set(holes))
    if not unique:
        return False
    if len(unique) == 1:
        return True
    if unique[-1] - unique[0] + 1 == len(unique):
        return True
    if len(unique) == 2 and tuple(unique) in OCTAVE_SPLITS:
        return True
    return False


def is_legal_combination(tones: Iterable[HoleTone]) -> bool:
    tones = list(tones)
    if not tones:
        return False
    breaths = {t.breath for t in tones}
    if len(breaths) != 1:
        return False
    return is_legal_holes(t.hole for t in tones)


def name_chord(tones: Iterable[HoleTone], key: str) -> str | None:
    """Name a harmonica chord from the sounding hole tones.

    Blow combinations are the I chord. Draw 1–4 is V, draw with the 7th is V7,
    draw 4–6 is ii. Returns None for a single note.
    """
    tones = list(tones)
    if len({t.midi for t in tones}) < 2:
        return None
    if not is_legal_combination(tones):
        return None

    layout_key = normalize_key(key)
    tonic = _KEY_PC[_key_token(layout_key)]
    pcs = frozenset(t.midi % 12 for t in tones)
    breaths = {t.breath for t in tones}
    holes = frozenset(t.hole for t in tones)

    i_pcs = frozenset({tonic, (tonic + 4) % 12, (tonic + 7) % 12})
    v_root = (tonic + 7) % 12
    v_pcs = frozenset({v_root, (v_root + 4) % 12, (v_root + 7) % 12})
    v7_pcs = v_pcs | {(v_root + 10) % 12}
    ii_root = (tonic + 2) % 12
    ii_pcs = frozenset({ii_root, (ii_root + 3) % 12, (ii_root + 7) % 12})
    viio_pcs = frozenset({(tonic + 11) % 12, (tonic + 2) % 12, (tonic + 5) % 12, (tonic + 9) % 12})

    if breaths == {"blow"}:
        if pcs <= i_pcs:
            return pc_to_name(tonic, layout_key)
        return pc_to_name(tonic, layout_key)

    # Draw side.
    if pcs <= v_pcs:
        return pc_to_name(v_root, layout_key)
    if pcs <= v7_pcs and (v_root + 10) % 12 in pcs:
        return f"{pc_to_name(v_root, layout_key)}7"
    if pcs <= ii_pcs or holes <= {4, 5, 6, 8, 9, 10} and pcs <= (ii_pcs | {(tonic + 11) % 12}):
        if pcs <= ii_pcs:
            return f"{pc_to_name(ii_root, layout_key)}m"
    if pcs <= viio_pcs and len(pcs) >= 3:
        return f"{pc_to_name((tonic + 11) % 12, layout_key)}m7b5"
    if pcs <= v7_pcs:
        return pc_to_name(v_root, layout_key) if (v_root + 10) % 12 not in pcs else f"{pc_to_name(v_root, layout_key)}7"
    if pcs <= ii_pcs:
        return f"{pc_to_name(ii_root, layout_key)}m"
    return None


def tab_string(tones: Iterable[HoleTone]) -> str:
    tones = sorted(tones, key=lambda t: (t.hole, t.breath, t.bend))
    if not tones:
        return ""
    if len(tones) == 1:
        return tones[0].tab
    breath = tones[0].breath
    nums = " ".join(str(t.hole) + ("'" * t.bend) for t in tones)
    return f"{nums} {breath}"
