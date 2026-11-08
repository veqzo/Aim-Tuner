"""Weighted quantiles and a scene-level bootstrap for the median."""
from __future__ import annotations

import random
from collections.abc import Sequence

QUANTILES = (0.10, 0.25, 0.50, 0.75, 0.90)


def weighted_quantile(values: Sequence[float], weights: Sequence[float], q: float) -> float:
    """Quantile of the weighted empirical distribution (midpoint rule, no interpolation
    across gaps wider than one sample)."""
    pairs = sorted(zip(values, weights))
    total = sum(w for _, w in pairs)
    if total <= 0:
        raise ValueError("weights must sum to more than 0")
    target = q * total
    running = 0.0
    for value, w in pairs:
        running += w
        if running >= target - 1e-12:
            return value
    return pairs[-1][0]


def summary(groups: Sequence[Sequence[float]], seed: int = 23, resamples: int = 1000) -> dict:
    """`groups` holds the values of one scene each. Every scene has weight 1, split
    equally over its values, so a scene with many bots does not dominate. The median
    CI resamples whole scenes, because points inside one scene are not independent."""
    groups = [list(g) for g in groups if g]
    if not groups:
        return {"n_scenes": 0, "n_points": 0}
    values = [v for g in groups for v in g]
    weights = [1.0 / len(g) for g in groups for _ in g]
    out = {
        "n_scenes": len(groups),
        "n_points": len(values),
        "mean": sum(v * w for v, w in zip(values, weights)) / sum(weights),
    }
    for q in QUANTILES:
        out[f"p{int(q * 100)}"] = weighted_quantile(values, weights, q)
    rng = random.Random(seed)
    medians = []
    for _ in range(resamples):
        pick = [groups[rng.randrange(len(groups))] for _ in groups]
        medians.append(
            weighted_quantile([v for g in pick for v in g], [1.0 / len(g) for g in pick for _ in g], 0.5)
        )
    medians.sort()
    out["p50_ci95"] = [medians[int(0.025 * resamples)], medians[int(0.975 * resamples) - 1]]
    return out


def rounded(obj, digits: int = 3):
    if isinstance(obj, float):
        return round(obj, digits)
    if isinstance(obj, dict):
        return {k: rounded(v, digits) for k, v in obj.items()}
    if isinstance(obj, list):
        return [rounded(v, digits) for v in obj]
    return obj
