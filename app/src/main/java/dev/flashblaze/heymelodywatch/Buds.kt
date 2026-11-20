package dev.flashblaze.heymelodywatch

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.wear.tiles.TileService
import dev.flashblaze.heymelodywatch.protocol.AncLevel
import dev.flashblaze.heymelodywatch.protocol.Battery
import dev.flashblaze.heymelodywatch.protocol.BudPart
import dev.flashblaze.heymelodywatch.protocol.Cmd
import dev.flashblaze.heymelodywatch.protocol.Decode
import dev.flashblaze.heymelodywatch.protocol.Event
import dev.flashblaze.heymelodywatch.protocol.Models
import dev.flashblaze.heymelodywatch.protocol.NoiseState
import dev.flashblaze.heymelodywatch.protocol.Payloads
import dev.flashblaze.heymelodywatch.protocol.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class Link {
    IDLE,
    NO_PERMISSION,
    BLUETOOTH_OFF,
    NOT_PAIRED,
    CONNECTING,
    CONNECTED,
    FAILED,

    /** Answered the handshake but its command bitmap has no noise control. */
    UNSUPPORTED,
    ;

    /** States a connect attempt ends in without a link. */
    val isDeadEnd get() = this in setOf(NO_PERMISSION, BLUETOOTH_OFF, NOT_PAIRED, FAILED, UNSUPPORTED)
}

/** Why the last mode change did not go through. */
enum class Notice { NOT_WORN, REFUSED, NO_REPLY }

data class BudsState(
    val link: Link = Link.IDLE,
    val deviceName: String? = null,
    /** Last mode the buds reported; survives a disconnect. */
    val noise: NoiseState? = null,
    /** The level Smart picked, while the mode is Smart. */
    val smartPick: AncLevel? = null,
    /** ANC levels this model has, from its product id; empty until known or for other models. */
    val levels: List<AncLevel> = emptyList(),
    /** A mode change in flight. */
    val pending: NoiseState? = null,
    val notice: Notice? = null,
    /** Last level per part. Not [BatteryReading.live] when it comes from an earlier report. */
    val battery: Map<BudPart, BatteryReading> = emptyMap(),
)

/** [live]: reported on the current link; false for a part the buds stopped reporting (case lid shut) or after a disconnect. */
data class BatteryReading(val battery: Battery, val live: Boolean)

data class PairedBuds(val address: String, val name: String)

/**
 * The one connection to the buds, shared by the app screen and the tile. Each user [acquire]s it
 * while it needs the link and [release]s it after; the socket closes [IDLE_CLOSE_MS] after the last
 * release, so the watch does not hold an RFCOMM link all day.
 */
