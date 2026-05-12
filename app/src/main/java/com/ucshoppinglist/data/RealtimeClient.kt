package com.ucshoppinglist.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RealtimeClient {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var wsBaseUrl: String = "ws://10.0.2.2:8080/ws"
    private var socket: WebSocket? = null
    private var currentListId: String? = null
    private var isConnected: Boolean = false
    private var connectionId: Int = 0  // wird bei jeder neuen Verbindung erhöht

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<ServerEvent> = _events

    fun connect(listId: String) {
        if (socket != null && currentListId == listId && isConnected) return

        disconnect()
        currentListId = listId
        connectionId++
        val myConnectionId = connectionId

        val request = Request.Builder()
            .url("$wsBaseUrl?listId=$listId")
            .build()

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (connectionId != myConnectionId) return
                isConnected = true
                _events.tryEmit(ServerEvent.Connection(true, "Live verbunden"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (connectionId != myConnectionId) return
                parseAndEmit(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (connectionId != myConnectionId) return
                isConnected = false
                _events.tryEmit(ServerEvent.Connection(false, "Offline - warte auf Verbindung"))
                socket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (connectionId != myConnectionId) return
                isConnected = false
                _events.tryEmit(ServerEvent.Connection(false, "Offline"))
                socket = null
            }
        })
    }

    fun updateServerBase(httpBaseUrl: String) {
        val clean = httpBaseUrl.trim().removeSuffix("/")
        val withoutScheme = clean
            .removePrefix("http://")
            .removePrefix("https://")

        wsBaseUrl = if (clean.startsWith("https://")) {
            "wss://$withoutScheme/ws"
        } else {
            "ws://$withoutScheme/ws"
        }

        val listId = currentListId
        if (listId != null) {
            connect(listId)
        }
    }

    fun disconnect() {
        socket?.close(1000, "bye")
        socket = null
        isConnected = false
    }

    fun connected(): Boolean = isConnected

    fun addItem(name: String, quantity: String, assignedTo: String = "", allowEmptyQuantity: Boolean = false) {
        val msg = JSONObject()
            .put("type", "add_item")
            .put("payload", JSONObject()
                .put("name", name)
                .put("quantity", quantity)
                .put("assignedTo", assignedTo)
                .put("allowEmptyQuantity", allowEmptyQuantity))
        socket?.send(msg.toString())
    }

    fun toggleItem(id: String, done: Boolean, checkedBy: String = "") {
        val msg = JSONObject()
            .put("type", "toggle_item")
            .put("payload", JSONObject().put("id", id).put("done", done).put("checkedBy", checkedBy))
        socket?.send(msg.toString())
    }

    fun setItemStatus(id: String, status: String, checkedBy: String = "") {
        val msg = JSONObject()
            .put("type", "toggle_item")
            .put("payload", JSONObject().put("id", id).put("status", status).put("checkedBy", checkedBy))
        socket?.send(msg.toString())
    }

    fun removeItem(id: String) {
        val msg = JSONObject()
            .put("type", "remove_item")
            .put("payload", JSONObject().put("id", id))
        socket?.send(msg.toString())
    }

    fun updateItem(id: String, name: String, quantity: String, assignedTo: String, status: String) {
        val msg = JSONObject()
            .put("type", "update_item")
            .put("payload", JSONObject()
                .put("id", id)
                .put("name", name)
                .put("quantity", quantity)
                .put("assignedTo", assignedTo)
                .put("status", status))
        socket?.send(msg.toString())
    }

    private fun parseAndEmit(text: String) {
        val root = JSONObject(text)
        when (root.optString("type")) {
            "snapshot" -> {
                val payload = root.getJSONObject("payload")
                val items = payload.optJSONArray("items") ?: JSONArray()
                _events.tryEmit(ServerEvent.Snapshot(items.toShoppingItems()))
            }
            "item_added" -> {
                val item = root.getJSONObject("payload").toShoppingItem()
                _events.tryEmit(ServerEvent.ItemAdded(item))
            }
            "item_updated" -> {
                val item = root.getJSONObject("payload").toShoppingItem()
                _events.tryEmit(ServerEvent.ItemUpdated(item))
            }
            "item_removed" -> {
                val id = root.getJSONObject("payload").optString("id")
                _events.tryEmit(ServerEvent.ItemRemoved(id))
            }
            "error" -> {
                val msg = root.getJSONObject("payload").optString("message", "Serverfehler")
                _events.tryEmit(ServerEvent.Error(msg))
            }
        }
    }

    private fun JSONArray.toShoppingItems(): List<ShoppingItem> {
        val out = mutableListOf<ShoppingItem>()
        for (i in 0 until length()) {
            val obj = getJSONObject(i)
            out += obj.toShoppingItem()
        }
        return out
    }

    private fun JSONObject.toShoppingItem(): ShoppingItem {
        val status = optString("status").ifBlank {
            if (optBoolean("done", false)) "done" else "open"
        }
        return ShoppingItem(
            id = optString("id"),
            name = optString("name"),
            quantity = optString("quantity", "1"),
            status = status,
            assignedTo = optString("assignedTo", ""),
            done = status == "done" || optBoolean("done", false),
            checkedBy = optString("checkedBy", "")
        )
    }
}
