package dev.veqzo.aimtune.app.screens

private const val TWO_COLUMN_MIN_WIDTH = 1280f
private const val MAX_COLUMN_WIDTH = 820f
private const val ONE_COLUMN_FACTOR = 1.2f

/** Column layout of the Raw Accel screens, from the width of the stage in design units (1080 high, so 1920 at 16:9). */
object HubLayout {
    const val COLUMN_GAP = 56f

    /** Two columns from 1280 units on, so 1920×1080 and 1280×720 windows have two and a narrow window one. */
    fun twoColumns(stageWidth: Float): Boolean = stageWidth >= TWO_COLUMN_MIN_WIDTH

    /** The width of a column: half the room between the margins in two columns (at most 820), the whole room (at most 984) in one. */
    fun columnWidth(stageWidth: Float, margin: Float): Float {
        val available = stageWidth - 2 * margin
        return if (twoColumns(stageWidth)) minOf((available - COLUMN_GAP) / 2, MAX_COLUMN_WIDTH) else minOf(available, MAX_COLUMN_WIDTH * ONE_COLUMN_FACTOR)
    }
}
