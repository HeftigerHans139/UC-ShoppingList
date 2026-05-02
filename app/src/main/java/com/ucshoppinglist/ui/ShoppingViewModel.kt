package com.ucshoppinglist.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ucshoppinglist.data.ListAccess
import com.ucshoppinglist.data.ListApi
import com.ucshoppinglist.data.PendingAction
import com.ucshoppinglist.data.RealtimeClient
import com.ucshoppinglist.data.ServerEvent
import com.ucshoppinglist.data.ShoppingItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class ShoppingUiState(
    val items: List<ShoppingItem> = emptyList(),
    val connectionMessage: String = "Nicht verbunden",
    val currentListId: String = "",
    val inviteCode: String = "",
    val listTitle: String = "Gemeinsame Einkaufsliste",
    val serverHttpBase: String = "http://10.0.2.2:8080",
    val pendingCount: Int = 0,
    val showOnboarding: Boolean = true,
    val errorMessage: String = "",
    val savedLists: List<ListAccess> = emptyList(),
    val pendingPairCode: String = ""
)

class ShoppingViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("shopping_prefs", 0)
    private val realtimeClient = RealtimeClient()
    private val listApi = ListApi()

    private var reconnectJobStarted = false
    private val pendingActions = mutableListOf<PendingAction>()

    private val _uiState = MutableStateFlow(ShoppingUiState())
    val uiState: StateFlow<ShoppingUiState> = _uiState.asStateFlow()

    init {
        loadLocalState()
        val serverBase = normalizeServerBase(
            prefs.getString("serverHttpBase", "http://10.0.2.2:8080").orEmpty()
        )
        listApi.updateBaseUrl(serverBase)
        realtimeClient.updateServerBase(serverBase)

        _uiState.update { it.copy(serverHttpBase = serverBase, savedLists = loadSavedLists()) }

        observeEvents()
        maybeConnectStoredList()
        startReconnectLoopIfNeeded()
    }

    fun updateServerBase(newBase: String) {
        val normalized = normalizeServerBase(newBase)
        prefs.edit().putString("serverHttpBase", normalized).apply()
        listApi.updateBaseUrl(normalized)
        realtimeClient.updateServerBase(normalized)

        _uiState.update {
            it.copy(
                serverHttpBase = normalized,
                connectionMessage = if (it.currentListId.isBlank()) "Server gespeichert" else "Verbinde..."
            )
        }

        val listId = _uiState.value.currentListId
        if (listId.isNotBlank()) {
            realtimeClient.connect(listId)
        }
    }

    fun addItem(name: String, quantity: String) {
        val n = name.trim()
        val q = quantity.trim().ifBlank { "1" }
        val action = PendingAction(type = "add", name = n, quantity = q)
        sendOrQueue(action)
    }

    fun toggleDone(item: ShoppingItem, done: Boolean) {
        val action = PendingAction(type = "toggle", id = item.id, done = done)
        sendOrQueue(action)
    }

    fun removeItem(item: ShoppingItem) {
        val action = PendingAction(type = "remove", id = item.id)
        sendOrQueue(action)
    }

    fun createNewList(title: String = "Gemeinsame Einkaufsliste", shared: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                listApi.createList(title, shared)
            }.onSuccess { access ->
                saveAccess(access)
                addToSavedLists(access)
                connectToList(access)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Erstellen fehlgeschlagen") }
            }
        }
    }

    fun joinListByCode(code: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                listApi.joinByCode(code)
            }.onSuccess { access ->
                saveAccess(access)
                addToSavedLists(access)
                connectToList(access)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Beitreten fehlgeschlagen") }
            }
        }
    }

    fun requestFirstAccess() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                listApi.requestAccess(deviceName = "Android")
            }.onSuccess { request ->
                _uiState.update {
                    it.copy(
                        pendingPairCode = request.pairCode,
                        errorMessage = "Anfrage gesendet. Freigabecode: ${request.pairCode}. Bitte im Webinterface annehmen."
                    )
                }

                repeat(120) {
                    delay(3000)
                    runCatching { listApi.getAccessRequestStatus(request.requestId) }
                        .onSuccess { status ->
                            if (status.status == "approved" && status.accessToken.isNotBlank()) {
                                joinByAccessToken(status.accessToken)
                                return@launch
                            }
                            if (status.status == "rejected") {
                                _uiState.update {
                                    it.copy(errorMessage = "Anfrage wurde abgelehnt.")
                                }
                                return@launch
                            }
                        }
                }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Anfrage fehlgeschlagen") }
            }
        }
    }

    fun joinByAccessToken(accessToken: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                listApi.redeemAccessToken(accessToken)
            }.onSuccess { access ->
                saveAccess(access)
                addToSavedLists(access)
                connectToList(access)
                _uiState.update { it.copy(pendingPairCode = "") }
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.message ?: "Token-Zugang fehlgeschlagen") }
            }
        }
    }

    fun switchToList(access: ListAccess) {
        saveAccess(access)
        connectToList(access)
    }

    fun removeFromSavedLists(listId: String) {
        val updated = _uiState.value.savedLists.filter { it.listId != listId }
        persistSavedLists(updated)
        _uiState.update { it.copy(savedLists = updated) }
        if (_uiState.value.currentListId == listId) {
            realtimeClient.disconnect()
            _uiState.update {
                it.copy(
                    currentListId = "",
                    inviteCode = "",
                    listTitle = "Gemeinsame Einkaufsliste",
                    items = emptyList(),
                    showOnboarding = updated.isEmpty(),
                    connectionMessage = "Nicht verbunden"
                )
            }
            prefs.edit().remove("listId").remove("inviteCode").remove("listTitle").apply()
        }
    }

    fun buildInviteLink(): String {
        val code = _uiState.value.inviteCode
        if (code.isBlank()) return ""
        val encodedServer = URLEncoder.encode(_uiState.value.serverHttpBase, "UTF-8")
        return "ucshoppinglist://join?server=$encodedServer&code=$code"
    }

    fun buildInviteShareText(): String {
        val link = buildInviteLink()
        if (link.isBlank()) return ""
        return "Tritt meiner Einkaufsliste bei. Einfach Link tippen:\n$link"
    }

    fun handleInviteLink(rawLink: String) {
        runCatching {
            val uri = Uri.parse(rawLink)
            val code = uri.getQueryParameter("code").orEmpty().trim().uppercase()
            val accessToken = uri.getQueryParameter("access").orEmpty().trim()
            val server = uri.getQueryParameter("server").orEmpty().trim()

            if (server.isNotBlank()) {
                updateServerBase(server)
            }

            if (accessToken.isNotBlank()) {
                joinByAccessToken(accessToken)
                return
            }

            if (code.isBlank()) {
                _uiState.update { it.copy(errorMessage = "Einladungslink ohne Code") }
                return
            }

            joinListByCode(code)
        }.onFailure {
            _uiState.update { state -> state.copy(errorMessage = "Einladungslink ungueltig") }
        }
    }

    fun setError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = "") }
    }

    private fun observeEvents() {
        viewModelScope.launch {
            realtimeClient.events.collect { event ->
                when (event) {
                    is ServerEvent.Snapshot -> {
                        _uiState.update { it.copy(items = event.items) }
                        flushQueueIfConnected()
                    }
                    is ServerEvent.ItemAdded -> {
                        _uiState.update { it.copy(items = it.items + event.item) }
                    }
                    is ServerEvent.ItemUpdated -> {
                        _uiState.update {
                            it.copy(items = it.items.map { old -> if (old.id == event.item.id) event.item else old })
                        }
                    }
                    is ServerEvent.ItemRemoved -> {
                        _uiState.update { it.copy(items = it.items.filterNot { old -> old.id == event.id }) }
                    }
                    is ServerEvent.Connection -> {
                        _uiState.update { it.copy(connectionMessage = event.message) }
                        if (event.connected) {
                            flushQueueIfConnected()
                        }
                    }
                    is ServerEvent.Error -> {
                        _uiState.update { it.copy(errorMessage = event.message) }
                    }
                }
            }
        }
    }

    private fun maybeConnectStoredList() {
        val listId = prefs.getString("listId", "").orEmpty()
        if (listId.isNotBlank()) {
            _uiState.update {
                it.copy(
                    currentListId = listId,
                    inviteCode = prefs.getString("inviteCode", "").orEmpty(),
                    listTitle = prefs.getString("listTitle", "Gemeinsame Einkaufsliste").orEmpty(),
                    showOnboarding = false,
                    connectionMessage = "Verbinde..."
                )
            }
            realtimeClient.connect(listId)
        }
    }

    private fun connectToList(access: ListAccess) {
        _uiState.update {
            it.copy(
                currentListId = access.listId,
                inviteCode = access.inviteCode,
                listTitle = access.title,
                showOnboarding = false,
                connectionMessage = "Verbinde...",
                errorMessage = ""
            )
        }
        realtimeClient.connect(access.listId)
    }

    private fun sendOrQueue(action: PendingAction) {
        if (_uiState.value.currentListId.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Bitte zuerst verbinden und Liste auswaehlen") }
            return
        }

        if (realtimeClient.connected()) {
            dispatch(action)
        } else {
            pendingActions += action
            persistQueue()
            _uiState.update { it.copy(pendingCount = pendingActions.size) }
        }
    }

    private fun dispatch(action: PendingAction) {
        when (action.type) {
            "add" -> realtimeClient.addItem(action.name, action.quantity)
            "toggle" -> realtimeClient.toggleItem(action.id, action.done)
            "remove" -> realtimeClient.removeItem(action.id)
        }
    }

    private fun flushQueueIfConnected() {
        if (!realtimeClient.connected() || pendingActions.isEmpty()) return

        val copy = pendingActions.toList()
        pendingActions.clear()
        for (action in copy) {
            dispatch(action)
        }
        persistQueue()
        _uiState.update { it.copy(pendingCount = 0) }
    }

    private fun loadLocalState() {
        val raw = prefs.getString("pendingActions", "[]").orEmpty()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                pendingActions += PendingAction(
                    type = o.optString("type"),
                    id = o.optString("id"),
                    name = o.optString("name"),
                    quantity = o.optString("quantity"),
                    done = o.optBoolean("done", false)
                )
            }
        } catch (_: Exception) {
            pendingActions.clear()
        }
        _uiState.update { it.copy(pendingCount = pendingActions.size) }
    }

    private fun persistQueue() {
        val arr = JSONArray()
        for (a in pendingActions) {
            arr.put(
                JSONObject()
                    .put("type", a.type)
                    .put("id", a.id)
                    .put("name", a.name)
                    .put("quantity", a.quantity)
                    .put("done", a.done)
            )
        }
        prefs.edit().putString("pendingActions", arr.toString()).apply()
    }

    private fun saveAccess(access: ListAccess) {
        prefs.edit()
            .putString("listId", access.listId)
            .putString("inviteCode", access.inviteCode)
            .putString("listTitle", access.title)
            .apply()
    }

    private fun addToSavedLists(access: ListAccess) {
        val existing = _uiState.value.savedLists.filter { it.listId != access.listId }
        val updated = existing + access
        persistSavedLists(updated)
        _uiState.update { it.copy(savedLists = updated) }
    }

    private fun loadSavedLists(): List<ListAccess> {
        val raw = prefs.getString("savedLists", "[]").orEmpty()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ListAccess(
                    listId = o.optString("listId"),
                    inviteCode = o.optString("inviteCode"),
                    title = o.optString("title", "Einkaufsliste"),
                    shared = o.optBoolean("shared", true)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun persistSavedLists(lists: List<ListAccess>) {
        val arr = JSONArray()
        for (l in lists) {
            arr.put(JSONObject()
                .put("listId", l.listId)
                .put("inviteCode", l.inviteCode)
                .put("title", l.title)
                .put("shared", l.shared))
        }
        prefs.edit().putString("savedLists", arr.toString()).apply()
    }

    private fun normalizeServerBase(input: String): String {
        val trimmed = input.trim().removeSuffix("/")
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return "http://$trimmed"
    }

    private fun startReconnectLoopIfNeeded() {
        if (reconnectJobStarted) return
        reconnectJobStarted = true

        viewModelScope.launch {
            while (true) {
                delay(3000)
                val listId = _uiState.value.currentListId
                if (listId.isNotBlank() && !realtimeClient.connected()) {
                    realtimeClient.connect(listId)
                }
            }
        }
    }

    override fun onCleared() {
        realtimeClient.disconnect()
        super.onCleared()
    }
}
