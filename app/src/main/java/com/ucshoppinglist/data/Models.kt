package com.ucshoppinglist.data

enum class ListType {
    SHOPPING, PLANNUNG, COOKING, NOTE;

    fun displayName(): String = when (this) {
        SHOPPING -> "Einkaufsliste"
        PLANNUNG -> "Plannung"
        COOKING -> "Kochideen"
        NOTE -> "Notiz"
    }
}

data class ShoppingItem(
    val id: String,
    val name: String,
    val quantity: String,
    val status: String = "open",
    val assignedTo: String = "",
    val done: Boolean,
    val checkedBy: String = ""
)

data class ListAccess(
    val listId: String,
    val inviteCode: String,
    val title: String,
    val shared: Boolean = true,
    val listType: ListType = ListType.SHOPPING
)

data class AccessRequestStatus(
    val requestId: String,
    val pairCode: String,
    val status: String,
    val accessToken: String = ""
)

data class PendingAction(
    val type: String,
    val id: String = "",
    val name: String = "",
    val quantity: String = "",
    val status: String = "open",
    val assignedTo: String = "",
    val done: Boolean = false,
    val checkedBy: String = ""
)

sealed interface ServerEvent {
    data class Snapshot(val items: List<ShoppingItem>) : ServerEvent
    data class ItemAdded(val item: ShoppingItem) : ServerEvent
    data class ItemUpdated(val item: ShoppingItem) : ServerEvent
    data class ItemRemoved(val id: String) : ServerEvent
    data class Connection(val connected: Boolean, val message: String) : ServerEvent
    data class Error(val message: String) : ServerEvent
}
