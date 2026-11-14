package com.wichat.transport

import android.util.Log
import com.wichat.data.prefs.AccountStore
import com.wichat.model.Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Tier 3: reaches anyone, at any distance, through the relay over a TLS WebSocket.
 * If the recipient is offline the relay keeps the envelope and wakes them with a push.
 */
class RelayTransport(
    private val http: OkHttpClient,
    private val accounts: AccountStore,
    private val scope: CoroutineScope,
) : Transport {

    override val name = "relay"

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)
    private var socket: WebSocket? = null
    private var loop: Job? = null

    override suspend fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { connectLoop() }
    }

    override suspend fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "stopped")
        socket = null
        _state.value = ConnectionState.DISCONNECTED
    }

    /** Skip the backoff wait, e.g. when the phone just joined a Wi-Fi network. */
    fun reconnectNow() {
        reconnectSignal.trySend(Unit)
    }

    /** The relay address or token changed: drop the current connection and use the new one. */
    fun accountChanged() {
        socket?.close(1000, "account changed")
        reconnectSignal.trySend(Unit)
    }

    val isConnected: Boolean get() = _state.value == ConnectionState.CONNECTED

    /** The relay can reach any registered user; it queues for offline ones. */
    override fun isPeerReachable(recipientTag: String) = _state.value == ConnectionState.CONNECTED

    override suspend fun send(envelope: Envelope): TransportResult {
        val ws = socket
        if (ws == null || _state.value != ConnectionState.CONNECTED) {
            return TransportResult.Failed(IOException("Relay not connected"))
        }
        val ack = CompletableDeferred<Boolean>()
        pendingAcks[envelope.envelopeId] = ack
        val frame = FrameJson.encodeToString(ClientFrame.serializer(), ClientFrame.Send(envelope))
        if (!ws.send(frame)) {
            pendingAcks.remove(envelope.envelopeId)
            return TransportResult.Failed(IOException("Socket closed"))
        }
        val accepted = withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() }
        pendingAcks.remove(envelope.envelopeId)
        return when (accepted) {
            true -> TransportResult.Accepted
            false -> TransportResult.Failed(IOException("Relay rejected envelope"))
            null -> TransportResult.Failed(IOException("Relay ack timed out"))
        }
    }

    override suspend fun acknowledge(envelopeId: String) {
        socket?.send(FrameJson.encodeToString(ClientFrame.serializer(), ClientFrame.Ack(listOf(envelopeId))))
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (true) {
            val account = accounts.current
            if (account == null || !account.hasRelay) {
                // Offline-only account: wait until one is connected to a relay in Settings.
                withTimeoutOrNull(5_000) { reconnectSignal.receive() }
                continue
            }
            _state.value = ConnectionState.CONNECTING
            val closed = CompletableDeferred<Unit>()
            val request = Request.Builder()
                .url("${account.relayUrl}/v1/ws")
                .header("Authorization", "Bearer ${account.token}")
                .build()
            socket = http.newWebSocket(request, Listener(closed, onOpen = { attempt = 0 }))
            closed.await()
            socket = null
            _state.value = ConnectionState.DISCONNECTED
            pendingAcks.values.forEach { it.complete(false) }

            val backoff = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(attempt++, 8))
            withTimeoutOrNull(backoff) { reconnectSignal.receive() }
        }
    }

    private inner class Listener(
        private val closed: CompletableDeferred<Unit>,
        private val onOpen: () -> Unit,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            onOpen()
            _state.value = ConnectionState.CONNECTED
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = runCatching { FrameJson.decodeFromString(ServerFrame.serializer(), text) }
                .getOrElse { Log.w(TAG, "Bad frame from relay", it); return }
            when (frame) {
                is ServerFrame.Deliver -> _incoming.tryEmit(frame.envelope)
                is ServerFrame.ServerAck -> pendingAcks[frame.envelopeId]?.complete(true)
                is ServerFrame.Error -> {
                    Log.w(TAG, "Relay error: ${frame.error}")
                    frame.envelopeId?.let { pendingAcks[it]?.complete(false) }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.i(TAG, "Relay connection failed: ${t.message}")
            closed.complete(Unit)
        }
    }

    private companion object {
        const val TAG = "RelayTransport"
        const val ACK_TIMEOUT_MS = 10_000L
        const val BASE_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 10 * 60_000L
    }
}

private val FrameJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

@Serializable
private sealed class ClientFrame {
    @Serializable
    @SerialName("send")
    data class Send(val envelope: Envelope) : ClientFrame()

    @Serializable
    @SerialName("ack")
    data class Ack(val envelopeIds: List<String>) : ClientFrame()
}

@Serializable
private sealed class ServerFrame {
    @Serializable
    @SerialName("envelope")
    data class Deliver(val envelope: Envelope) : ServerFrame()

    @Serializable
    @SerialName("server_ack")
    data class ServerAck(val envelopeId: String) : ServerFrame()

    @Serializable
    @SerialName("error")
    data class Error(val error: String, val envelopeId: String? = null) : ServerFrame()
}
