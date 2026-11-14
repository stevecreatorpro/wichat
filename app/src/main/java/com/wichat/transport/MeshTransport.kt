package com.wichat.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.wichat.data.prefs.AccountStore
import com.wichat.mesh.MeshCrypto
import com.wichat.mesh.MeshEngine
import com.wichat.mesh.MeshLinks
import com.wichat.mesh.MeshPermissions
import com.wichat.mesh.MeshStore
import com.wichat.model.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class MeshState(
    val enabled: Boolean = true,
    val running: Boolean = false,
    val needsPermission: Boolean = false,
    val peers: Int = 0,
    val carrying: Int = 0,
    val error: String? = null,
)

/**
 * Offline chat with no internet or router: phones running WiChat find each other with Google
 * Nearby Connections (Bluetooth to discover, Wi-Fi Direct/hotspot/LAN for data), connect
 * automatically, and pass sealed envelopes along hop by hop (see [MeshEngine]).
 */
class MeshTransport(
    private val context: Context,
    private val accounts: AccountStore,
    private val crypto: MeshCrypto,
    private val store: MeshStore,
    private val scope: CoroutineScope,
) : Transport {

    override val name = "mesh"

    private val _incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<Envelope> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(MeshState(enabled = accounts.meshEnabled))
    val state: StateFlow<MeshState> = _state.asStateFlow()

    private val client by lazy { Nearby.getConnectionsClient(context) }
    /** All engine and store work happens here, one thing at a time. */
    private val meshThread = Dispatchers.IO.limitedParallelism(1)
    private val connected = ConcurrentHashMap.newKeySet<String>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    /** Random per run, so the radio name never reveals who you are. */
    private val localName = ByteArray(6).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
    private var housekeeping: Job? = null

    private val links = object : MeshLinks {
        override val peers: Set<String> get() = connected
        override fun send(peer: String, frame: ByteArray) {
            client.sendPayload(peer, Payload.fromBytes(frame))
                .addOnFailureListener { Log.w(TAG, "send to $peer failed: ${it.message}") }
        }
    }

    private val engine = MeshEngine(store, links, crypto, onDeliver = { _incoming.tryEmit(it) })

    override suspend fun start() {
        if (_state.value.running) return
        if (!accounts.meshEnabled || accounts.current == null) {
            _state.update { it.copy(enabled = accounts.meshEnabled, running = false) }
            return
        }
        if (!MeshPermissions.granted(context)) {
            _state.update { it.copy(needsPermission = true, running = false) }
            return
        }
        _state.update { it.copy(enabled = true, needsPermission = false, running = true, error = null) }
        client.startAdvertising(localName, SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { fail("Couldn't start advertising", it) }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { fail("Couldn't start looking for phones", it) }
        housekeeping = scope.launch {
            while (isActive) {
                withContext(meshThread) { store.prune(System.currentTimeMillis()) }
                refreshCounts()
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
    }

    override suspend fun stop() {
        housekeeping?.cancel()
        housekeeping = null
        if (_state.value.running) {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        connected.clear()
        pending.clear()
        _state.update { it.copy(running = false, peers = 0) }
    }

    /** Turn offline mesh on or off (saved). */
    suspend fun setEnabled(enabled: Boolean) {
        accounts.meshEnabled = enabled
        _state.update { it.copy(enabled = enabled) }
        if (enabled) start() else stop()
    }

    /** Call after the user grants the Nearby permissions. */
    suspend fun permissionsChanged() {
        stop()
        start()
    }

    val isEnabled: Boolean get() = _state.value.running

    override fun isPeerReachable(recipientTag: String) = connected.isNotEmpty()

    /**
     * Seals the envelope and starts carrying it. "Accepted" only once a neighbour got it; with
     * nobody in range it is still carried and offered to the next phone that appears, but stays
     * queued so the relay can deliver it too if the internet comes back first.
     */
    override suspend fun send(envelope: Envelope): TransportResult {
        if (!_state.value.running) return TransportResult.Failed(IllegalStateException("Mesh is off"))
        return try {
            withContext(meshThread) { engine.submit(crypto.seal(envelope, System.currentTimeMillis())) }
            refreshCounts()
            if (connected.isNotEmpty()) TransportResult.Accepted else TransportResult.Queued
        } catch (e: Exception) {
            TransportResult.Failed(e)
        }
    }

    override suspend fun acknowledge(envelopeId: String) {
        withContext(meshThread) { engine.markDelivered(envelopeId) }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Only one side asks, so two phones don't both request at once.
            if (info.endpointName <= localName) return
            if (connected.size + pending.size >= MAX_PEERS || !pending.add(endpointId)) return
            client.requestConnection(localName, endpointId, lifecycle)
                .addOnFailureListener {
                    pending.remove(endpointId)
                    Log.i(TAG, "connection request to $endpointId failed: ${it.message}")
                }
        }

        override fun onEndpointLost(endpointId: String) {
            pending.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // No pairing prompt: everything sent over this link is end-to-end encrypted anyway.
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pending.remove(endpointId)
            if (!result.status.isSuccess) return
            connected.add(endpointId)
            _state.update { it.copy(peers = connected.size) }
            scope.launch(meshThread) { engine.onPeerConnected(endpointId) }
        }

        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
            _state.update { it.copy(peers = connected.size) }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch(meshThread) {
                engine.onFrame(endpointId, bytes)
                refreshCounts()
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private suspend fun refreshCounts() {
        val carrying = withContext(meshThread) { store.envelopeIds(System.currentTimeMillis(), 10_000).size }
        _state.update { it.copy(carrying = carrying) }
    }

    private fun fail(what: String, e: Exception) {
        val code = (e as? ApiException)?.statusCode
        Log.w(TAG, "$what (status $code)", e)
        _state.update { it.copy(error = "$what. Turn on Bluetooth and Wi-Fi (and Location on older phones).") }
    }

    private companion object {
        const val TAG = "MeshTransport"
        const val SERVICE_ID = "com.wichat.mesh.v1"
        /** M-to-N: each phone links to several neighbours, so messages can hop across a crowd. */
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        const val MAX_PEERS = 4
    }
}
