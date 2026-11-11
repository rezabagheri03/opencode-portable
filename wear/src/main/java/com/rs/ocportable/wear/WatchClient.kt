package com.rs.ocportable.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable

/**
 * Watch-side node discovery and command sending. The phone advertises [WearProtocol.CAPABILITY];
 * the OS bond means there is no pairing code and no IP address.
 */
class WatchClient(
    context: Context,
    private val onConnection: (Boolean) -> Unit,
) {
    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)
    private val capabilities = Wearable.getCapabilityClient(app)
    private val nodes = Wearable.getNodeClient(app)
    private val channels = Wearable.getChannelClient(app)

    @Volatile private var nodeID: String? = null
    @Volatile var connected = false
        private set

    val nodeId: String? get() = nodeID

    fun refresh() {
        capabilities.getCapability(WearProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val node = info.nodes.firstOrNull()
                if (node != null) {
                    setNode(node.id)
                } else {
                    nodes.connectedNodes.addOnSuccessListener { connected ->
                        val fallback = connected.firstOrNull()
                        if (fallback != null) setNode(fallback.id) else setDisconnected()
                    }.addOnFailureListener { setDisconnected() }
                }
            }
            .addOnFailureListener { failure ->
                Log.d(TAG, "capability lookup failed: ${failure.message}")
                setDisconnected()
            }
    }

    fun sendCommand(action: String, status: WearProtocol.Status? = WearState.status, text: String? = null) {
        val pending = status?.pending
        val json = WearProtocol.commandJson(
            action = action,
            sessionID = pending?.sessionID ?: status?.sessionID,
            requestID = pending?.requestID,
            text = text,
        )
        withNode { node ->
            messages.sendMessage(node, WearProtocol.PATH_COMMAND, json.toByteArray(Charsets.UTF_8))
                .addOnFailureListener {
                    setDisconnected()
                }
        }
    }

    fun sendAudio(bytes: ByteArray, onResult: (Boolean) -> Unit) {
        val node = nodeID
        if (node == null) {
            refresh()
            onResult(false)
            return
        }
        channels.openChannel(node, WearProtocol.PATH_AUDIO)
            .addOnSuccessListener { channel ->
                channels.getOutputStream(channel)
                    .addOnSuccessListener { output ->
                        Thread {
                            try {
                                output.use { it.write(bytes) }
                                channels.close(channel)
                                onResult(true)
                            } catch (_: Exception) {
                                onResult(false)
                            }
                        }.start()
                    }
                    .addOnFailureListener { onResult(false) }
            }
            .addOnFailureListener { onResult(false) }
    }

    private fun withNode(action: (String) -> Unit) {
        val known = nodeID
        if (known != null) {
            action(known)
            return
        }
        capabilities.getCapability(WearProtocol.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val node = info.nodes.firstOrNull()
                if (node != null) {
                    setNode(node.id)
                    action(node.id)
                } else {
                    setDisconnected()
                }
            }
            .addOnFailureListener { setDisconnected() }
    }

    private fun setNode(id: String) {
        nodeID = id
        if (!connected) {
            connected = true
            onConnection(true)
        }
    }

    private fun setDisconnected() {
        nodeID = null
        if (connected) {
            connected = false
            onConnection(false)
        }
    }

    private companion object {
        const val TAG = "WatchClient"
    }
}
