package com.example.expensians

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GoalGreen = Color(0xFF2F805B)
private val GoalAmber = Color(0xFFC98A1B)
private val GoalRed = Color(0xFFC05252)

private fun gMoney(n: Long): String = "ETB " + String.format(Locale.US, "%,d", n)

private fun fmtDate(ms: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(ms))

// ---------------------------------------------------------
// GOAL TAB

@Composable
fun GoalScreen(transactions: List<Transaction>, dao: FinanceDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val goals by dao.observeGoals().collectAsState(initial = emptyList())

    // refresh once a minute so "days left" and new days stay correct while the app is open
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            now = System.currentTimeMillis()
        }
    }

    val prefs = remember { context.getSharedPreferences(GoalNotifier.PREFS, Context.MODE_PRIVATE) }
    var volumeOn by remember { mutableStateOf(prefs.getBoolean(GoalNotifier.PREF_VOLUME, true)) }

    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Goal?>(null) }

    val progress = goals.map { GoalEngine.progress(it, transactions, now) }
    val active = progress.filter { !it.finished }
    val finished = progress.filter { it.finished }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.padding(horizontal = 18.dp)) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("Goals", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Spending limits and earning targets. Tap a goal to edit it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    if (active.isEmpty()) {
                        GoalInfoCard {
                            Text(
                                "No goals yet. Tap New goal to set a spending limit " +
                                        "or an earning target.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                items(active, key = { it.goal.id }) { p ->
                    GoalCard(p) { editing = p.goal }
                }

                if (finished.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(20.dp))
                        Text("Finished", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                    }
                    items(finished, key = { "done-" + it.goal.id }) { p ->
                        GoalCard(p) { editing = p.goal }
                    }
                }

                item {
                    Spacer(Modifier.height(20.dp))
                    GoalInfoCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Volume boost for goal sounds",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "Sets the notification volume to 50% right before a limit " +
                                            "or goal sound plays. Skipped when the phone is on " +
                                            "silent or vibrate.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = volumeOn,
                                onCheckedChange = {
                                    volumeOn = it
                                    prefs.edit().putBoolean(GoalNotifier.PREF_VOLUME, it).apply()
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(96.dp))
                }
            }

            ExtendedFloatingActionButton(
                onClick = { adding = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New goal") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(18.dp)
            )
        }
    }

    if (adding || editing != null) {
        GoalEditSheet(
            existing = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { goal ->
                scope.launch { dao.upsertGoal(goal) }
                adding = false
                editing = null
            },
            onDelete = { goal ->
                scope.launch { dao.deleteGoal(goal) }
                adding = false
                editing = null
            }
        )
    }
}

@Composable
private fun GoalInfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

