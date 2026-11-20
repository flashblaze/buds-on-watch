package dev.flashblaze.heymelodywatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import dev.flashblaze.heymelodywatch.protocol.Decode
import dev.flashblaze.heymelodywatch.protocol.Event
import dev.flashblaze.heymelodywatch.protocol.Frame
import dev.flashblaze.heymelodywatch.protocol.Framer
import dev.flashblaze.heymelodywatch.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * One RFCOMM session with the buds. Not reusable: after [closed] completes, open a new one.
 *
 * [onEvent] runs on the reader thread for every mode report, whether it answers our read or is a push.
 */
class BudsLink private constructor(
    private val socket: BluetoothSocket,
    scope: CoroutineScope,
    private val onEvent: (Event) -> Unit,
) {
    private val out = socket.outputStream
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Frame>>()
    private val nextSeq = AtomicInteger(1)

    /** Completes when the socket is gone, by [close] or by the buds. */
    val closed = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) { readLoop() }
    }

    private fun readLoop() {
        val framer = Framer()
        val chunk = ByteArray(512)
        try {
            val input = socket.inputStream
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                for (frame in framer.feed(chunk, n)) {
                    Log.d(TAG, "rx $frame")
                    // State first, so a request() caller that resumes on the reply sees it.
                    Decode.anc(frame)?.let(onEvent)
                    pending.remove(key(frame.cmd, frame.seq))?.complete(frame)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "read ended: ${e.message}")
        } finally {
            close()
        }
    }

    /** Sends [cmd] and waits for its reply (`cmd | 0x8000`, same seq). Null on timeout or a dead link. */
    suspend fun request(cmd: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 1500): Frame? {
        val seq = nextSeq.getAndUpdate { if (it >= 0xBF) 1 else it + 1 }
        val replyKey = key(cmd or 0x8000, seq)
        val reply = CompletableDeferred<Frame>()
        pending[replyKey] = reply
        try {
            if (!send(Frame(cmd, seq, payload))) return null
            return withTimeoutOrNull(timeoutMs) { reply.await() }
        } finally {
            pending.remove(replyKey)
        }
    }

    private suspend fun send(frame: Frame): Boolean = writeLock.withLock {
        if (closed.isCompleted) return false
        val bytes = frame.encode()
        Log.d(TAG, "tx $frame  [${bytes.toHex()}]")
        try {
            withContext(Dispatchers.IO) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: IOException) {
            Log.d(TAG, "write failed: ${e.message}")
            close()
            false
        }
    }

    fun close() {
        if (!closed.complete(Unit)) return
        runCatching { socket.close() }
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    companion object {
        const val TAG = "BudsLink"

        /** HeyMelody's control service. On Buds Pro 3 it is RFCOMM channel 15. */
        private val SERVICE: UUID = UUID.fromString("0000079A-D102-11E1-9B23-00025B00A5A5")
        private const val CHANNEL = 15
        private const val CONNECT_TIMEOUT_MS = 12_000L

        private fun key(cmd: Int, seq: Int) = (cmd shl 8) or seq

        /**
         * Connects: service UUID secure, then insecure, then channel 15 directly. Null when all fail.
         * Caller must hold BLUETOOTH_CONNECT.
         */
        @SuppressLint("MissingPermission")
        suspend fun open(device: BluetoothDevice, scope: CoroutineScope, onEvent: (Event) -> Unit): BudsLink? {
            val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
                "secure 079A" to { device.createRfcommSocketToServiceRecord(SERVICE) },
                "insecure 079A" to { device.createInsecureRfcommSocketToServiceRecord(SERVICE) },
                "channel $CHANNEL" to {
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, CHANNEL) as BluetoothSocket
                },
            )
            for ((label, create) in attempts) {
                val socket = runCatching(create).getOrNull() ?: continue
                if (connect(socket)) {
                    Log.d(TAG, "connected via $label")
                    return BudsLink(socket, scope, onEvent)
                }
                Log.d(TAG, "connect via $label failed")
                delay(300)
            }
            return null
        }

        /**
         * Blocking connect on its own thread. Closing the socket is the only way to abort it, so a
         * timeout or a cancelled caller closes it.
         */
        @SuppressLint("MissingPermission")
        private suspend fun connect(socket: BluetoothSocket): Boolean {
            val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { runCatching { socket.close() } }
                    thread(name = "buds-connect") {
                        val connected = try {
                            socket.connect()
                            true
                        } catch (e: IOException) {
                            Log.d(TAG, "connect: ${e.message}")
                            false
                        }
                        cont.resume(connected) // ignored if already cancelled; the socket is closed then
                    }
                }
            }
            if (ok != true) runCatching { socket.close() }
            return ok == true
        }
    }
}
