package dev.veqzo.aimtune.app

import com.badlogic.gdx.graphics.PerspectiveCamera
import com.badlogic.gdx.math.Vector3
import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.NanoClock
import dev.veqzo.aimtune.core.math.Vec3
import dev.veqzo.aimtune.games.GameProfile
import dev.veqzo.aimtune.games.GameProfiles
import dev.veqzo.aimtune.input.RawMouseSource
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unit chain end to end (review): DPI x in-game sensitivity x game yaw -> degrees per count ->
 * the view of the trainer -> the projection on screen. One raw count must turn the trainer's view
 * by exactly what the game turns it, and the game's horizontal FOV must be what the screen shows.
 */
class UnitChainTest {
    private class FakeSource : RawMouseSource {
        var now = 0L
        private val queue = ConcurrentLinkedQueue<MouseEvent>()
        override val name = "fake"
        override val clock = NanoClock { now }
        override var eventsReceived = 0L
        override fun start() = Unit
        override fun setWindowFocused(focused: Boolean) = Unit
        override fun close() = Unit
        fun push(dx: Int, dy: Int) {
            queue.add(MouseEvent(now, dx, dy))
        }
        override fun drain(consumer: (MouseEvent) -> Unit) {
            while (true) consumer(queue.poll() ?: return)
        }
    }

    private val profiles = GameProfiles.load()

    private fun trainerFor(game: String, dpi: Double, sensitivity: Double, source: FakeSource): Trainer {
        val profile = checkNotNull(profiles.find(game))
        val config = TrainerConfig(dpi, profile.cm360(dpi, sensitivity), profile, roundSeconds = 10, seed = 1)
        return Trainer(config, source).also { it.startRound() }
    }

    @Test
    fun `one raw count turns the trainer view by sensitivity times game yaw`() {
        for ((game, dpi, sens) in listOf(Triple("valorant", 800.0, 0.249), Triple("valorant", 400.0, 0.5), Triple("cs2", 1600.0, 1.2), Triple("valorant", 3200.0, 0.1))) {
            val profile = checkNotNull(profiles.find(game))
            val expected = sens * profile.yawDegreesPerCount
            val source = FakeSource()
            val trainer = trainerFor(game, dpi, sens, source)
            source.push(1, 0)
            trainer.update()
            assertEquals(expected, trainer.view.yawDegrees, 1e-12, "$game $dpi dpi sens $sens: yaw per count")
            val angle = Vec3(0.0, 0.0, -1.0).angleToDegrees(trainer.view.direction())
            assertEquals(expected, angle, 1e-9, "$game: angle between the view directions")

            source.push(0, 1)
            trainer.update()
            assertEquals(-expected, trainer.view.pitchDegrees, 1e-12, "$game: pitch per count, mouse down looks down")
        }
    }

    @Test
    fun `counts for one physical 360 turn are the counts for one in-game 360 turn`() {
        val profile = checkNotNull(profiles.find("valorant"))
        val dpi = 800.0
        val sens = 0.249
        val cm360 = profile.cm360(dpi, sens)
        val counts = Math.round(dpi * cm360 / 2.54).toInt()
        val gameTurn = counts * sens * profile.yawDegreesPerCount
        // Rounding to whole counts leaves at most half a count of error.
        assertTrue(abs(gameTurn - 360.0) <= 0.5 * sens * profile.yawDegreesPerCount, "turn $gameTurn")
        val source = FakeSource()
        val trainer = trainerFor("valorant", dpi, sens, source)
        source.push(counts, 0)
        trainer.update()
        assertTrue(abs(trainer.view.yawDegrees) < 0.05, "view after a full physical turn: ${trainer.view.yawDegrees}")
    }

    private fun screenX(vertical: Double, width: Int, height: Int, yaw: Double): Double {
        com.badlogic.gdx.utils.GdxNativesLoader.load()
        val camera = PerspectiveCamera(vertical.toFloat(), width.toFloat(), height.toFloat()).apply { near = 0.05f; far = 200f }
        camera.update()
        val direction = dev.veqzo.aimtune.core.math.ViewAngles.directionOf(yaw, 0.0)
        val point = Vector3(direction.x.toFloat() * 10f, direction.y.toFloat() * 10f, direction.z.toFloat() * 10f)
        return camera.project(point, 0f, 0f, width.toFloat(), height.toFloat()).x.toDouble()
    }

    @Test
    fun `the horizontal FOV of the game profile is what the trainer shows at 16 to 9`() {
        for (game in listOf("valorant", "cs2")) {
            val profile: GameProfile = checkNotNull(profiles.find(game))
            val aspect16by9 = 16.0 / 9.0
            // The profile's FOV is horizontal at fovAspectRatio; at 16:9 the horizontal FOV in the trainer follows from the vertical one.
            val vertical = profile.verticalFovDegrees
            val horizontalAt169 = Math.toDegrees(2 * Math.atan(tan(Math.toRadians(vertical) / 2) * aspect16by9))
            val half = horizontalAt169 / 2
            val width = 1920
            val height = 1080
            assertEquals(width.toDouble(), screenX(vertical, width, height, half), 1.0, "$game: the edge of the horizontal FOV is the screen edge")
            assertEquals(0.0, screenX(vertical, width, height, -half), 1.0, "$game: left edge")
            // A point at the angle where the tangent says it must be.
            val quarter = Math.toDegrees(Math.atan(tan(Math.toRadians(half)) / 2))
            assertEquals(width * 0.75, screenX(vertical, width, height, quarter), 1.0, "$game: projection is rectilinear")
            if (game == "valorant") assertEquals(103.0, horizontalAt169, 1e-6, "Valorant: 103 degrees horizontal at 16:9")
        }
    }

    @Test
    fun `the picture is letterboxed to the profile aspect so the FOV does not depend on the window shape`() {
        val profile = checkNotNull(profiles.find("valorant"))
        for ((w, h) in listOf(1920 to 1080, 2560 to 1080, 1280 to 1024, 800 to 1200, 3440 to 1440)) {
            val box = letterbox(w, h, profile.trainerAspectRatio)
            assertEquals(profile.trainerAspectRatio, box.width.toDouble() / box.height, 0.01, "$w x $h")
            assertTrue(box.x >= 0 && box.y >= 0 && box.x + box.width <= w && box.y + box.height <= h, "$w x $h: box inside the window")
            assertTrue(abs(box.x - (w - box.x - box.width)) <= 1 && abs(box.y - (h - box.y - box.height)) <= 1, "$w x $h: centered")
            val horizontal = Math.toDegrees(2 * Math.atan(tan(Math.toRadians(profile.verticalFovDegrees) / 2) * box.width / box.height))
            assertEquals(103.0, horizontal, 0.5, "$w x $h: horizontal FOV of the drawn picture")
        }
    }
}
