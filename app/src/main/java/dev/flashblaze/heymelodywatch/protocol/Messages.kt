package dev.flashblaze.heymelodywatch.protocol

/**
 * The noise-control subset of the protocol (QuickBuds docs/PROTOCOL.md §5, §6, §10, §14).
 * A reply's command is the request's with bit 15 set; it echoes the request's seq.
 */
object Cmd {
    const val HANDSHAKE = 0x0100 // -> 00 <command bitmap, LSB first>
    const val OFFERED_EVENTS = 0x0200 // -> 00 <count> <event codes>
    const val SUBSCRIBE_ONE = 0x0201 // <code>, for buds without 0x0205
    const val PUSH = 0x0204 // from the buds, seq FF: <event code> ...
    const val SUBSCRIBE = 0x0205 // <count> <codes>
    const val ANC_READ = 0x010C // 01 01 -> 00 01 01 <mask LE>; 04 01 -> 00 04 01 <mask LE>
    const val ANC_SET = 0x0404 // 01 01 <mask LE> -> <status>

    const val REPLY = 0x8000

    /** Bitmap bits (§5): 8 enables 0x010C/0x0404, 22 enables 0x0205. */
    const val BIT_ANC = 8
    const val BIT_SUBSCRIBE = 22

    const val EVENT_ANC = 0x03
}

object Status {
    const val OK = 0x00
    /** Both buds out of the ear (or in the case): the buds refuse a mode change. */
    const val NOT_WORN = 0x0E
}

object Payloads {
    val READ_MODE = byteArrayOf(0x01, 0x01)
    val READ_SMART_PICK = byteArrayOf(0x04, 0x01)

    /** `01 01 <mask>`: one bit set, `bit / 8 + 1` bytes, low byte first. */
    fun setMode(state: NoiseState): ByteArray {
        val bit = state.setBit
        val mask = ByteArray(bit / 8 + 1)
        mask[bit / 8] = (1 shl (bit % 8)).toByte()
        return byteArrayOf(0x01, 0x01) + mask
    }

    /** Subscribe to every offered code, as HeyMelody does; always include ANC. */
    fun subscribe(offered: List<Int>): ByteArray {
        val codes = (offered + Cmd.EVENT_ANC).distinct()
        return byteArrayOf(codes.size.toByte()) + ByteArray(codes.size) { codes[it].toByte() }
    }
}

/** Something the buds said that the app cares about. */
sealed interface Event {
    data class Mode(val state: NoiseState) : Event
    data class SmartPick(val level: AncLevel) : Event
}

object Decode {
    /** Little-endian int from 1-4 bytes at [from] to the end. */
    fun le(bytes: ByteArray, from: Int): Int {
        var v = 0
        for (i in from until minOf(bytes.size, from + 4)) v = v or ((bytes[i].toInt() and 0xFF) shl (8 * (i - from)))
        return v
    }

    /** `0x8100` payload `00 <bitmap>`: is command bit [bit] set? */
    fun hasCommandBit(handshake: ByteArray, bit: Int): Boolean {
        val i = 1 + bit / 8
        return i < handshake.size && (handshake[i].toInt() shr (bit % 8)) and 1 == 1
    }

    /** `0x8200` payload `00 <count> <codes>`. */
    fun offeredEvents(payload: ByteArray): List<Int> {
        if (payload.size < 2 || payload[0].toInt() != 0) return emptyList()
        val count = payload[1].toInt() and 0xFF
        return (0 until minOf(count, payload.size - 2)).map { payload[2 + it].toInt() and 0xFF }
    }

    /**
     * Noise-control content of a `0x810C` reply (`00 <what> <arg> <mask>`) or a `0x0204` push
     * (`03 <what> <arg> <mask>`). `what` 01 is the mode, 04 the level Smart picked;
     * `02` is the hold-cycle mask and is not a mode.
     */
    fun anc(frame: Frame): Event? {
        val p = frame.payload
        val body = when (frame.cmd) {
            Cmd.ANC_READ or Cmd.REPLY -> if (p.size >= 4 && p[0].toInt() == Status.OK) p else null
            Cmd.PUSH -> if (p.size >= 4 && p[0].toInt() == Cmd.EVENT_ANC) p else null
            else -> null
        } ?: return null
        if (body[2].toInt() != 0x01) return null
        val mask = le(body, 3)
        return when (body[1].toInt()) {
            0x01 -> NoiseState.fromReport(mask)?.let { Event.Mode(it) }
            0x04 -> NoiseState.smartPick(mask)?.let { Event.SmartPick(it) }
            else -> null
        }
    }
}
