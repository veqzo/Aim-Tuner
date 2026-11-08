import math
import unittest

from geometry import Vec, angular_size, angular_speed, sight, wrap_degrees, yaw_of_direction
from scenes import classify, scene_from_doc
from stats import summary, weighted_quantile


def doc(actors, spawners=(), yaw=0.0):
    return {
        "environment": {"map": "ascent"},
        "playerSpawn": {"position": {"x": 0, "y": 0, "z": 0}, "yaw": yaw},
        "actors": list(actors),
        "spawners": list(spawners),
        "rules": {"duration": 60, "playerMovable": True},
    }


class GeometryTest(unittest.TestCase):
    def test_forward_is_minus_z_at_yaw_zero(self):
        s = sight(Vec(0, 0, 0), 0.0, Vec(0, 0, -10))
        self.assertAlmostEqual(s.yaw_offset, 0.0)
        self.assertAlmostEqual(s.distance, 10.0)

    def test_yaw_matches_engine_forward(self):
        for yaw in (0.3, 1.7, -2.5):
            self.assertAlmostEqual(yaw_of_direction(-math.sin(yaw), -math.cos(yaw)), yaw)

    def test_target_to_the_side_is_ninety_degrees(self):
        self.assertAlmostEqual(abs(sight(Vec(0, 0, 0), 0.0, Vec(10, 0, 0)).yaw_offset), 90.0)

    def test_elevation_follows_floor_difference(self):
        self.assertAlmostEqual(sight(Vec(0, 0, 0), 0.0, Vec(0, 10, -10)).elevation, 45.0)

    def test_wrap(self):
        self.assertAlmostEqual(wrap_degrees(270.0), -90.0)
        self.assertAlmostEqual(wrap_degrees(-180.0), 180.0)

    def test_angular_speed_across_and_along(self):
        across = angular_speed(Vec(0, 0, 0), Vec(0, 0, -10), Vec(5.4, 0, 0))
        self.assertAlmostEqual(across, math.degrees(0.54))
        self.assertAlmostEqual(angular_speed(Vec(0, 0, 0), Vec(0, 0, -10), Vec(0, 0, 5.4)), 0.0)

    def test_head_size(self):
        self.assertAlmostEqual(angular_size(0.24, 20.0), math.degrees(2 * math.atan(0.006)))


class SceneTest(unittest.TestCase):
    def test_cross_skips_the_hidden_start(self):
        actor = {"id": "a", "behaviors": [{"kind": "cross", "speed": 5.4,
                 "waypoints": [{"x": -5, "y": 0, "z": -10}, {"x": 0, "y": 0, "z": -10}]}]}
        s = scene_from_doc("curated", "k", doc([actor]))
        self.assertEqual(len(s.exposures), 1)
        self.assertAlmostEqual(s.exposures[0].speed, math.degrees(0.54))
        self.assertEqual(s.kind, "peek")

    def test_static_spawner_and_strafe(self):
        still = {"id": "a", "behaviors": []}
        strafer = {"id": "b", "behaviors": [{"kind": "strafe", "speed": [5.4, 5.4]}]}
        spawners = [{"kind": "fixed", "actorTemplateId": "a", "position": {"x": 0, "y": 0, "z": -10}},
                    {"kind": "area", "actorTemplateId": "b", "position": {"x": 0, "y": 0, "z": -20}},
                    {"kind": "grid", "actorTemplateId": "a"}]
        s = scene_from_doc("community", "k", doc([still, strafer], spawners))
        self.assertEqual([e.motion for e in s.exposures], ["static", "strafe"])
        self.assertAlmostEqual(s.exposures[1].speed, math.degrees(5.4 / 20))
        self.assertEqual(s.skipped_spawners, 1)

    def test_flash_classification_wins(self):
        actor = {"id": "a", "behaviors": [{"kind": "cross", "waypoints": [], "flashStops": [{"wait": 1.2}]}]}
        self.assertEqual(classify(doc([actor])), "flash")
        self.assertEqual(classify(doc([{"id": "b", "behaviors": []}])), "static")


class StatsTest(unittest.TestCase):
    def test_weighted_median(self):
        self.assertEqual(weighted_quantile([1, 2, 3], [1, 1, 1], 0.5), 2)
        self.assertEqual(weighted_quantile([1, 2, 3], [0.1, 0.1, 5], 0.5), 3)

    def test_each_scene_counts_once(self):
        # One scene with many points must not outweigh two scenes with one point each.
        s = summary([[10.0] * 50, [1.0], [1.0]], resamples=50)
        self.assertEqual(s["p50"], 1.0)
        self.assertEqual(s["n_scenes"], 3)
        self.assertEqual(s["n_points"], 52)


if __name__ == "__main__":
    unittest.main()
