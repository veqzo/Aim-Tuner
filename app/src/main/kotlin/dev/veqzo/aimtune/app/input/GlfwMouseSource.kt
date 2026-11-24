package dev.veqzo.aimtune.app.input

import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.NanoClock
import dev.veqzo.aimtune.core.input.SystemNanoClock
import dev.veqzo.aimtune.input.RawMouseSource
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWCursorPosCallback
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Fallback source on GLFW. While the cursor is disabled (captured) GLFW reports a virtual cursor
 * position; with raw mouse motion enabled its deltas are device counts. Callbacks run inside the
 * frame's event polling on the render thread, so timestamps are quantized to the frame rate and
 * are much coarser than [dev.veqzo.aimtune.input.windows.WindowsRawInputSource]. Do not run both
 * at once: only one window per process can hold the raw mouse registration.
 */
class GlfwMouseSource(
    private val windowHandle: Long,
    override val clock: NanoClock = SystemNanoClock,
) : RawMouseSource {
    override val name = "GLFW raw motion (fallback)"

    private val queue = ConcurrentLinkedQueue<MouseEvent>()
    private val received = AtomicLong()

    @Volatile private var focused = false
    private var previousCallback: GLFWCursorPosCallback? = null
    private var lastX = Double.NaN
    private var lastY = Double.NaN
    private var remainderX = 0.0
    private var remainderY = 0.0

    private val callback = GLFWCursorPosCallback.create { window, x, y ->
        previousCallback?.invoke(window, x, y)
        onCursorPosition(x, y)
    }

    override val hasReportTimestamps = false

    override val eventsReceived: Long get() = received.get()

    override fun start() {
        previousCallback = GLFW.glfwSetCursorPosCallback(windowHandle, callback)
        if (GLFW.glfwRawMouseMotionSupported()) {
            GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_TRUE)
        } else {
            System.getLogger("aimtune.input.glfw")
                .log(System.Logger.Level.WARNING, "GLFW raw mouse motion is not supported; deltas are not raw counts")
        }
    }

    override fun setWindowFocused(focused: Boolean) {
        this.focused = focused
    }

    override fun drain(consumer: (MouseEvent) -> Unit) {
        while (true) consumer(queue.poll() ?: return)
    }

    override fun close() {
        focused = false
        GLFW.glfwSetCursorPosCallback(windowHandle, previousCallback)
        GLFW.glfwSetInputMode(windowHandle, GLFW.GLFW_RAW_MOUSE_MOTION, GLFW.GLFW_FALSE)
        callback.free()
    }

    private fun onCursorPosition(x: Double, y: Double) {
        val captured = GLFW.glfwGetInputMode(windowHandle, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_DISABLED
        if (!captured || !focused) {
            // Positions are only deltas of device motion while captured; reset so re-capture does not jump.
            lastX = Double.NaN
            return
        }
        if (!lastX.isNaN()) {
            val totalX = remainderX + (x - lastX)
            val totalY = remainderY + (y - lastY)
            // Truncate toward zero and carry the fractional part into the next report.
            val dx = totalX.toInt()
            val dy = totalY.toInt()
            remainderX = totalX - dx
            remainderY = totalY - dy
            if (dx != 0 || dy != 0) {
                queue.add(MouseEvent(clock.nowNanos(), dx, dy))
                received.incrementAndGet()
            }
        }
        lastX = x
        lastY = y
    }
}