// ---------------------------------------------------------
// ONE GOAL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalCard(p: GoalProgress, onClick: () -> Unit) {
    val g = p.goal
    val spend = g.kind == GoalEngine.SPEND
    val remaining = g.amount - p.current

    val color = when {
        p.finished -> if (p.achieved) GoalGreen else GoalRed
        spend && p.level == 2 -> GoalRed
        spend && p.level == 1 -> GoalAmber
        spend -> GoalGreen
        p.level == 2 -> GoalGreen
        else -> MaterialTheme.colorScheme.primary
    }
    val status = when {
        p.finished -> if (p.achieved) "Achieved" else "Missed"
        spend && remaining < 0 -> "Over limit"
        spend && p.level == 2 -> "Limit reached"
        spend && p.level == 1 -> "Almost at limit"
        spend -> "On track"
        p.level == 2 -> "Reached 🎉"
        else -> "In progress"
    }
    val timeLeft = if (p.daysLeft <= 1) "ends today" else "${p.daysLeft} days left"
    val detail = when {
        p.finished -> "Ran ${fmtDate(p.window.start)} – ${fmtDate(p.window.end - 1)}"
        spend && remaining > 0 -> {
            val perDay = if (p.daysLeft > 1) " · about ${gMoney(remaining / p.daysLeft)} a day" else ""
            "${gMoney(remaining)} left$perDay · $timeLeft"
        }
        spend && remaining == 0L -> "Exactly at the limit · $timeLeft"
        spend -> "Over by ${gMoney(-remaining)} · $timeLeft"
        remaining > 0 -> "${gMoney(remaining)} to go · $timeLeft"
        else -> "Target reached · $timeLeft"
    }
    val percent = if (g.amount > 0) p.current * 100 / g.amount else 0L

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    GoalEngine.title(g),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = color
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "${gMoney(p.current)} of ${gMoney(g.amount)}  ($percent%)",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { p.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(Modifier.height(8.dp))

            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------
// ADD / EDIT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalEditSheet(
    existing: Goal?,
    onDismiss: () -> Unit,
    onSave: (Goal) -> Unit,
    onDelete: (Goal) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun presetOf(g: Goal?): String = when {
        g == null -> "DAILY"
        g.period != "FIXED" -> g.period
        g.length == 6 && g.unit == "MONTHS" -> "SIX_MONTHS"
        g.length == 1 && g.unit == "YEARS" -> "YEAR"
        else -> "CUSTOM"
    }

    val fixedOld = existing?.takeIf { it.period == "FIXED" }

    var kind by remember { mutableStateOf(existing?.kind ?: GoalEngine.SPEND) }
    var amount by remember { mutableStateOf(existing?.amount?.toString() ?: "") }
    var preset by remember { mutableStateOf(presetOf(existing)) }
    var customN by remember { mutableStateOf(fixedOld?.length?.toString() ?: "30") }
    var customUnit by remember { mutableStateOf(fixedOld?.unit ?: "DAYS") }
    var confirmDelete by remember { mutableStateOf(false) }

    val amountValue = amount.toLongOrNull()
    val customLen = customN.toIntOrNull()
    val canSave = amountValue != null && amountValue > 0 &&
            (preset != "CUSTOM" || (customLen != null && customLen in 1..999))

    val hint = when (preset) {
        "DAILY" -> "Starts again every day at midnight."
        "WEEKLY" -> "Starts again every Monday."
        "MONTHLY" -> "Starts again on the 1st of every month."
        else -> "Runs once, starting today. At the end it is marked Achieved or Missed."
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                if (existing == null) "New goal" else "Edit goal",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(14.dp))
            Text("Type", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = kind == GoalEngine.SPEND,
                    onClick = { kind = GoalEngine.SPEND },
                    label = { Text("Spending limit") }
                )
                FilterChip(
                    selected = kind == GoalEngine.EARN,
                    onClick = { kind = GoalEngine.EARN },
                    label = { Text("Earning goal") }
                )
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { if (it.length <= 12 && it.all { c -> c.isDigit() }) amount = it },
                label = { Text(if (kind == GoalEngine.SPEND) "Limit (ETB)" else "Target (ETB)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            Text("Period", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "DAILY" to "Daily",
                    "WEEKLY" to "Weekly",
                    "MONTHLY" to "Monthly",
                    "SIX_MONTHS" to "6 months",
                    "YEAR" to "1 year",
                    "CUSTOM" to "Custom"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = preset == key,
                        onClick = { preset = key },
                        label = { Text(label) }
                    )
                }
            }

            if (preset == "CUSTOM") {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "DAYS" to "Days",
                        "WEEKS" to "Weeks",
                        "MONTHS" to "Months",
                        "YEARS" to "Years"
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = customUnit == key,
                            onClick = { customUnit = key },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customN,
                    onValueChange = { if (it.length <= 3 && it.all { c -> c.isDigit() }) customN = it },
                    label = { Text("How many") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = canSave,
                        onClick = {
                            val amt = amountValue ?: return@Button
                            val (period, length, unit) = when (preset) {
                                "DAILY", "WEEKLY", "MONTHLY" -> Triple(preset, 1, "DAYS")
                                "SIX_MONTHS" -> Triple("FIXED", 6, "MONTHS")
                                "YEAR" -> Triple("FIXED", 1, "YEARS")
                                else -> Triple("FIXED", customLen ?: 1, customUnit)
                            }
                            // changing the goal itself starts its notifications fresh
                            val keep = existing != null &&
                                    existing.kind == kind && existing.amount == amt &&
                                    existing.period == period && existing.length == length &&
                                    existing.unit == unit
                            onSave(
                                Goal(
                                    id = existing?.id ?: 0L,
                                    kind = kind,
                                    amount = amt,
                                    period = period,
                                    length = length,
                                    unit = unit,
                                    startDate = existing?.startDate
                                        ?: GoalEngine.startOfDay(System.currentTimeMillis()),
                                    notifiedWindow = if (keep) (existing?.notifiedWindow ?: 0L) else 0L,
                                    notifiedLevel = if (keep) (existing?.notifiedLevel ?: 0) else 0
                                )
                            )
                        }
                    ) { Text("Save") }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this goal?") },
            text = { Text("Your transactions are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(existing)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}