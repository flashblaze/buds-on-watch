package dev.flashblaze.budsonwatch.protocol

/**
 * One whole OPPO/OnePlus "OPOv1" message (the HeyMelody RFCOMM protocol):
 *
 * `AA <TotalLen LEB128> <Slice=00> 00 <Cmd u16 LE> <Seq> <PayLen u16 LE> <Payload>`
 *
 * TotalLen counts every byte after itself, so a whole message has `TotalLen = 7 + PayLen`.
 * There is no checksum. Replies carry `cmd | 0x8000` and echo [seq]; pushes from the buds use seq `FF`.
 */
class Frame(val cmd: Int, val seq: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val total = 7 + payload.size
        val out = ArrayList<Byte>(3 + total)
        out += 0xAA.toByte()
        var len = total
        do {
            var b = len and 0x7F
            len = len ushr 7
            if (len != 0) b = b or 0x80
            out += b.toByte()
        } while (len != 0)
        out += 0x00 // whole message, not a slice
        out += 0x00
        out += (cmd and 0xFF).toByte()
        out += (cmd ushr 8 and 0xFF).toByte()
        out += (seq and 0xFF).toByte()
        out += (payload.size and 0xFF).toByte()
        out += (payload.size ushr 8 and 0xFF).toByte()
        return out.toByteArray() + payload
    }

    override fun toString() = "cmd=%04X seq=%02X %s".format(cmd, seq, payload.toHex())
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

/**
 * Splits the RFCOMM byte stream into [Frame]s. Bytes before `AA` are dropped. Sliced messages
 * (Slice byte != 0) are skipped whole: noise-control traffic never uses them.
 * A header that does not add up (bad length, PayLen mismatch) drops the `AA` and searches on.
 */
class Framer {
    private var buf = ByteArray(0)

    fun feed(bytes: ByteArray, count: Int = bytes.size): List<Frame> {
        buf += bytes.copyOf(count)
        val frames = ArrayList<Frame>()
        while (true) {
            val start = buf.indexOfFirst { it == 0xAA.toByte() }
            if (start < 0) { buf = ByteArray(0); break }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)

            // LEB128 length, at most 3 bytes.
            var len = 0
            var p = 1
            var complete = false
            while (p < buf.size && p <= 3) {
                val b = buf[p++].toInt() and 0xFF
                len = len or ((b and 0x7F) shl (7 * (p - 2)))
                if (b and 0x80 == 0) { complete = true; break }
            }
            if (!complete) {
                if (p > 3) { dropOne(); continue } // 3 continuation bytes: not a header
                break // wait for more bytes
            }
            if (len < 4 || len > MAX_FRAME) { dropOne(); continue }
            // Check the header as soon as its bytes are in, so a stray AA that reads as a long
            // length is dropped instead of stalling the stream until that many bytes arrive.
            if (buf.size < p + 2) break
            val slice = buf[p].toInt() and 0xFF
            if (slice > 3 || buf[p + 1].toInt() != 0) { dropOne(); continue }
            if (slice == 0) {
                if (len < 7) { dropOne(); continue }
                if (buf.size < p + 7) break
                val payLen = (buf[p + 5].toInt() and 0xFF) or ((buf[p + 6].toInt() and 0xFF) shl 8)
                if (payLen != len - 7) { dropOne(); continue }
            }
            val end = p + len
            if (buf.size < end) break
            if (slice != 0) { buf = buf.copyOfRange(end, buf.size); continue }

            val cmd = (buf[p + 2].toInt() and 0xFF) or ((buf[p + 3].toInt() and 0xFF) shl 8)
            val seq = buf[p + 4].toInt() and 0xFF
            frames += Frame(cmd, seq, buf.copyOfRange(p + 7, end))
            buf = buf.copyOfRange(end, buf.size)
        }
        return frames
    }

    private fun dropOne() {
        buf = buf.copyOfRange(1, buf.size)
    }

    private companion object {
        const val MAX_FRAME = 2048
    }
}
