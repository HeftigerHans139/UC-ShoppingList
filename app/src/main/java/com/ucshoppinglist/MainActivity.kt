package com.ucshoppinglist

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedAssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ucshoppinglist.data.ListAccess
import com.ucshoppinglist.data.ListType
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
    var showEditPlanningDialog by remember { mutableStateOf(false) }
    var showConnectionDialog by remember { mutableStateOf(state.showOnboarding) }
    var showListSwitcher by remember { mutableStateOf(false) }
    var showCreateList by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var newListShared by remember { mutableStateOf(true) }
    var pendingName by remember { mutableStateOf("") }
    var pendingQuantity by remember { mutableStateOf("1") }
    var pendingAssignedTo by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<ShoppingItem?>(null) }
    var editingQuantity by remember { mutableStateOf("") }
    var editingAssignedTo by remember { mutableStateOf("") }

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

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Benachrichtigung best-effort, kein Fehler bei Ablehnung */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Einstellungen")
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
                            listType = state.currentListType,
                            onToggle = { checked -> vm.toggleDone(item, checked) },
                            onSetStatus = { status -> vm.setItemStatus(item, status) },
                            onDelete = { vm.removeItem(item) },
                            onEdit = {
                                editingItem = item
                                editingQuantity = item.quantity
                                editingAssignedTo = item.assignedTo
                                showEditPlanningDialog = true
                            }
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
                pendingAssignedTo = ""
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
                        vm.createNewList(listTitleDraft, ListType.SHOPPING, newListShared)
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
        val isCooking = state.currentListType == ListType.COOKING
        val isPlanning = state.currentListType == ListType.PLANNUNG
        AlertDialog(
            onDismissRequest = { showQuantityDialog = false },
            title = {
                Text(
                    when {
                        isPlanning -> "Details"
                        isCooking -> "Kommentar"
                        else -> "Wie viel?"
                    }
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = pendingQuantity,
                        onValueChange = { pendingQuantity = it },
                        label = { Text(if (isPlanning) "Zusatz" else if (isCooking) "Kommentar" else "Menge") },
                        placeholder = { Text(if (isPlanning) "z. B. bitte bis Dienstag" else if (isCooking) "z. B. vegetarisch" else "z. B. 2 kg") },
                        singleLine = true
                    )
                    if (isPlanning) {
                        TextField(
                            value = pendingAssignedTo,
                            onValueChange = { pendingAssignedTo = it },
                            label = { Text("Zustaendig") },
                            placeholder = { Text("z. B. Alex") },
                            singleLine = true
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.addItem(
                            pendingName,
                            pendingQuantity.ifBlank { if (isCooking || isPlanning) "" else "1" },
                            pendingAssignedTo,
                            allowEmptyQuantity = isCooking || isPlanning
                        )
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
            onCreateNew = {
                showListSwitcher = false
                showCreateList = true
            },
            onDismiss = { showListSwitcher = false }
        )
    }

    if (showCreateList) {
        CreateListDialog(
            onConfirm = { title, type, shared ->
                vm.createNewList(title, type, shared)
                showCreateList = false
            },
            onDismiss = { showCreateList = false }
        )
    }

    if (showSettings) {
        SettingsDialog(
            userName = state.userName,
            savedLists = state.savedLists,
            notificationSettings = state.notificationSettings,
            onSaveUserName = { vm.saveUserName(it) },
            onSetNotification = { listId, enabled -> vm.setNotificationEnabled(listId, enabled) },
            onDismiss = { showSettings = false }
        )
    }

    if (showEditPlanningDialog && editingItem != null) {
        AlertDialog(
            onDismissRequest = { showEditPlanningDialog = false },
            title = { Text("Bearbeiten: ${editingItem!!.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editingAssignedTo,
                        onValueChange = { editingAssignedTo = it },
                        label = { Text("Zustaendig") },
                        placeholder = { Text("z. B. Martin") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editingQuantity,
                        onValueChange = { editingQuantity = it },
                        label = { Text("Zusatz (optional)") },
                        placeholder = { Text("z. B. die guten") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editingItem?.let { item ->
                            val updated = item.copy(
                                assignedTo = editingAssignedTo,
                                quantity = editingQuantity
                            )
                            vm.updateItem(updated)
                            showEditPlanningDialog = false
                            editingItem = null
                        }
                    }
                ) { Text("Speichern") }
            },
            dismissButton = {
                TextButton(onClick = { showEditPlanningDialog = false }) { Text("Abbrechen") }
            }
        )
    }
}

@Composable
private fun ListSwitcherDialog(
    lists: List<ListAccess>,
    currentListId: String,
    onSelect: (ListAccess) -> Unit,
    onRemove: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gespeicherte Listen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onCreateNew,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.PostAdd, contentDescription = null)
                    Spacer(modifier = Modifier.padding(4.dp))
                    Text("Neue Liste erstellen")
                }
                HorizontalDivider()
                if (lists.isEmpty()) {
                    Text("Keine gespeicherten Listen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
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
                                        "${l.listType.displayName()} · Code: ${l.inviteCode}",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateListDialog(
    onConfirm: (title: String, type: ListType, shared: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ListType.SHOPPING) }
    var shared by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neue Liste erstellen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Art der Liste", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ListType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.displayName()) }
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titel") },
                    placeholder = { Text("z. B. Wocheneinkauf") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Geteilt (per Code beitreten)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(checked = shared, onCheckedChange = { shared = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title.trim(), selectedType, shared) },
                enabled = title.isNotBlank()
            ) { Text("Erstellen") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun SettingsDialog(
    userName: String,
    savedLists: List<ListAccess>,
    notificationSettings: Map<String, Boolean>,
    onSaveUserName: (String) -> Unit,
    onSetNotification: (String, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var userNameDraft by remember { mutableStateOf(userName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Einstellungen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mein Name", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = userNameDraft,
                    onValueChange = { userNameDraft = it },
                    label = { Text("Dein Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { onSaveUserName(userNameDraft) }) {
                    Text("Name speichern")
                }

                HorizontalDivider()

                Text("Benachrichtigungen", fontWeight = FontWeight.SemiBold)
                if (savedLists.isEmpty()) {
                    Text(
                        "Noch keine Listen vorhanden.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Benachrichtigung wenn ein Artikel hinzugefuegt wird:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    savedLists.forEach { list ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(list.title, fontWeight = FontWeight.Medium)
                                Text(
                                    list.listType.displayName(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = notificationSettings[list.listId] == true,
                                onCheckedChange = { enabled -> onSetNotification(list.listId, enabled) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Fertig") }
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
    listType: ListType,
    onToggle: (Boolean) -> Unit,
    onSetStatus: (String) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit = {}
) {
    val checkedByColors = listOf(
        Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF6A1B9A),
        Color(0xFFE65100), Color(0xFF00695C), Color(0xFFC62828)
    )
    val checkedByColor = if (item.checkedBy.isNotBlank()) {
        checkedByColors[item.checkedBy.hashCode().and(0x7FFFFFFF) % checkedByColors.size]
    } else Color.Transparent
    val status = item.status.ifBlank {
        if (item.done) "done" else "open"
    }
    val isDone = status == "done"
    val isPrepared = status == "prepared"
    val isPlanning = listType == ListType.PLANNUNG

    val cardColor = when {
        isPlanning && isPrepared -> Color(0xFFFFFDE7)  // Hellgelb für vorbereitet
        isDone && isPlanning -> Color(0xFFE8F5E9)      // Hellgrün für erledigt
        else -> Color(0xFFFFFFFF)                        // Weiß
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPlanning) {
                // Für Plannung: Symbol-Button mit zuverlässiger Klick-Erkennung
                TextButton(
                    onClick = { onSetStatus(nextPlanningStatus(status)) },
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = when (status) {
                            "prepared" -> Color(0x33FFF59D)  // Transparentes Gelb
                            "done" -> Color(0x33C8E6C9)      // Transparentes Grün
                            else -> Color(0x330B7285)         // Transparentes Blau
                        }
                    ),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        text = when (status) {
                            "prepared" -> "~"
                            "done" -> "✓"
                            else -> "○"
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (status) {
                            "prepared" -> Color(0xFFF57F17)  // Orange für vorbereitet
                            "done" -> Color(0xFF2E7D32)       // Grün für erledigt
                            else -> Color(0xFF1F7A8C)         // Blau für offen
                        }
                    )
                }
            } else {
                Checkbox(
                    checked = isDone,
                    onCheckedChange = onToggle
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = if (isDone) TextDecoration.LineThrough else null
                    )
                    // Zeige checkedBy-Name für done-Status
                    if (isDone && item.checkedBy.isNotBlank()) {
                        Spacer(modifier = Modifier.padding(3.dp))
                        Text(
                            text = item.checkedBy,
                            style = MaterialTheme.typography.labelSmall,
                            color = checkedByColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Zuständigen zeigen für Plannung in allen States
                if (isPlanning) {
                    if (item.assignedTo.isNotBlank()) {
                        Text(
                            text = "👤 ${item.assignedTo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF0277BD)
                        )
                    }
                    // Zusatz auch anzeigen
                    if (item.quantity.isNotBlank()) {
                        Text(
                            text = "📝 ${item.quantity}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (listType == ListType.COOKING) {
                    // Cooking: Rezept anzeigen
                    if (item.quantity.isNotBlank()) {
                        Text(
                            text = item.quantity,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Shopping: Menge anzeigen
                    if (item.quantity.isNotBlank() && item.quantity != "1") {
                        Text(
                            text = "Menge: ${item.quantity}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Buttons
            if (isPlanning) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Bearbeiten", modifier = Modifier.size(20.dp))
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Loeschen", modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun planningStatusLabel(status: String): String = when (status) {
    "prepared" -> "Vorbereitet"
    "done" -> "Erledigt"
    else -> "Offen"
}

private fun nextPlanningStatus(status: String): String = when (status) {
    "open" -> "prepared"
    "prepared" -> "done"
    else -> "open"
}