class Buds private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("buds", Context.MODE_PRIVATE)
    private val setLock = Mutex()

    private val _state = MutableStateFlow(
        BudsState(noise = lastKnown(), levels = Models.levels(productId()), battery = lastBattery()),
    )
    val state: StateFlow<BudsState> = _state

    private var users = 0
    private var connection: Job? = null
    private var idleClose: Job? = null

    @Volatile private var link: BudsLink? = null

    @Synchronized
    fun acquire() {
        users++
        idleClose?.cancel()
        idleClose = null
        if (connection?.isActive != true) startConnection()
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0) return
        idleClose?.cancel()
        idleClose = scope.launch {
            delay(IDLE_CLOSE_MS)
            synchronized(this@Buds) {
                if (users > 0) return@launch
                connection?.cancel()
                connection = null
            }
        }
    }

    /** Drops the link and connects again: retry, after a permission grant, after picking other buds. */
    @Synchronized
    fun reconnect() {
        if (users > 0) startConnection()
    }

    suspend fun <T> session(block: suspend Buds.() -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    @Synchronized
    private fun startConnection() {
        val previous = connection
        // Set before launching, so a caller waiting on the state never sees the last attempt's dead end.
        _state.update { it.copy(link = Link.CONNECTING, notice = null) }
        connection = scope.launch {
            previous?.cancelAndJoin()
            try {
                connectLoop()
            } finally {
                _state.update {
                    val s = it.copy(pending = null, battery = it.battery.mapValues { (_, r) -> r.copy(live = false) })
                    if (s.link.isDeadEnd) s else s.copy(link = Link.IDLE)
                }
            }
        }
    }

    private suspend fun connectLoop() {
        var drops = 0
        while (true) {
            val device = findDevice() ?: return
            @SuppressLint("MissingPermission")
            val name = device.name ?: device.address
            _state.update { it.copy(link = Link.CONNECTING, deviceName = name, notice = null) }
            val l = BudsLink.open(device, scope, ::onEvent)
            if (l == null) {
                _state.update { it.copy(link = Link.FAILED) }
                return
            }
            link = l
            try {
                val ready = setUp(l)
                if (ready != Link.CONNECTED) {
                    _state.update { it.copy(link = ready) }
                    return
                }
                _state.update { it.copy(link = Link.CONNECTED) }
                drops = 0
                l.closed.await()
            } finally {
                l.close()
                link = null
                _state.update { s -> s.copy(battery = s.battery.mapValues { (_, r) -> r.copy(live = false) }) }
            }
            // The buds dropped us: case closed, out of range, or the phone took the channel.
            Log.d(BudsLink.TAG, "link dropped")
            if (++drops > MAX_RECONNECTS) {
                _state.update { it.copy(link = Link.FAILED) }
                return
            }
            _state.update { it.copy(link = Link.CONNECTING) }
            delay(RECONNECT_DELAY_MS)
        }
    }

    /** Handshake, event subscription, first mode read; paced like HeyMelody (300 ms, then 200 ms). */
    private suspend fun setUp(l: BudsLink): Link {
        delay(300)
        val handshake = l.request(Cmd.HANDSHAKE)?.payload ?: return Link.FAILED
        // A bitmap long enough to hold the ANC bit, without it: no noise control on these buds.
        if (handshake.size > 1 + Cmd.BIT_ANC / 8 && !Decode.hasCommandBit(handshake, Cmd.BIT_ANC)) {
            return Link.UNSUPPORTED
        }
        delay(200)
        val product = l.request(Cmd.PRODUCT_ID)?.let { Decode.productId(it.payload) }
        Log.d(BudsLink.TAG, "product id $product")
        if (product != null) {
            prefs.edit().putString(KEY_PRODUCT, product).apply()
            _state.update { it.copy(levels = Models.levels(product)) }
        }
        delay(200)
        val offered = l.request(Cmd.OFFERED_EVENTS)?.let { Decode.offeredEvents(it.payload) }
            .orEmpty()
            .ifEmpty { listOf(0x01, 0x02, Cmd.EVENT_ANC) }
        delay(200)
        // Without the subscription the buds stay silent about changes made on the buds themselves.
        if (Decode.hasCommandBit(handshake, Cmd.BIT_SUBSCRIBE)) {
            l.request(Cmd.SUBSCRIBE, Payloads.subscribe(offered))
        } else {
            for (code in (offered + Cmd.EVENT_ANC).distinct()) {
                l.request(Cmd.SUBSCRIBE_ONE, byteArrayOf(code.toByte()))
                delay(200)
            }
        }
        delay(200)
        readMode(l)
        delay(200)
        l.request(Cmd.BATTERY)
        return if (l.closed.isCompleted) Link.FAILED else Link.CONNECTED
    }

    private suspend fun readMode(l: BudsLink) {
        l.request(Cmd.ANC_READ, Payloads.READ_MODE)
        if (_state.value.noise?.level == AncLevel.SMART) {
            delay(200)
            l.request(Cmd.ANC_READ, Payloads.READ_SMART_PICK)
        }
    }

    private fun onEvent(event: Event) {
        when (event) {
            is Event.Mode -> {
                _state.update {
                    it.copy(noise = event.state, smartPick = it.smartPick.takeIf { event.state.level == AncLevel.SMART })
                }
                remember(event.state)
            }
            is Event.SmartPick -> _state.update { it.copy(smartPick = event.level) }
            is Event.Batteries -> {
                val next = _state.updateAndGet { s ->
                    val stale = s.battery.mapValues { (_, r) -> r.copy(live = false) }
                    s.copy(battery = stale + event.parts.mapValues { (_, b) -> BatteryReading(b, live = true) })
                }
                rememberBattery(next.battery)
            }
        }
    }

    /**
     * Asks the buds for [target], then reads the mode back: the screen shows only what the buds
     * report. Waits for a link if one is still connecting. False when no link came up.
     */
    suspend fun setMode(target: NoiseState): Boolean = setLock.withLock {
        val l = awaitLink() ?: return false
        _state.update { it.copy(pending = target, notice = null) }
        try {
            val reply = l.request(Cmd.ANC_SET, Payloads.setMode(target))
            val status = reply?.payload?.firstOrNull()?.toInt()?.and(0xFF)
            val notice = when (status) {
                null -> Notice.NO_REPLY
                Status.OK -> null
                Status.NOT_WORN -> Notice.NOT_WORN
                else -> Notice.REFUSED
            }
            if (notice != null) Log.d(BudsLink.TAG, "set ${target.setBit}: status $status")
            _state.update { it.copy(notice = notice) }
            delay(300)
            l.request(Cmd.ANC_READ, Payloads.READ_MODE)
            if (target.level == AncLevel.SMART) {
                // Smart reports its pick a moment after the switch.
                delay(800)
                l.request(Cmd.ANC_READ, Payloads.READ_SMART_PICK)
            }
            true
        } finally {
            _state.update { it.copy(pending = null) }
        }
    }

    private suspend fun awaitLink(): BudsLink? {
        val settled = withTimeoutOrNull(AWAIT_LINK_MS) {
            state.first { it.link == Link.CONNECTED || it.link.isDeadEnd }
        }
        return if (settled?.link == Link.CONNECTED) link else null
    }

    // ---- Device choice ----

    fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** The saved buds if still paired, else the first paired "OnePlus Buds". Sets the dead-end state when none. */
    @SuppressLint("MissingPermission")
    private fun findDevice(): BluetoothDevice? {
        if (!hasPermission()) return null.also { _state.update { it.copy(link = Link.NO_PERMISSION) } }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return null.also { _state.update { it.copy(link = Link.BLUETOOTH_OFF) } }
        val bonded = adapter.bondedDevices.orEmpty()
        val saved = prefs.getString(KEY_ADDRESS, null)
        return bonded.firstOrNull { it.address == saved }
            ?: bonded.firstOrNull { it.name?.contains("OnePlus Buds", ignoreCase = true) == true }
            ?: null.also { _state.update { it.copy(link = Link.NOT_PAIRED) } }
    }

    /** Paired headphones, for the picker. Empty without the permission. */
    @SuppressLint("MissingPermission")
    fun pairedHeadphones(): List<PairedBuds> {
        if (!hasPermission()) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO }
            .map { PairedBuds(it.address, it.name ?: it.address) }
    }

    fun choose(address: String) {
        // Other buds: forget the last ones' readings.
        prefs.edit().putString(KEY_ADDRESS, address).remove(KEY_MODE).remove(KEY_BATTERY).apply()
        _state.update { it.copy(noise = null, smartPick = null, battery = emptyMap()) }
        reconnect()
    }

    // ---- Last known readings, for the tile and the next launch ----

    private fun remember(noise: NoiseState) {
        if (prefs.getString(KEY_MODE, null) == noise.key) return
        prefs.edit().putString(KEY_MODE, noise.key).apply()
        TileService.getUpdater(context).requestUpdate(NoiseTileService::class.java)
    }

    private fun lastKnown(): NoiseState? = NoiseState.fromKey(prefs.getString(KEY_MODE, null))

    /** `part:level:charging` pairs, e.g. `1:100:1,2:98:0,3:40:0`. */
    private fun rememberBattery(battery: Map<BudPart, BatteryReading>) {
        val value = battery.entries.joinToString(",") { (part, r) ->
            "${part.id}:${r.battery.level}:${if (r.battery.charging) 1 else 0}"
        }
        prefs.edit().putString(KEY_BATTERY, value).apply()
    }

    private fun lastBattery(): Map<BudPart, BatteryReading> =
        prefs.getString(KEY_BATTERY, null).orEmpty().split(",").mapNotNull { entry ->
            val f = entry.split(":")
            val part = BudPart.entries.firstOrNull { it.id == f.getOrNull(0)?.toIntOrNull() } ?: return@mapNotNull null
            val level = f.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            part to BatteryReading(Battery(level, f.getOrNull(2) == "1"), live = false)
        }.toMap()

    /** Product id of the buds last connected, e.g. `064014`. */
    fun productId(): String? = prefs.getString(KEY_PRODUCT, null)

    companion object {
        private const val KEY_ADDRESS = "address"
        private const val KEY_MODE = "mode"
        private const val KEY_PRODUCT = "product"
        private const val KEY_BATTERY = "battery"
        private const val IDLE_CLOSE_MS = 5_000L
        private const val AWAIT_LINK_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val MAX_RECONNECTS = 3

        @Volatile private var instance: Buds? = null

        fun get(context: Context): Buds =
            instance ?: synchronized(this) { instance ?: Buds(context.applicationContext).also { instance = it } }
    }
}
