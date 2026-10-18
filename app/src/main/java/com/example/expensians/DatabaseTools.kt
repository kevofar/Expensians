package com.example.expensians

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------
// SMALL HELPERS (private to this file)

private val TxDebit = Color(0xFFC05252)
private val TxCredit = Color(0xFF2F805B)

private const val DATE_INPUT = "yyyy-MM-dd HH:mm"

private fun money(n: Long): String = "ETB " + String.format(Locale.US, "%,d", n)

private fun formatDate(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(ms))

/** No reason (or the old default "Uncategorized") is shown as "Unknown". */
private fun reasonText(r: String): String =
    if (r.isBlank() || r.equals("Uncategorized", ignoreCase = true)) "Unknown" else r

private enum class TxDialog { REASON, EDIT_ID, EDIT, DELETE }

// ---------------------------------------------------------
// MAIN SCREEN (replaces the one in MainActivity.kt)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val dao = remember { AppDatabase.getDatabase(context).financeDao() }
    val scope = rememberCoroutineScope()

    val transactions by dao.getAllTransactions().collectAsState(initial = emptyList())
    val topUps by dao.observeTopUps().collectAsState(initial = emptyList())
    val accounts by dao.observeAccounts()
        .collectAsState<List<KnownAccount>, List<KnownAccount>?>(initial = null)

    var selectedTab by remember { mutableStateOf(0) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var dialog by remember { mutableStateOf<TxDialog?>(null) }

    val tabs = listOf(
        Triple("Home", Icons.Default.Home, 0),
        Triple("Database", Icons.Default.List, 1),
        Triple("Goal", Icons.Default.Star, 2),
        Triple("Settings", Icons.Default.Settings, 3)
    )

    val loadedAccounts = accounts

    // The password is the ID of any of the user's own accounts.
    val ownIds = (loadedAccounts ?: emptyList()).filter { it.isOwn }.map { it.id }

    val inSelection = selectedTab == 1 && selectedIds.isNotEmpty()
    val targets = transactions.filter { it.id in selectedIds }

    BackHandler(enabled = inSelection) { selectedIds = emptySet() }

    if (loadedAccounts != null && loadedAccounts.none { it.isOwn }) {
        FirstRunDialog { id, name ->
            scope.launch {
                val existing = loadedAccounts.firstOrNull { it.id == id }
                dao.upsertAccount(
                    KnownAccount(
                        id = id,
                        name = name.ifBlank { existing?.name },
                        isOwn = true,
                        userSaved = name.isNotBlank() || existing?.userSaved == true,
                        lastSeen = existing?.lastSeen ?: 0L
                    )
                )
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (inSelection) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { dialog = TxDialog.REASON }) {
                            Icon(Icons.Default.Info, contentDescription = "Set reason")
                        }
                        if (selectedIds.size == 1) {
                            IconButton(onClick = {
                                dialog = if (ownIds.isEmpty()) TxDialog.EDIT else TxDialog.EDIT_ID
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit")
                            }
                        }
                        IconButton(onClick = { dialog = TxDialog.DELETE }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                TopAppBar(
                    title = {
                        Text(
                            "Expensians",
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp
            ) {
                tabs.forEach { (title, icon, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = {
                            selectedTab = index
                            selectedIds = emptySet()
                        },
                        label = {
                            Text(
                                title,
                                fontWeight = if (selectedTab == index) FontWeight.SemiBold
                                else FontWeight.Normal
                            )
                        },
                        icon = { Icon(icon, contentDescription = title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when (selectedTab) {
                0 -> HomeScreen(transactions, dao)
                1 -> DatabaseScreen(
                    transactions = transactions,
                    dao = dao,
                    ownIds = ownIds,
                    selectedIds = selectedIds,
                    onSelectedChange = { selectedIds = it }
                )
                2 -> GoalScreen(transactions, dao)
                3 -> SettingsScreen(
                    isDarkTheme = isDarkTheme,
                    onThemeChanged = onThemeChanged,
                    accounts = loadedAccounts ?: emptyList(),
                    topUps = topUps,
                    dao = dao
                )
            }
        }
    }

    // ---- dialogs for the selection actions ----
    when (dialog) {
        TxDialog.REASON -> ReasonDialog(
            count = targets.size,
            initial = if (targets.size == 1) targets.first().reason else "",
            onConfirm = { reason ->
                val list = targets
                scope.launch {
                    list.forEach {
                        dao.updateTransaction(it.copy(reason = reason.trim().ifBlank { "Uncategorized" }))
                    }
                }
                selectedIds = emptySet()
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        TxDialog.EDIT_ID -> ConfirmWithIdDialog(
            title = "Edit transaction",
            message = "Enter your account ID to continue.",
            confirmLabel = "Continue",
            destructive = false,
            ownIds = ownIds,
            onConfirm = { dialog = TxDialog.EDIT },
            onDismiss = { dialog = null }
        )

        TxDialog.EDIT -> {
            val tx = targets.firstOrNull()
            if (tx != null) {
                key(tx.id) {
                    EditTransactionDialog(
                        tx = tx,
                        onSave = { updated ->
                            scope.launch { dao.updateTransaction(updated) }
                            selectedIds = emptySet()
                            dialog = null
                        },
                        onDismiss = { dialog = null }
                    )
                }
            }
        }

        TxDialog.DELETE -> ConfirmWithIdDialog(
            title = if (targets.size == 1) "Delete this transaction?"
            else "Delete ${targets.size} transactions?",
            message = if (ownIds.isEmpty()) "Are you sure? This cannot be undone."
            else "Are you sure? This cannot be undone. Enter your account ID to confirm.",
            confirmLabel = "Delete",
            destructive = true,
            ownIds = ownIds,
            onConfirm = {
                val list = targets
                scope.launch { list.forEach { dao.deleteTransaction(it) } }
                selectedIds = emptySet()
                dialog = null
            },
            onDismiss = { dialog = null }
        )

        null -> {}
    }
}

// ---------------------------------------------------------
// DIALOGS

@Composable
private fun ConfirmWithIdDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean,
    ownIds: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val needId = ownIds.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(message)
                if (needId) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                input = it
                                wrong = false
                            }
                        },
                        label = { Text("Account ID (last 4 digits)") },
                        singleLine = true,
                        isError = wrong,
                        supportingText = { if (wrong) Text("That ID is not correct.") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needId || input.length == 4,
                onClick = { if (!needId || input in ownIds) onConfirm() else wrong = true }
            ) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ReasonDialog(
    count: Int,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf(if (reasonText(initial) == "Unknown") "" else initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (count == 1) "Set reason" else "Set reason for $count transactions",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 60) reason = it },
                    label = { Text("Reason") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Leave it empty to show \"Unknown\".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTransactionDialog(
    tx: Transaction,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit
) {
    val fmt = remember { SimpleDateFormat(DATE_INPUT, Locale.getDefault()).apply { isLenient = false } }

    var name by remember { mutableStateOf(tx.senderName) }
    var amount by remember { mutableStateOf(tx.amount.toString()) }
    var type by remember { mutableStateOf(tx.type) }
    var reason by remember { mutableStateOf(if (reasonText(tx.reason) == "Unknown") "" else tx.reason) }
    var date by remember { mutableStateOf(fmt.format(Date(tx.date))) }
    var yours by remember { mutableStateOf(tx.yourAccount) }
    var other by remember { mutableStateOf(tx.otherAccount ?: "") }
    var balance by remember { mutableStateOf(tx.balance?.toString() ?: "") }
    var own by remember { mutableStateOf(tx.ownTransfer) }

    val amountV = amount.toLongOrNull()
    val dateV = runCatching { fmt.parse(date)?.time }.getOrNull()
    val balanceV = balance.toLongOrNull()
    val canSave = amountV != null && dateV != null &&
            (balance.isBlank() || balanceV != null) &&
            yours.length == 4 && (other.isEmpty() || other.length == 4) &&
            name.isNotBlank()

    fun digits(s: String, max: Int) = s.length <= max && s.all { it.isDigit() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit transaction", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DEBIT" to "Debit", "CREDIT" to "Credit", "UNKNOWN" to "Unknown")
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = type == key,
                                onClick = { type = key },
                                label = { Text(label) }
                            )
                        }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (digits(it, 12)) amount = it },
                    label = { Text("Amount (ETB)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (to / from)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (it.length <= 60) reason = it },
                    label = { Text("Reason (empty = Unknown)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("Date ($DATE_INPUT)") },
                    singleLine = true,
                    isError = dateV == null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = yours,
                    onValueChange = { if (digits(it, 4)) yours = it },
                    label = { Text("Your account (last 4)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = other,
                    onValueChange = { if (digits(it, 4)) other = it },
                    label = { Text("Other account (last 4, optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = balance,
                    onValueChange = { if (it.length <= 13 && it.all { c -> c.isDigit() || c == '-' }) balance = it },
                    label = { Text("Balance (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Own transfer (not counted as spending)",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = own, onCheckedChange = { own = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        tx.copy(
                            senderName = name.trim(),
                            amount = amountV ?: tx.amount,
                            type = type,
                            reason = reason.trim().ifBlank { "Uncategorized" },
                            date = dateV ?: tx.date,
                            yourAccount = yours,
                            otherAccount = other.ifBlank { null },
                            balance = balanceV,
                            ownTransfer = own,
                            // same rule as the SMS parser: direction and balance must be known
                            isParsed = type != "UNKNOWN" && balanceV != null
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportDialog(
    ownIds: List<String>,
    onConfirm: (format: String) -> Unit,
    onDismiss: () -> Unit
) {
    var format by remember { mutableStateOf("JSON") }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val needId = ownIds.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export data", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = format == "JSON",
                        onClick = { format = "JSON" },
                        label = { Text("JSON (everything)") }
                    )
                    FilterChip(
                        selected = format == "CSV",
                        onClick = { format = "CSV" },
                        label = { Text("CSV (transactions)") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (format == "JSON")
                        "Transactions, accounts, top-up amounts, goals and rules in one file."
                    else
                        "Transactions only. Opens in Excel or Google Sheets.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (needId) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                                input = it
                                wrong = false
                            }
                        },
                        label = { Text("Account ID (last 4 digits)") },
                        singleLine = true,
                        isError = wrong,
                        supportingText = { if (wrong) Text("That ID is not correct.") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needId || input.length == 4,
                onClick = { if (!needId || input in ownIds) onConfirm(format) else wrong = true }
            ) { Text("Export") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------
// DATABASE SCREEN (replaces the one in MainActivity.kt)

@Composable
fun DatabaseScreen(
    transactions: List<Transaction>,
    dao: FinanceDao,
    ownIds: List<String>,
    selectedIds: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var onlyReview by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var exportFormat by remember { mutableStateOf("JSON") }
    var showImportId by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val shown = if (onlyReview) transactions.filter { !it.isParsed } else transactions
    val inSelection = selectedIds.isNotEmpty()

    fun toggle(id: Long) {
        onSelectedChange(if (id in selectedIds) selectedIds - id else selectedIds + id)
    }

    fun finishExport(uri: Uri?) {
        if (uri == null) return
        scope.launch {
            val ok = writeExport(context, uri, exportFormat, dao, transactions)
            Toast.makeText(
                context,
                if (ok) "Export saved" else "Export failed",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val saveJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { finishExport(it) }
    val saveCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { finishExport(it) }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                importResult = ImportAll.run(context, uri, dao)
                importing = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(modifier = Modifier.padding(horizontal = 18.dp)) {
            item {
                Spacer(Modifier.height(16.dp))

                Text("Transaction history", style = MaterialTheme.typography.titleLarge)

                Spacer(Modifier.height(4.dp))

                Text(
                    "${shown.size} transactions · press and hold to select",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showExport = true }) { Text("Export") }
                    OutlinedButton(
                        enabled = !importing,
                        onClick = {
                            if (ownIds.isEmpty()) openFile.launch(arrayOf("*/*"))
                            else showImportId = true
                        }
                    ) { Text(if (importing) "Importing…" else "Import") }
                }

                Spacer(Modifier.height(12.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Only messages needing review",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(checked = onlyReview, onCheckedChange = { onlyReview = it })
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (shown.isEmpty()) {
                    Text(
                        "No transactions to show.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(shown, key = { it.id }) { tx ->
                TransactionCard(
                    tx = tx,
                    dao = dao,
                    selected = tx.id in selectedIds,
                    onClick = { if (inSelection) toggle(tx.id) },
                    onLongClick = { toggle(tx.id) }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    if (showExport) {
        ExportDialog(
            ownIds = ownIds,
            onConfirm = { format ->
                exportFormat = format
                showExport = false
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                if (format == "CSV") saveCsv.launch("expensians_export_$stamp.csv")
                else saveJson.launch("expensians_export_$stamp.json")
            },
            onDismiss = { showExport = false }
        )
    }

    if (showImportId) {
        ConfirmWithIdDialog(
            title = "Import data",
            message = "Enter your account ID, then choose a JSON or CSV file made by " +
                    "Expensians. Nothing is deleted: only new data is added.",
            confirmLabel = "Choose file",
            destructive = false,
            ownIds = ownIds,
            onConfirm = {
                showImportId = false
                openFile.launch(arrayOf("*/*"))
            },
            onDismiss = { showImportId = false }
        )
    }

    importResult?.let { text ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text("Import result", fontWeight = FontWeight.Bold) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } }
        )
    }
}

// ---------------------------------------------------------
// TRANSACTION CARD (replaces the one in MainActivity.kt)
// HomeScreen still calls TransactionCard(tx, dao), which keeps working.

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionCard(
    tx: Transaction,
    dao: FinanceDao,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(16.dp)

    val isDebit = tx.type == "DEBIT"
    val color = when (tx.type) {
        "DEBIT" -> TxDebit
        "CREDIT" -> TxCredit
        else -> MaterialTheme.colorScheme.onSurface
    }
    val sign = when (tx.type) {
        "DEBIT" -> "−"
        "CREDIT" -> "+"
        else -> ""
    }

    val clickable = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = { onLongClick?.invoke() }
        )
    } else Modifier

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(shape)
            .then(clickable),
        shape = shape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    sign + money(tx.amount),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    formatDate(tx.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))

            if (tx.isParsed) {
                Text(
                    (if (isDebit) "To: " else "From: ") + tx.senderName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    "Needs review: the direction or the balance is not clear in this message.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Reason: " + reasonText(tx.reason),
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(6.dp))

            val accountLine = buildString {
                append("Your account …")
                append(tx.yourAccount)
                if (tx.otherAccount != null) {
                    append("   ↔   …")
                    append(tx.otherAccount)
                }
            }
            Text(
                accountLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tx.balance != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "Balance: " + money(tx.balance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val tags = mutableListOf<String>()
            if (tx.ownTransfer) tags.add("Own transfer")
            if (tx.autoLinked) tags.add("Linked by first name")
            if (tx.nameConflict) tags.add("Name conflict (saved name kept)")
            if (tx.topUpState == "CONFIRMED") tags.add("Top-up")

            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    tags.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (tx.topUpState == "POSSIBLE" || tx.topUpState == "PROBABLE") {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                Spacer(Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tx.topUpState == "PROBABLE") "Most probably a top-up?"
                        else "Possible top-up?",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                dao.updateTransaction(
                                    tx.copy(topUpState = "CONFIRMED", isTopUp = true, reason = "Top-up")
                                )
                                dao.insertTopUp(TopUpAmount(tx.amount))
                            }
                        }
                    ) { Text("Yes") }
                    TextButton(
                        onClick = {
                            scope.launch { dao.updateTransaction(tx.copy(topUpState = "REJECTED")) }
                        }
                    ) { Text("No") }
                }
            }
        }
    }
}

// ---------------------------------------------------------
// EXPORT

private suspend fun writeExport(
    context: Context,
    uri: Uri,
    format: String,
    dao: FinanceDao,
    transactions: List<Transaction>
): Boolean = withContext(Dispatchers.IO) {
    try {
        val text = if (format == "CSV") {
            DataExport.csv(transactions)
        } else {
            DataExport.json(
                transactions,
                dao.getAccountsOnce(),
                dao.getTopUpAmountsOnce(),
                dao.getGoalsOnce(),
                dao.getRulesOnce()
            )
        }
        val out = context.contentResolver.openOutputStream(uri) ?: return@withContext false
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        true
    } catch (e: Exception) {
        false
    }
}

object DataExport {
    const val FORMAT_VERSION = 1   // raise this if the file layout ever changes

    fun json(
        transactions: List<Transaction>,
        accounts: List<KnownAccount>,
        topUps: List<Long>,
        goals: List<Goal>,
        rules: List<CategoryRule>
    ): String {
        val root = JSONObject()
        root.put("app", "Expensians")
        root.put("formatVersion", FORMAT_VERSION)
        root.put("exportedAt", System.currentTimeMillis())

        root.put("transactions", JSONArray().also { arr ->
            transactions.forEach { t ->
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("senderName", t.senderName)
                    put("amount", t.amount)
                    put("type", t.type)
                    put("reason", t.reason)
                    put("date", t.date)
                    put("isParsed", t.isParsed)
                    put("yourAccount", t.yourAccount)
                    put("otherAccount", t.otherAccount ?: JSONObject.NULL)
                    put("balance", t.balance ?: JSONObject.NULL)
                    put("isTopUp", t.isTopUp)
                    put("topUpState", t.topUpState)
                    put("autoLinked", t.autoLinked)
                    put("nameConflict", t.nameConflict)
                    put("ownTransfer", t.ownTransfer)
                })
            }
        })

        root.put("accounts", JSONArray().also { arr ->
            accounts.forEach { a ->
                arr.put(JSONObject().apply {
                    put("id", a.id)
                    put("name", a.name ?: JSONObject.NULL)
                    put("isOwn", a.isOwn)
                    put("userSaved", a.userSaved)
                    put("lastSeen", a.lastSeen)
                })
            }
        })

        root.put("topUpAmounts", JSONArray().also { arr -> topUps.forEach { arr.put(it) } })

        root.put("goals", JSONArray().also { arr ->
            goals.forEach { g ->
                arr.put(JSONObject().apply {
                    put("id", g.id)
                    put("kind", g.kind)
                    put("amount", g.amount)
                    put("period", g.period)
                    put("length", g.length)
                    put("unit", g.unit)
                    put("startDate", g.startDate)
                })
            }
        })

        root.put("rules", JSONArray().also { arr ->
            rules.forEach { r ->
                arr.put(JSONObject().apply {
                    put("id", r.id)
                    put("minAmount", r.minAmount)
                    put("maxAmount", r.maxAmount)
                    put("reason", r.reason)
                })
            }
        })

        return root.toString(2)
    }

    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
            "\"" + s.replace("\"", "\"\"") + "\""
        else s

    fun csv(transactions: List<Transaction>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder()
        sb.append('\uFEFF')   // lets Excel read the file as UTF-8
        sb.append("date,type,amount,name,reason,your_account,other_account,balance,own_transfer,parsed\n")
        transactions.forEach { t ->
            sb.append(
                listOf(
                    fmt.format(Date(t.date)),
                    t.type,
                    t.amount.toString(),
                    esc(t.senderName),
                    esc(reasonText(t.reason)),
                    t.yourAccount,
                    t.otherAccount ?: "",
                    t.balance?.toString() ?: "",
                    t.ownTransfer.toString(),
                    t.isParsed.toString()
                ).joinToString(",")
            )
            sb.append('\n')
        }
        return sb.toString()
    }
}