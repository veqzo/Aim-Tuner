package dev.veqzo.aimtune.input.windows

import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Structure.FieldOrder

/** RAWINPUTHEADER: dwType, dwSize, hDevice, wParam. */
@FieldOrder("dwType", "dwSize", "hDevice", "wParam")
internal open class RawInputHeader : Structure() {
    @JvmField var dwType: Int = 0
    @JvmField var dwSize: Int = 0
    @JvmField var hDevice: Pointer? = null
    @JvmField var wParam: Pointer? = null
}

/**
 * RAWMOUSE. The C union of ulButtons / (usButtonFlags, usButtonData) is modeled as one 4-byte
 * [ulButtons] so it keeps its 4-byte alignment after the 2-byte [usFlags].
 */
@FieldOrder("usFlags", "ulButtons", "ulRawButtons", "lLastX", "lLastY", "ulExtraInformation")
internal open class RawMouse : Structure() {
    @JvmField var usFlags: Short = 0
    @JvmField var ulButtons: Int = 0
    @JvmField var ulRawButtons: Int = 0
    @JvmField var lLastX: Int = 0
    @JvmField var lLastY: Int = 0
    @JvmField var ulExtraInformation: Int = 0
}

/** RAWINPUT for a mouse packet: header followed by the RAWMOUSE member of the data union. */
@FieldOrder("header", "mouse")
internal open class RawMouseInput(pointer: Pointer?) : Structure(pointer) {
    constructor() : this(null)

    @JvmField var header: RawInputHeader = RawInputHeader()
    @JvmField var mouse: RawMouse = RawMouse()
}

/** RAWINPUTDEVICE: usUsagePage, usUsage, dwFlags, hwndTarget. */
@FieldOrder("usUsagePage", "usUsage", "dwFlags", "hwndTarget")
internal open class RawInputDevice : Structure() {
    @JvmField var usUsagePage: Short = 0
    @JvmField var usUsage: Short = 0
    @JvmField var dwFlags: Int = 0
    @JvmField var hwndTarget: Pointer? = null
}
