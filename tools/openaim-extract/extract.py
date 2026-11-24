"""Aggregate Valorant engagement statistics from a local open-aim checkout.

Usage: python -I extract.py <open-aim root> [--out <file>]

Reads the open-aim folder only. Writes aggregated numbers (quantiles, counts); no scene
text, names, coordinates or layouts. See README.md for the assumptions.
"""
from __future__ import annotations

import argparse
import json
import logging
import subprocess
from collections import Counter
from collections.abc import Callable
from pathlib import Path

from geometry import angular_size
from scenes import Exposure, Scene, load_scenes
from stats import rounded, summary

log = logging.getLogger("openaim-extract")

HEAD_DIAMETER = 0.24  # open-aim head sphere radius 0.12 m
DEFAULT_OUT = Path(__file__).resolve().parents[2] / "games/src/main/resources/games/valorant-engagements.json"

METRICS: dict[str, tuple[str, Callable[[Exposure], float | None]]] = {
    "distance": ("m", lambda e: e.sight.distance),
    "yaw_offset_abs": ("deg", lambda e: abs(e.sight.yaw_offset)),
    "elevation": ("deg", lambda e: e.sight.elevation),
    "head_size": ("deg", lambda e: angular_size(HEAD_DIAMETER, e.sight.distance)),
    "angular_speed_moving": ("deg/s", lambda e: e.speed if e.speed > 0 else None),
}


def metric_groups(scenes: list[Scene], fn: Callable[[Exposure], float | None]) -> list[list[float]]:
    return [[v for v in (fn(e) for e in s.exposures) if v is not None] for s in scenes]


def group_stats(scenes: list[Scene]) -> dict:
    out = {"n_scenes": len(scenes), "metrics": {}}
    for name, (unit, fn) in METRICS.items():
        out["metrics"][name] = {"unit": unit, **summary(metric_groups(scenes, fn))}
    # Share of exposures where the bot moves, each scene weight 1.
    shares = [sum(e.speed > 0 for e in s.exposures) / len(s.exposures) for s in scenes if s.exposures]
    out["moving_share"] = sum(shares) / len(shares) if shares else None
    motions: dict[str, float] = {}
    for s in scenes:
        for e in s.exposures:
            motions[e.motion] = motions.get(e.motion, 0.0) + 1 / len(s.exposures)
    out["motion_share"] = {k: v / len(scenes) for k, v in sorted(motions.items())} if scenes else {}
    return out


def timing_stats(scenes: list[Scene]) -> dict:
    names = sorted({k for s in scenes for k in s.timings})
    return {n: {"unit": "s" if n.endswith("_s") else "deg", **summary([s.timings.get(n, ()) for s in scenes])} for n in names}


def git_revision(root: Path) -> str:
    try:
        return subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True, check=True, timeout=10
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def build_report(root: Path) -> dict:
    scenes = load_scenes(root)
    on_map = [s for s in scenes if s.map and s.exposures]
    groups = {"all": on_map}
    for kind in ("flash", "peek", "static"):
        groups[kind] = [s for s in on_map if s.kind == kind]
    for source in ("curated", "community"):
        groups[source] = [s for s in on_map if s.source == source]
    return rounded({
        "verified": False,
        "source": {
            "repo": "https://github.com/unkwsr/open-aim",
            "commit": git_revision(root),
            "tool": "tools/openaim-extract",
            "scenes_read": len(scenes),
            "scenes_used": len(on_map),
            "scenes_dropped_no_map": sum(1 for s in scenes if not s.map),
            "scenes_dropped_no_exposure": sum(1 for s in scenes if s.map and not s.exposures),
            "spawners_skipped": sum(s.skipped_spawners for s in on_map),
            "maps": dict(sorted(Counter(s.map for s in on_map).items())),
        },
        "assumptions": [
            "Scene frame: metres, y up, positions at the feet, forward (-sin yaw, 0, -cos yaw).",
            "Eye and bot head centre both 1.75 m above the feet; elevation follows the floor difference.",
            "Offsets are measured from the player spawn and spawn yaw; scenes where the player walks a route add noise.",
            "A crossing bot is seen at each waypoint after the first, moving with the speed of the leg that ends there.",
            "A strafing bot moves across the line of sight at its mean strafe speed.",
            "Arena drills without a map are dropped; each remaining scene has weight 1, split over its points.",
            "Community scenes are user-made drills, not match data: they show what players train, not match frequency.",
        ],
        "head_diameter_m": HEAD_DIAMETER,
        "groups": {name: group_stats(g) for name, g in groups.items()},
        "timings": timing_stats(on_map),
    })


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="Aggregate Valorant engagement statistics from open-aim.")
    parser.add_argument("root", type=Path, help="local open-aim checkout")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    report = build_report(args.root)
    args.out.write_text(json.dumps(report, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    log.info("wrote %s (%d scenes used)", args.out, report["source"]["scenes_used"])


if __name__ == "__main__":
    main()
