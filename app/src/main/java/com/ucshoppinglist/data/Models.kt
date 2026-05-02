package com.ucshoppinglist.data

data class ShoppingItem(
    val id: String,
    val name: String,
    val quantity: String,
    val done: Boolean
)

data class ListAccess(
    val listId: String,
    val inviteCode: String,
    val title: String,
    val shared: Boolean = true
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
    val done: Boolean = false
)

sealed interface ServerEvent {
    data class Snapshot(val items: List<ShoppingItem>) : ServerEvent
    data class ItemAdded(val item: ShoppingItem) : ServerEvent
    data class ItemUpdated(val item: ShoppingItem) : ServerEvent
    data class ItemRemoved(val id: String) : ServerEvent
    data class Connection(val connected: Boolean, val message: String) : ServerEvent
    data class Error(val message: String) : ServerEvent
}
