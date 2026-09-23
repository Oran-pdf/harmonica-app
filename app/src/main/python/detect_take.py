"""Run the reed-template detector and return the app's timeline JSON."""

from __future__ import annotations

import json
from pathlib import Path

from harmonica_annotator.decoder import decode_notes
from harmonica_annotator.layout import HarpLayout
from harmonica_annotator.templates import template_raw_notes


def detect(wav_path, key="C"):
    harp = HarpLayout(str(key or "C"))
    raw = template_raw_notes(Path(wav_path), harp) or []
    events = decode_notes(raw, harp, min_duration=0.06)
    payload = []
    for event in events:
        holes = []
        for tone in event.holes:
            holes.append({"hole": int(tone.hole), "bend": int(tone.bend)})
        if not holes:
            continue
        breath = event.breath if event.breath in ("blow", "draw") else event.holes[0].breath
        payload.append(
            {
                "t0": float(event.t0),
                "t1": float(event.t1),
                "breath": breath,
                "holes": holes,
            }
        )
    return json.dumps({"key": harp.key, "events": payload})
