"""Engagement geometry in the open-aim scene frame.

Frame facts, read from the open-aim engine bundle (site/next/js, movement code):
- positions are metres, y is up, the position is the feet of the actor;
- yaw is radians, forward is (-sin yaw, 0, -cos yaw) (three.js camera looks down -Z);
- player eye height is 1.75 m standing.
Bot head centres are taken at the same 1.75 m above the feet as the player eye (same
humanoid capsule), so the elevation angle depends only on the floor height difference.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

EYE_HEIGHT = 1.75


@dataclass(frozen=True)
class Vec:
    x: float
    y: float
    z: float

    def minus(self, other: Vec) -> Vec:
        return Vec(self.x - other.x, self.y - other.y, self.z - other.z)

    def horizontal_length(self) -> float:
        return math.hypot(self.x, self.z)


def vec(d: dict) -> Vec:
    return Vec(float(d["x"]), float(d.get("y", 0.0)), float(d["z"]))


def yaw_of_direction(dx: float, dz: float) -> float:
    """Inverse of forward = (-sin yaw, -cos yaw), in radians."""
    return math.atan2(-dx, -dz)


def wrap_degrees(angle: float) -> float:
    """Wrap to (-180, 180]."""
    a = (angle + 180.0) % 360.0 - 180.0
    return 180.0 if a == -180.0 else a


@dataclass(frozen=True)
class Sight:
    distance: float  # metres, eye to head centre, 3D
    yaw_offset: float  # degrees from the spawn yaw, signed
    elevation: float  # degrees, positive is up


def sight(eye_feet: Vec, spawn_yaw: float, target_feet: Vec) -> Sight:
    d = target_feet.minus(eye_feet)
    flat = d.horizontal_length()
    target_yaw = yaw_of_direction(d.x, d.z)
    return Sight(
        distance=math.hypot(flat, d.y),
        yaw_offset=wrap_degrees(math.degrees(target_yaw - spawn_yaw)),
        elevation=math.degrees(math.atan2(d.y, flat)),
    )


def angular_speed(eye_feet: Vec, target_feet: Vec, velocity: Vec) -> float:
    """Angular speed in deg/s of a target moving with `velocity`, seen from the eye.
    Only the part of the velocity across the line of sight moves the crosshair."""
    d = target_feet.minus(eye_feet)
    r2 = d.x * d.x + d.y * d.y + d.z * d.z
    if r2 < 1e-9:
        return 0.0
    # |d x v| / |d|^2 is the angular speed in rad/s.
    cx = d.y * velocity.z - d.z * velocity.y
    cy = d.z * velocity.x - d.x * velocity.z
    cz = d.x * velocity.y - d.y * velocity.x
    return math.degrees(math.sqrt(cx * cx + cy * cy + cz * cz) / r2)


def angular_size(diameter: float, distance: float) -> float:
    """Full angle in degrees that a sphere of `diameter` covers at `distance`."""
    return math.degrees(2.0 * math.atan2(diameter / 2.0, distance))
