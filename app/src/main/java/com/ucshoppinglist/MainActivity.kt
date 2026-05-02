package com.ucshoppinglist

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ucshoppinglist.data.ListAccess
import com.ucshoppinglist.data.ShoppingItem
import com.ucshoppinglist.ui.ShoppingViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ShoppingScreen(initialDeepLink = intent?.dataString.orEmpty())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShoppingScreen(initialDeepLink: String = "", vm: ShoppingViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackHost = remember { SnackbarHostState() }

    var showNameDialog by remember { mutableStateOf(false) }
    var showQuantityDialog by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(state.showOnboarding) }
    var showListSwitcher by remember { mutableStateOf(false) }
    var newListShared by remember { mutableStateOf(true) }
    var pendingName by remember { mutableStateOf("") }
    var pendingQuantity by remember { mutableStateOf("1") }

    var serverDraft by remember { mutableStateOf(state.serverHttpBase) }
    var listTitleDraft by remember { mutableStateOf("Gemeinsame Einkaufsliste") }
    var joinCodeDraft by remember { mutableStateOf("") }

    LaunchedEffect(state.showOnboarding) {
        showConnectionDialog = state.showOnboarding
    }

    LaunchedEffect(state.serverHttpBase) {
        serverDraft = state.serverHttpBase
    }

    LaunchedEffect(initialDeepLink) {
        if (initialDeepLink.isNotBlank()) {
            vm.handleInviteLink(initialDeepLink)
            showConnectionDialog = false
        }
    }

    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage.isNotBlank()) {
            snackHost.showSnackbar(state.errorMessage)
            vm.clearError()
        }
    }

    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFF6F4EE), Color(0xFFEAF2F4), Color(0xFFF8FAFB))
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            CenterAlignedTopAppBar(
                title = {
                    Column {
                        Text(state.listTitle, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)
                        Text(state.connectionMessage, style = MaterialTheme.typography.labelMedium)
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFAF6E9))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Group, contentDescription = null)
                        Spacer(modifier = Modifier.padding(4.dp))
                        Text("Code: ${state.inviteCode.ifBlank { "-" }}", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            serverDraft = state.serverHttpBase
                            showConnectionDialog = true
                        }) {
                            Icon(Icons.Rounded.SettingsEthernet, contentDescription = "Verbinden")
                        }
                        IconButton(onClick = { showListSwitcher = true }) {
                            Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Listen wechseln")
                        }
                        IconButton(onClick = {
                            val shareText = vm.buildInviteShareText()
                            if (shareText.isBlank()) {
                                vm.setError("Noch kein Einladungslink vorhanden")
                            } else {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                }
                                context.startActivity(Intent.createChooser(intent, "Einladung teilen"))
                            }
                        }) {
                            Icon(Icons.Rounded.Link, contentDescription = "Link teilen")
                        }
                    }
                    Text(
                        "Server: ${state.serverHttpBase}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.pendingPairCode.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Freigabecode (wartend): ${state.pendingPairCode}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF1F7A8C),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Tippe auf das Link-Icon, um den Join-Link direkt zu teilen.")
                }
            }

            if (state.pendingCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                ElevatedAssistChip(
                    onClick = {},
                    label = { Text("${state.pendingCount} Aktionen warten auf Sync") },
                    leadingIcon = { Icon(Icons.Rounded.WifiOff, contentDescription = null) },
                    colors = AssistChipDefaults.elevatedAssistChipColors(containerColor = Color(0xFFFFF2CC))
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (state.items.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.items, key = { it.id }) { item ->
                        ItemRow(
                            item = item,
                            onToggle = { checked -> vm.toggleDone(item, checked) },
                            onDelete = { vm.removeItem(item) }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )

        FloatingActionButton(
            onClick = {
                pendingName = ""
                pendingQuantity = "1"
                showNameDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
            containerColor = Color(0xFF1F7A8C),
            contentColor = Color.White
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Hinzufuegen")
        }
    }

    if (showConnectionDialog) {
        AlertDialog(
            onDismissRequest = {
                if (state.currentListId.isNotBlank()) {
                    showConnectionDialog = false
                }
            },
            title = { Text("Verbindungsmenue") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = serverDraft,
                        onValueChange = { serverDraft = it },
                        label = { Text("Server URL (http oder https)") },
                        placeholder = { Text("z. B. http://192.168.178.50") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = listTitleDraft,
                        onValueChange = { listTitleDraft = it },
                        label = { Text("Neue Liste") },
                        placeholder = { Text("Titel") },
                        singleLine = true
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Geteilt (per Code beitreten)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        androidx.compose.material3.Switch(
                            checked = newListShared,
                            onCheckedChange = { newListShared = it }
                        )
                    }
                    OutlinedTextField(
                        value = joinCodeDraft,
                        onValueChange = { joinCodeDraft = it.uppercase() },
                        label = { Text("Code beitreten") },
                        placeholder = { Text("ABC123") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.updateServerBase(serverDraft)
                        showConnectionDialog = false
                    }
                ) { Text("Nur verbinden") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.updateServerBase(serverDraft)
                        vm.createNewList(listTitleDraft, newListShared)
                        showConnectionDialog = false
                    }) { Text("Neue Liste") }
                    TextButton(onClick = {
                        vm.updateServerBase(serverDraft)
                        vm.joinListByCode(joinCodeDraft)
                        showConnectionDialog = false
                    }) { Text("Beitreten") }
                    TextButton(onClick = {
                        vm.updateServerBase(serverDraft)
                        vm.requestFirstAccess()
                        showConnectionDialog = false
                    }) { Text("Anfrage") }
                }
            }
        )
    }

    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = { Text("Was soll auf die Liste?") },
            text = {
                TextField(
                    value = pendingName,
                    onValueChange = { pendingName = it },
                    placeholder = { Text("z. B. Tomaten") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (pendingName.trim().isNotEmpty()) {
                            showNameDialog = false
                            showQuantityDialog = true
                        }
                    }
                ) { Text("Weiter") }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) { Text("Abbrechen") }
            }
        )
    }

    if (showQuantityDialog) {
        AlertDialog(
            onDismissRequest = { showQuantityDialog = false },
            title = { Text("Wie viel?") },
            text = {
                TextField(
                    value = pendingQuantity,
                    onValueChange = { pendingQuantity = it },
                    placeholder = { Text("z. B. 2 kg") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addItem(pendingName, pendingQuantity.ifBlank { "1" })
                        showQuantityDialog = false
                    }
                ) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { showQuantityDialog = false }) { Text("Zurueck") }
            }
        )
    }
    if (showListSwitcher) {
        ListSwitcherDialog(
            lists = state.savedLists,
            currentListId = state.currentListId,
            onSelect = { access ->
                vm.switchToList(access)
                showListSwitcher = false
            },
            onRemove = { vm.removeFromSavedLists(it) },
            onDismiss = { showListSwitcher = false }
        )
    }
}

@Composable
private fun ListSwitcherDialog(
    lists: List<ListAccess>,
    currentListId: String,
    onSelect: (ListAccess) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gespeicherte Listen") },
        text = {
            if (lists.isEmpty()) {
                Text("Keine gespeicherten Listen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (l in lists) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (l.listId == currentListId)
                                    MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(l.title, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Code: ${l.inviteCode}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { onSelect(l) }) { Text("Wechseln") }
                                IconButton(onClick = { onRemove(l.listId) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Entfernen")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Schliessen") }
        }
    )
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Noch nichts in der Liste",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Tippe auf +, um den ersten Artikel hinzuzufuegen.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ItemRow(
    item: ShoppingItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = item.done,
                onCheckedChange = onToggle
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (item.done) TextDecoration.LineThrough else null
                )
                Text(
                    text = "Menge: ${item.quantity}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                )
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Loeschen")
            }
        }
    }
}
