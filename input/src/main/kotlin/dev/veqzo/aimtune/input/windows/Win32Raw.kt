package dev.veqzo.aimtune.input.windows

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary

// Constants and signatures verified against Microsoft Learn (winuser.h docs), see ADR 0002.
internal const val WM_INPUT = 0x00FF
internal const val WM_CLOSE = 0x0010
internal const val WM_DESTROY = 0x0002
internal const val WM_APP = 0x8000

internal const val RID_INPUT = 0x10000003
internal const val RIM_TYPEMOUSE = 0

/** RAWMOUSE usButtonFlags: the left button went down (the low 16 bits of ulButtons). */
internal const val RI_MOUSE_LEFT_BUTTON_DOWN = 0x0001
internal const val RIM_INPUTSINK = 1

internal const val HID_USAGE_PAGE_GENERIC: Short = 0x01
internal const val HID_USAGE_GENERIC_MOUSE: Short = 0x02

internal const val RIDEV_REMOVE = 0x00000001
internal const val RIDEV_INPUTSINK = 0x00000100

internal const val MOUSE_MOVE_ABSOLUTE = 0x01

/** SystemParametersInfo action: three ints, the third is the mouse acceleration level (0 = off). */
internal const val SPI_GETMOUSE = 0x0003

/** Raw input functions that JNA's platform module does not wrap. */
@Suppress("FunctionName")
internal interface User32Raw : StdCallLibrary {
    fun RegisterRawInputDevices(devices: RawInputDevice, count: Int, size: Int): Boolean

    fun SystemParametersInfoW(action: Int, param: Int, value: IntArray, winIni: Int): Boolean

    /** Returns bytes copied, or -1 on error (UINT cast to Int). */
    fun GetRawInputData(rawInput: Pointer, command: Int, data: Pointer?, size: IntByReference, headerSize: Int): Int

    companion object {
        val INSTANCE: User32Raw by lazy { Native.load("user32", User32Raw::class.java) }
    }
}

@Suppress("FunctionName")
internal interface Kernel32Raw : StdCallLibrary {
    fun QueryPerformanceCounter(counter: LongByReference): Boolean
    fun QueryPerformanceFrequency(frequency: LongByReference): Boolean

    companion object {
        val INSTANCE: Kernel32Raw by lazy { Native.load("kernel32", Kernel32Raw::class.java) }
    }
}
