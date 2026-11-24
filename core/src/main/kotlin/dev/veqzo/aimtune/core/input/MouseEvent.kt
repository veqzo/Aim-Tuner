package dev.veqzo.aimtune.core.input

/**
 * One relative mouse report: raw counts [dx]/[dy] (positive x = right, positive y = down) and the
 * arrival time in nanoseconds on a monotonic clock whose origin is arbitrary but fixed per source.
 * [leftPressed] is set when the same device report also carried the left button going down, so a
 * click is ordered exactly against the movement before and after it (a report without movement but
 * with a click has dx = dy = 0).
 */
data class MouseEvent(val timeNanos: Long, val dx: Int, val dy: Int, val leftPressed: Boolean = false)
