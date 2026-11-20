package dev.flashblaze.heymelodywatch.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Byte strings are captures from QuickBuds docs/PROTOCOL.md and nic0manz/oneplus_buds3_pro_python. */
class ProtocolTest {

    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `set frames match the captured bytes`() {
        assertArrayEquals(
            hex("AA 0A 00 00 04 04 06 03 00 01 01 01"),
            Frame(Cmd.ANC_SET, 0x06, Payloads.setMode(NoiseState(NoiseMode.OFF))).encode(),
        )
        assertArrayEquals(hex("AA 07 00 00 00 01 01 00 00"), Frame(Cmd.HANDSHAKE, 0x01, ByteArray(0)).encode())
    }

    @Test
    fun `set payloads use the SET bit of each mode`() {
        fun set(state: NoiseState) = Payloads.setMode(state).toHex()
        assertEquals("01 01 01", set(NoiseState(NoiseMode.OFF)))
        assertEquals("01 01 02", set(NoiseState(NoiseMode.ANC)))
        assertEquals("01 01 04", set(NoiseState(NoiseMode.TRANSPARENCY)))
        assertEquals("01 01 10", set(NoiseState(NoiseMode.ANC, AncLevel.HIGH)))
        assertEquals("01 01 20", set(NoiseState(NoiseMode.ANC, AncLevel.MEDIUM)))
        assertEquals("01 01 40", set(NoiseState(NoiseMode.ANC, AncLevel.LIGHT)))
        assertEquals("01 01 80", set(NoiseState(NoiseMode.ANC, AncLevel.SMART)))
    }

    @Test
    fun `long frames use a two byte LEB128 length both ways`() {
        val frame = Frame(0x0123, 0x02, ByteArray(124) { it.toByte() })
        val bytes = frame.encode()
        assertEquals("AA 83 01", bytes.copyOf(3).toHex())
        val decoded = Framer().feed(bytes).single()
        assertEquals(0x0123, decoded.cmd)
        assertArrayEquals(frame.payload, decoded.payload)
    }

    @Test
    fun `framer skips noise, joins split reads and splits joined frames`() {
        val push = hex("AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00")
        val reply = hex("AA 0C 00 00 0C 81 07 05 00 00 01 01 10 00")
        // A stray AA right before a frame reads as a 1578-byte header; the PayLen check must reject it.
        val stream = hex("00 13 AA") + push + reply
        val framer = Framer()
        val first = framer.feed(stream.copyOfRange(0, 9))
        val rest = framer.feed(stream.copyOfRange(9, stream.size))
        assertTrue(first.isEmpty())
        assertEquals(listOf(0x0204 to 0xFF, 0x810C to 0x07), rest.map { it.cmd to it.seq })
    }

    @Test
    fun `framer drops sliced messages whole`() {
        val slice = hex("AA 09 01 00 00 04 04 06 03 00 01")
        val push = hex("AA 0C 00 00 04 02 FF 05 00 03 01 01 08 00")
        assertEquals(listOf(0x0204), Framer().feed(slice + push).map { it.cmd })
    }

    @Test
    fun `reports decode by their REPORT bits`() {
        fun push(s: String) = Decode.anc(Frame(Cmd.PUSH, 0xFF, hex(s)))
        assertEquals(Event.Mode(NoiseState(NoiseMode.OFF)), push("03 01 01 08 00"))
        assertEquals(Event.Mode(NoiseState(NoiseMode.TRANSPARENCY)), push("03 01 01 00 01"))
        assertEquals(Event.Mode(NoiseState(NoiseMode.ANC, AncLevel.MEDIUM)), push("03 01 01 20 00"))
        assertEquals(Event.Mode(NoiseState(NoiseMode.ANC, AncLevel.SMART)), push("03 01 01 80 00"))
        assertEquals(Event.SmartPick(AncLevel.LIGHT), push("03 04 01 40 00"))
        // Hold-cycle mask, not a mode.
        assertNull(push("03 02 01 07 00"))
        // Battery push.
        assertNull(push("01 03 01 50 02 50 03 40"))

        val reply = Decode.anc(Frame(Cmd.ANC_READ or Cmd.REPLY, 0x07, hex("00 01 01 10 00")))
        assertEquals(Event.Mode(NoiseState(NoiseMode.ANC, AncLevel.HIGH)), reply)
        assertNull(Decode.anc(Frame(Cmd.ANC_READ or Cmd.REPLY, 0x07, hex("01 01 01 10 00"))))
    }

    @Test
    fun `battery replies and pushes`() {
        // Pushes captured from a Buds Pro 3 on the watch: left charging at 100 (E4), case 40 (28).
        assertEquals(
            mapOf(
                BudPart.LEFT to Battery(100, charging = true),
                BudPart.RIGHT to Battery(100, charging = false),
                BudPart.CASE to Battery(40, charging = false),
            ),
            Decode.event(Frame(Cmd.PUSH, 0x22, hex("01 03 01 E4 02 64 03 28")))?.let { (it as Event.Batteries).parts },
        )
        // Case lid closed: only the buds are reported.
        assertEquals(
            setOf(BudPart.LEFT, BudPart.RIGHT),
            Decode.battery(Frame(Cmd.PUSH, 0x25, hex("01 02 01 64 02 64")))?.parts?.keys,
        )
        val reply = Decode.battery(Frame(Cmd.BATTERY or Cmd.REPLY, 0x09, hex("00 03 01 37 02 FF 03 C5")))
        assertEquals(mapOf(BudPart.LEFT to Battery(55, false), BudPart.CASE to Battery(69, true)), reply?.parts)
        // Wear push (code 02) has the same shape and must not read as battery.
        assertNull(Decode.event(Frame(Cmd.PUSH, 0x21, hex("02 03 01 04 02 05 03 04"))))
    }

    @Test
    fun `handshake bitmap and offered events`() {
        val bitmap = hex("00 FF 77 5A EA 67 0E 20 07") // OnePlus Buds 4
        assertTrue(Decode.hasCommandBit(bitmap, Cmd.BIT_ANC))
        assertTrue(Decode.hasCommandBit(bitmap, Cmd.BIT_SUBSCRIBE))
        assertFalse(Decode.hasCommandBit(bitmap, 11)) // 0x77: bit 3 of the second bitmap byte is clear
        assertFalse(Decode.hasCommandBit(hex("00 FF"), Cmd.BIT_ANC))

        val offered = Decode.offeredEvents(hex("00 09 01 02 03 04 08 0B F1 F2 F3"))
        assertEquals(listOf(0x01, 0x02, 0x03, 0x04, 0x08, 0x0B, 0xF1, 0xF2, 0xF3), offered)
        // Count first, every offered code, ANC added when the buds did not list it.
        assertEquals("03 01 02 03", Payloads.subscribe(listOf(0x01, 0x02, 0x03)).toHex())
        assertEquals("03 02 01 03", Payloads.subscribe(listOf(0x02, 0x01)).toHex())
    }

    @Test
    fun `product id selects the level table`() {
        assertEquals("065414", Decode.productId(hex("00 14 54 06"))) // OnePlus Buds 4 capture
        val pro3 = Decode.productId(hex("00 14 40 06"))
        assertEquals(Models.BUDS_PRO_3, pro3)
        assertEquals(listOf(AncLevel.SMART, AncLevel.HIGH, AncLevel.MEDIUM, AncLevel.LIGHT), Models.levels(pro3))
        // Unknown model: no level bits are offered.
        assertTrue(Models.levels("065414").isEmpty())
        assertNull(Decode.productId(hex("01 14 40 06")))
    }
}
