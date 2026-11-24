package dev.veqzo.aimtune.storage

import dev.veqzo.aimtune.core.input.MouseEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipException

/**
 * Compact on-disk form of a round's mouse reports: one format-version byte followed by a deflated
 * stream of zigzag varints (time since the previous event, dx, dy). The first time delta is
 * relative to the round's origin. The event count is stored next to the blob, not inside it.
 */
object MouseEventCodec {
    private const val FORMAT_VERSION: Byte = 1
    private const val VARINT_PAYLOAD_BITS = 7
    private const val VARINT_PAYLOAD_MASK = 0x7F
    private const val VARINT_CONTINUE = 0x80
    private const val MAX_VARINT_BYTES = 10

    fun encode(events: List<MouseEvent>, originNanos: Long): ByteArray {
        val raw = ByteArrayOutputStream()
        var previous = originNanos
        for (e in events) {
            writeVarint(raw, zigzag(e.timeNanos - previous))
            writeVarint(raw, zigzag(e.dx.toLong()))
            writeVarint(raw, zigzag(e.dy.toLong()))
            previous = e.timeNanos
        }
        val out = ByteArrayOutputStream()
        out.write(FORMAT_VERSION.toInt())
        DeflaterOutputStream(out).use { it.write(raw.toByteArray()) }
        return out.toByteArray()
    }

    /** @throws IllegalArgumentException if the data is not a valid blob with exactly [count] events. */
    fun decode(bytes: ByteArray, count: Int, originNanos: Long): List<MouseEvent> {
        require(bytes.isNotEmpty() && bytes[0] == FORMAT_VERSION) { "unsupported mouse event format" }
        val input = try {
            InflaterInputStream(ByteArrayInputStream(bytes, 1, bytes.size - 1)).readAllBytes().let(::ByteArrayInputStream)
        } catch (e: ZipException) {
            throw IllegalArgumentException("corrupt mouse event data", e)
        }
        return try {
            var previous = originNanos
            val events = List(count) {
                previous += unzigzag(readVarint(input))
                MouseEvent(previous, unzigzag(readVarint(input)).toInt(), unzigzag(readVarint(input)).toInt())
            }
            require(input.available() == 0) { "mouse event data has more than $count events" }
            events
        } catch (e: EOFException) {
            throw IllegalArgumentException("mouse event data has fewer than $count events", e)
        }
    }

    private fun zigzag(value: Long): Long = (value shl 1) xor (value shr 63)

    private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v and VARINT_PAYLOAD_MASK.toLong().inv() != 0L) {
            out.write(((v and VARINT_PAYLOAD_MASK.toLong()).toInt()) or VARINT_CONTINUE)
            v = v ushr VARINT_PAYLOAD_BITS
        }
        out.write(v.toInt())
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        var shift = 0
        repeat(MAX_VARINT_BYTES) {
            val b = input.read()
            if (b < 0) throw EOFException()
            result = result or ((b and VARINT_PAYLOAD_MASK).toLong() shl shift)
            if (b and VARINT_CONTINUE == 0) return result
            shift += VARINT_PAYLOAD_BITS
        }
        throw IllegalArgumentException("varint too long")
    }
}
