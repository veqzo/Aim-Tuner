package dev.veqzo.aimtune.input.windows

/**
 * Windows pointer acceleration ("Enhance pointer precision") as reported by SPI_GETMOUSE. The
 * level is 0 when off; a non-zero level makes Windows multiply pointer movement above the
 * thresholds. Raw input is not affected, but the setting changes how the game feels outside the
 * trainer, so the app tells the user about it.
 */
data class PointerAcceleration(val level: Int) {
    val enabled: Boolean get() = level != 0
}

object WindowsPointerSettings {
    private const val SPI_GETMOUSE_VALUES = 3
    private const val ACCELERATION_INDEX = 2

    /** Null when not running on Windows or when the system call fails. */
    fun readAcceleration(): PointerAcceleration? {
        if (!System.getProperty("os.name").startsWith("Windows")) return null
        val values = IntArray(SPI_GETMOUSE_VALUES)
        if (!User32Raw.INSTANCE.SystemParametersInfoW(SPI_GETMOUSE, 0, values, 0)) return null
        return PointerAcceleration(values[ACCELERATION_INDEX])
    }
}
