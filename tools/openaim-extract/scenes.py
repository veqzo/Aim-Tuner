"""Read open-aim scene documents and turn each map scene into exposure points.

An exposure point is a place where the player sees a bot, with the motion of the bot
there. Only numbers leave this module; no scene text or layout is kept.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from pathlib import Path

from geometry import Sight, Vec, angular_speed, sight, vec

log = logging.getLogger(__name__)

CURATED_DIR = Path("scenarios")
COMMUNITY_DIR = Path("site/prod-api/api/v1/public/val/aimlab/scenes")
POINT_SPAWNERS = ("fixed", "area")


@dataclass(frozen=True)
class Exposure:
    sight: Sight
    speed: float  # angular speed in deg/s, 0 for a standing bot
    motion: str  # static | strafe | cross | path


@dataclass(frozen=True)
class Scene:
    source: str  # curated | community
    key: str
    map: str | None
    kind: str  # flash | peek | static
    player_movable: bool
    play_count: int
    exposures: tuple[Exposure, ...]
    timings: dict[str, tuple[float, ...]] = field(default_factory=dict)
    skipped_spawners: int = 0


def mean_of(value) -> float | None:
    """open-aim writes ranges as [min, max] and fixed values as a number."""
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, list) and value and all(isinstance(v, (int, float)) for v in value):
        return sum(value) / len(value)
    return None


def leg_speed(behavior: dict, leg: int) -> float:
    legs = behavior.get("legSpeeds")
    if isinstance(legs, list) and leg < len(legs):
        own = mean_of(legs[leg])
        if own is not None:
            return own
    return mean_of(behavior.get("speed")) or 0.0


def route_exposures(eye: Vec, yaw: float, behavior: dict, motion: str) -> list[Exposure]:
    """A crossing bot starts hidden at waypoint 0 (the engine places it there on spawn).
    Each later waypoint is taken as seen, with the velocity of the leg that ends there."""
    points = [vec(w) for w in behavior.get("waypoints", [])]
    out = []
    for i in range(1, len(points)):
        a, b = points[i - 1], points[i]
        d = b.minus(a)
        length = (d.x * d.x + d.y * d.y + d.z * d.z) ** 0.5
        v = leg_speed(behavior, i - 1)
        velocity = Vec(d.x / length * v, d.y / length * v, d.z / length * v) if length > 1e-6 else Vec(0, 0, 0)
        out.append(Exposure(sight(eye, yaw, b), angular_speed(eye, b, velocity), motion))
    return out


def standing_exposure(eye: Vec, yaw: float, at: Vec, behaviors: list[dict]) -> Exposure:
    strafe = next((b for b in behaviors if b.get("kind") == "strafe"), None)
    s = sight(eye, yaw, at)
    if strafe is None:
        return Exposure(s, 0.0, "static")
    # A strafing bot faces the player, so it moves across the line of sight.
    flat = max(at.minus(eye).horizontal_length(), 1e-6)
    speed = mean_of(strafe.get("speed")) or 0.0
    return Exposure(s, (speed / flat) * 57.29577951308232, "strafe")


def classify(doc: dict) -> str:
    behaviors = [b for a in doc.get("actors", []) for b in a.get("behaviors", [])]
    if any(b.get("kind") == "cross" and b.get("flashStops") for b in behaviors):
        return "flash"
    if any(b.get("kind") in ("cross", "strafe", "path") for b in behaviors):
        return "peek"
    return "static"


def collect_timings(doc: dict) -> dict[str, tuple[float, ...]]:
    found: dict[str, list[float]] = {}

    def add(name: str, value) -> None:
        m = mean_of(value)
        if m is not None:
            found.setdefault(name, []).append(m)

    for actor in doc.get("actors", []):
        for b in actor.get("behaviors", []):
            kind = b.get("kind")
            if kind == "shooter":
                add("bot_reaction_s", b.get("reaction"))
                add("bot_aim_error_deg", b.get("aimErrorDeg"))
            elif kind == "cross":
                add("cross_interval_s", b.get("interval"))
                for stop in b.get("flashStops") or []:
                    add("flash_wait_s", stop.get("wait"))
            elif kind == "strafe":
                add("strafe_dwell_s", b.get("dwell"))
                add("strafe_travel_s", b.get("travel"))
    for s in doc.get("spawners", []):
        add("spawn_delay_s", s.get("spawnDelay"))
    for d in doc.get("dispatchers") or []:
        add("dispatch_interval_s", d.get("interval"))
    add("round_duration_s", doc.get("rules", {}).get("duration"))
    return {k: tuple(v) for k, v in found.items()}


def scene_from_doc(source: str, key: str, doc: dict, play_count: int = 0) -> Scene:
    spawn = doc["playerSpawn"]
    eye = vec(spawn["position"])
    yaw = float(spawn.get("yaw", 0.0))
    actors = {a["id"]: a for a in doc.get("actors", [])}
    exposures: list[Exposure] = []
    skipped = 0
    for s in doc.get("spawners", []):
        actor = actors.get(s.get("actorTemplateId"))
        if s.get("kind") not in POINT_SPAWNERS or actor is None or "position" not in s:
            skipped += 1
            continue
        exposures.append(standing_exposure(eye, yaw, vec(s["position"]), actor.get("behaviors", [])))
    for actor in actors.values():
        for b in actor.get("behaviors", []):
            if b.get("kind") in ("cross", "path"):
                exposures.extend(route_exposures(eye, yaw, b, b["kind"]))
    return Scene(
        source=source,
        key=key,
        map=doc.get("environment", {}).get("map"),
        kind=classify(doc),
        player_movable=bool(doc.get("rules", {}).get("playerMovable")),
        play_count=int(play_count or 0),
        exposures=tuple(exposures),
        timings=collect_timings(doc),
        skipped_spawners=skipped,
    )


def load_scenes(root: Path) -> list[Scene]:
    scenes = []
    for p in sorted((root / CURATED_DIR).glob("*.json")):
        d = json.loads(p.read_text(encoding="utf-8"))
        item = d["item"]
        scenes.append(scene_from_doc("curated", item["shareCode"], json.loads(d["scene"]["doc"]), item.get("playCount", 0)))
    for p in sorted((root / COMMUNITY_DIR).iterdir()):
        try:
            d = json.loads(p.read_text(encoding="utf-8"))["data"]
            scenes.append(scene_from_doc("community", d["shareCode"], json.loads(d["doc"]), d.get("playCount", 0)))
        except (KeyError, ValueError) as err:
            log.warning("skip %s: %s", p.name, err)
    return scenes
