package dev.veqzo.aimtune.input.windows

import com.sun.jna.Pointer

/** Relative movement decoded from one WM_INPUT mouse packet. */
internal data class RawMouseMove(val dx: Int, val dy: Int, val leftPressed: Boolean)

internal object RawInputParser {
    /** Bytes a mouse packet occupies in the buffer filled by GetRawInputData. */
    val mouseInputSize: Int by lazy { RawMouseInput().size() }

    /** Size of RAWINPUTHEADER, required as `cbSizeHeader` by GetRawInputData. */
    val headerSize: Int by lazy { RawInputHeader().size() }

    /**
     * Decodes the packet in [buffer] ([bytesCopied] valid bytes). Returns null for non-mouse
     * packets, truncated data, absolute-position devices and reports without movement.
     */
    fun parseRelativeMove(buffer: Pointer, bytesCopied: Int): RawMouseMove? {
        if (bytesCopied < mouseInputSize) return null
        val input = RawMouseInput(buffer)
        input.read()
        if (input.header.dwType != RIM_TYPEMOUSE) return null
        if (input.mouse.usFlags.toInt() and MOUSE_MOVE_ABSOLUTE != 0) return null
        val leftPressed = input.mouse.ulButtons and RI_MOUSE_LEFT_BUTTON_DOWN != 0
        if (input.mouse.lLastX == 0 && input.mouse.lLastY == 0 && !leftPressed) return null
        return RawMouseMove(input.mouse.lLastX, input.mouse.lLastY, leftPressed)
    }
}
