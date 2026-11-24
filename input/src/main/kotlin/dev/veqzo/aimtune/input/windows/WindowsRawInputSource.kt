package dev.veqzo.aimtune.input.windows

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import dev.veqzo.aimtune.core.input.MouseEvent
import dev.veqzo.aimtune.core.input.NanoClock
import dev.veqzo.aimtune.input.RawMouseSource
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Windows Raw Input (WM_INPUT) mouse source. A dedicated thread owns a message-only window and
 * its message loop, and stamps every report with [clock] on arrival.
 *
 * Focus rule: the mouse device is registered (RIDEV_INPUTSINK, required because a message-only
 * window never has focus) only while [setWindowFocused] says the application window is focused.
 * On focus loss the registration is removed, and events that still arrive are dropped unqueued.
 *
 * Only one window per device class per process can be registered, so no other component in this
 * process (for example GLFW raw mouse motion) may register the mouse while this source runs.
 */
class WindowsRawInputSource(override val clock: NanoClock = QpcClock()) : RawMouseSource {
    override val name = "Windows Raw Input (JNA)"

    private val log = System.getLogger("aimtune.input.rawinput")
    private val queue = ConcurrentLinkedQueue<MouseEvent>()
    private val queued = AtomicInteger()
    private val received = AtomicLong()
    private val droppedUnfocused = AtomicLong()
    private val droppedOverflow = AtomicLong()
    private val sinkEvents = AtomicLong()

    @Volatile private var focused = false

    @Volatile private var window: HWND? = null
    private var thread: Thread? = null

    // Accessed only on the input thread.
    private var registered = false
    private val readBuffer by lazy { Memory(READ_BUFFER_BYTES.toLong()) }

    // The callback object must stay strongly referenced for as long as the window class exists.
    private val windowProc = object : WinUser.WindowProc {
        override fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = handleMessage(hwnd, uMsg, wParam, lParam)
    }

    override val reportsClicks = true

    override val droppedEvents: Long get() = droppedOverflow.get()

    override val eventsReceived: Long get() = received.get()

    /** Events that arrived flagged as background input (diagnostic only, not used for gating). */
    val backgroundFlaggedEvents: Long get() = sinkEvents.get()

    override fun start() {
        check(thread == null) { "already started" }
        val ready = CountDownLatch(1)
        val t = Thread({ runMessageLoop(ready) }, "aimtune-raw-input").apply { isDaemon = true }
        thread = t
        t.start()
        check(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS) && window != null) {
            "raw input window was not created within $START_TIMEOUT_SECONDS s"
        }
    }

    override fun setWindowFocused(focused: Boolean) {
        this.focused = focused
        val hwnd = window ?: return
        User32.INSTANCE.PostMessage(hwnd, WM_APP_FOCUS, WPARAM(if (focused) 1 else 0), LPARAM(0))
    }

    override fun drain(consumer: (MouseEvent) -> Unit) {
        while (true) {
            val event = queue.poll() ?: return
            queued.decrementAndGet()
            consumer(event)
        }
    }

    override fun close() {
        focused = false
        val hwnd = window
        val t = thread ?: return
        if (hwnd != null) User32.INSTANCE.PostMessage(hwnd, WM_CLOSE, WPARAM(0), LPARAM(0))
        t.join(JOIN_TIMEOUT_MILLIS)
        if (t.isAlive) log.log(System.Logger.Level.WARNING, "raw input thread did not stop in time")
        thread = null
        window = null
    }

    private fun runMessageLoop(ready: CountDownLatch) {
        val instance = Kernel32.INSTANCE.GetModuleHandle(null)
        val className = "AimTuneRawInput-${ProcessHandle.current().pid()}"
        val windowClass = WinUser.WNDCLASSEX().apply {
            cbSize = size()
            lpfnWndProc = windowProc
            hInstance = instance
            lpszClassName = className
        }
        try {
            if (User32.INSTANCE.RegisterClassEx(windowClass) == null) {
                log.log(System.Logger.Level.ERROR, "RegisterClassEx failed, error ${Kernel32.INSTANCE.GetLastError()}")
                return
            }
            window = User32.INSTANCE.CreateWindowEx(0, className, className, 0, 0, 0, 0, 0, MESSAGE_ONLY_PARENT, null, instance, null)
            if (window == null) {
                log.log(System.Logger.Level.ERROR, "CreateWindowEx failed, error ${Kernel32.INSTANCE.GetLastError()}")
                return
            }
        } finally {
            ready.countDown()
        }
        val message = WinUser.MSG()
        while (User32.INSTANCE.GetMessage(message, null, 0, 0) > 0) {
            User32.INSTANCE.TranslateMessage(message)
            User32.INSTANCE.DispatchMessage(message)
        }
        User32.INSTANCE.UnregisterClass(className, instance)
    }

    private fun handleMessage(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT = when (uMsg) {
        WM_INPUT -> {
            handleRawInput(wParam, lParam)
            // Required by the API so the system can clean up the raw input data.
            User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam)
        }
        WM_APP_FOCUS -> {
            applyRegistration(hwnd, wParam.toInt() != 0)
            LRESULT(0)
        }
        WM_CLOSE -> {
            applyRegistration(hwnd, false)
            User32.INSTANCE.DestroyWindow(hwnd)
            LRESULT(0)
        }
        WM_DESTROY -> {
            User32.INSTANCE.PostQuitMessage(0)
            LRESULT(0)
        }
        else -> User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam)
    }

    private fun handleRawInput(wParam: WPARAM, lParam: LPARAM) {
        val arrival = clock.nowNanos()
        if (!focused) {
            droppedUnfocused.incrementAndGet()
            return
        }
        val size = IntByReference(READ_BUFFER_BYTES)
        val copied = User32Raw.INSTANCE.GetRawInputData(
            Pointer.createConstant(lParam.toLong()), RID_INPUT, readBuffer, size, RawInputParser.headerSize,
        )
        if (copied <= 0) return
        val move = RawInputParser.parseRelativeMove(readBuffer, copied) ?: return
        if (wParam.toInt() == RIM_INPUTSINK) sinkEvents.incrementAndGet()
        if (queued.get() >= MAX_QUEUED_EVENTS) {
            droppedOverflow.incrementAndGet()
            return
        }
        queue.add(MouseEvent(arrival, move.dx, move.dy, move.leftPressed))
        queued.incrementAndGet()
        received.incrementAndGet()
    }

    private fun applyRegistration(hwnd: HWND, enable: Boolean) {
        if (enable == registered) return
        val device = RawInputDevice().apply {
            usUsagePage = HID_USAGE_PAGE_GENERIC
            usUsage = HID_USAGE_GENERIC_MOUSE
            // RIDEV_REMOVE requires a null target window.
            dwFlags = if (enable) RIDEV_INPUTSINK else RIDEV_REMOVE
            hwndTarget = if (enable) hwnd.pointer else null
        }
        if (User32Raw.INSTANCE.RegisterRawInputDevices(device, 1, device.size())) {
            registered = enable
        } else {
            log.log(System.Logger.Level.ERROR, "RegisterRawInputDevices(enable=$enable) failed, error ${Kernel32.INSTANCE.GetLastError()}")
        }
    }

    private companion object {
        /** HWND_MESSAGE is defined as ((HWND)-3); built with a long so it sign-extends on 64-bit. */
        val MESSAGE_ONLY_PARENT = HWND(Pointer.createConstant(-3L))

        const val WM_APP_FOCUS = WM_APP + 1
        const val READ_BUFFER_BYTES = 256
        const val MAX_QUEUED_EVENTS = 200_000
        const val START_TIMEOUT_SECONDS = 3L
        const val JOIN_TIMEOUT_MILLIS = 2_000L
    }
}
