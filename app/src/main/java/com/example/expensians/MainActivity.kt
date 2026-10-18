package com.example.expensians

import android.Manifest
import android.os.Build
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// ---------------------------------------------------------
// THEME

private val ExpensiansLightColors = lightColorScheme(
    primary = Color(0xFFAA6B39),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1DFCE),
    onPrimaryContainer = Color(0xFF482A16),
    secondary = Color(0xFF79543A),
    onSecondary = Color.White,
    background = Color(0xFFFAF7F2),
    onBackground = Color(0xFF29231E),
    surface = Color(0xFFFFFDF9),
    onSurface = Color(0xFF29231E),
    surfaceVariant = Color(0xFFF0E8DF),
    onSurfaceVariant = Color(0xFF74685D),
    outline = Color(0xFFDED2C5),
    error = Color(0xFFB83B42),
    errorContainer = Color(0xFFF9DEDE),
    onErrorContainer = Color(0xFF601B20)
)

private val ExpensiansDarkColors = darkColorScheme(
    primary = Color(0xFFD59A6A),
    onPrimary = Color(0xFF321B0B),
    primaryContainer = Color(0xFF704525),
    onPrimaryContainer = Color(0xFFF7E0CC),
    secondary = Color(0xFFD7B79B),
    onSecondary = Color(0xFF392719),
    background = Color(0xFF191613),
    onBackground = Color(0xFFECE4DC),
    surface = Color(0xFF231E19),
    onSurface = Color(0xFFECE4DC),
    surfaceVariant = Color(0xFF342B24),
    onSurfaceVariant = Color(0xFFC5B6A8),
    outline = Color(0xFF514237),
    error = Color(0xFFFF9292),
    errorContainer = Color(0xFF542B2D),
    onErrorContainer = Color(0xFFFFDAD8)
)

private val ExpensiansTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.3).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

// ---------------------------------------------------------
// ACTIVITY

class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ask for everything that is still missing in one go
        val needed = mutableListOf<String>()

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECEIVE_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECEIVE_SMS)
            needed.add(Manifest.permission.READ_SMS)
        }

        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }

        setContent {
            val context = LocalContext.current

            val sharedPrefs = remember {
                context.getSharedPreferences(
                    "expensians_prefs",
                    Context.MODE_PRIVATE
                )
            }

            var isDarkTheme by remember {
                mutableStateOf(
                    sharedPrefs.getBoolean("dark_mode", false)
                )
            }

            // system bars follow the app's own dark mode switch
            SideEffect {
                val barStyle = if (isDarkTheme) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    )
                }

                enableEdgeToEdge(
                    statusBarStyle = barStyle,
                    navigationBarStyle = barStyle
                )

                // removes the grey/white strip that 3-button navigation adds on its own
                if (Build.VERSION.SDK_INT >= 29) {
                    window.isNavigationBarContrastEnforced = false
                }
            }

            MaterialTheme(
                colorScheme = if (isDarkTheme) {
                    ExpensiansDarkColors
                } else {
                    ExpensiansLightColors
                },
                typography = ExpensiansTypography,
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(8.dp),
                    small = RoundedCornerShape(12.dp),
                    medium = RoundedCornerShape(16.dp),
                    large = RoundedCornerShape(22.dp),
                    extraLarge = RoundedCornerShape(28.dp)
                )
            ) {
                // MainScreen now lives in DatabaseTools.kt
                MainScreen(
                    isDarkTheme = isDarkTheme,
                    onThemeChanged = { enabled ->
                        isDarkTheme = enabled
                        sharedPrefs.edit()
                            .putBoolean("dark_mode", enabled)
                            .apply()
                    }
                )
            }
        }
    }
}

// ---------------------------------------------------------
// HELPERS

private fun money(n: Long): String =
    "ETB " + String.format(Locale.US, "%,d", n)

// MainScreen, DatabaseScreen and TransactionCard are in DatabaseTools.kt

// ---------------------------------------------------------
// FIRST-RUN DIALOG

@Composable
fun FirstRunDialog(
    onSave: (id: String, name: String) -> Unit
) {
    var digits by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                "Your bank account",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column {
                Text(
                    "Enter the last 4 digits of your CBE account " +
                            "(the digits you see in the SMS, e.g. 6947). " +
                            "You can add more accounts later in Settings."
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = digits,
                    onValueChange = {
                        if (
                            it.length <= 4 &&
                            it.all { c -> c.isDigit() }
                        ) {
                            digits = it
                        }
                    },
                    label = { Text("Last 4 digits") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Your name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = digits.length == 4,
                onClick = {
                    onSave(digits, name.trim())
                }
            ) {
                Text("Save")
            }
        }
    )
}

// ---------------------------------------------------------
// HOME

@Composable
fun HomeScreen(
    transactions: List<Transaction>,
    dao: FinanceDao
) {
    val startOfDay = remember {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    val todaySpending = transactions
        .filter {
            it.type == "DEBIT" &&
                    it.isParsed &&
                    !it.ownTransfer &&
                    it.date >= startOfDay
        }
        .sumOf { it.amount }

    val needsReview = transactions.count { !it.isParsed }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 18.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))

                Text(
                    "Today's spending",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(6.dp))

                Text(
                    money(todaySpending),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "${transactions.size} messages saved" +
                            if (needsReview > 0) {
                                " · $needsReview need review"
                            } else {
                                ""
                            },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(24.dp))

                Text(
                    "Recent transactions",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(Modifier.height(8.dp))

                if (transactions.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor =
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Text(
                            "Nothing yet. Transactions appear here " +
                                    "automatically when a CBE SMS arrives.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
            }

            items(
                transactions.take(5),
                key = { it.id }
            ) { tx ->
                TransactionCard(tx, dao)
            }

            item {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ---------------------------------------------------------
// SETTINGS

@Composable
fun SettingsScreen(
    isDarkTheme: Boolean,
    onThemeChanged: (Boolean) -> Unit,
    accounts: List<KnownAccount>,
    topUps: List<TopUpAmount>,
    dao: FinanceDao
) {
    val scope = rememberCoroutineScope()

    var newOwn by remember { mutableStateOf("") }
    var savedId by remember { mutableStateOf("") }
    var savedName by remember { mutableStateOf("") }
    var newTopUp by remember { mutableStateOf("") }

    val own = accounts.filter { it.isOwn }
    val named = accounts.filter {
        !it.isOwn && it.name != null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 18.dp)
        ) {
            item {
                Spacer(Modifier.height(16.dp))

                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    "Personalize your experience and manage accounts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))

                SettingsSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "Dark mode",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Spacer(Modifier.height(3.dp))

                            Text(
                                "Use a darker interface at night.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Switch(
                            checked = isDarkTheme,
                            onCheckedChange = onThemeChanged
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                SettingsHeading("My accounts")

                Text(
                    "Your own accounts. Transfers between them " +
                            "are not counted as spending.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(8.dp))
            }

            items(own, key = { "own-" + it.id }) { acc ->
                AccountRow(
                    title = "…${acc.id}" +
                            (acc.name?.let { "  ($it)" } ?: ""),
                    subtitle = null,
                    onDelete = {
                        scope.launch {
                            if (acc.name == null) {
                                dao.deleteAccount(acc)
                            } else {
                                dao.upsertAccount(
                                    acc.copy(isOwn = false)
                                )
                            }
                        }
                    }
                )
            }

            item {
                SettingsSectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newOwn,
                            onValueChange = {
                                if (
                                    it.length <= 4 &&
                                    it.all { c -> c.isDigit() }
                                ) {
                                    newOwn = it
                                }
                            },
                            label = { Text("Last 4 digits") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(8.dp))

                        Button(
                            enabled = newOwn.length == 4,
                            onClick = {
                                val id = newOwn

                                scope.launch {
                                    val existing = accounts.firstOrNull {
                                        it.id == id
                                    }

                                    dao.upsertAccount(
                                        existing?.copy(isOwn = true)
                                            ?: KnownAccount(
                                                id = id,
                                                isOwn = true
                                            )
                                    )
                                }

                                newOwn = ""
                            }
                        ) {
                            Text("Add")
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                SettingsHeading("Top-up amounts")

                Text(
                    "A debit of 100 or less with no name is a " +
                            "possible top-up. Amounts on this list are " +
                            "marked as more likely top-ups. Tap an amount " +
                            "to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))

                if (topUps.isEmpty()) {
                    Text(
                        "No saved top-up amounts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(topUps, key = { it.amount }) { t ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        dao.deleteTopUp(t)
                                    }
                                }
                            ) {
                                Text("${t.amount}  ×")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                SettingsSectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newTopUp,
                            onValueChange = {
                                if (
                                    it.length <= 3 &&
                                    it.all { c -> c.isDigit() }
                                ) {
                                    newTopUp = it
                                }
                            },
                            label = { Text("Amount (1 to 100)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.weight(1f)
                        )

                        Spacer(Modifier.width(8.dp))

                        Button(
                            enabled = (
                                    newTopUp.toLongOrNull() ?: 0L
                                    ) in 1L..100L,
                            onClick = {
                                val amount = newTopUp.toLong()

                                scope.launch {
                                    dao.insertTopUp(
                                        TopUpAmount(amount)
                                    )
                                }

                                newTopUp = ""
                            }
                        ) {
                            Text("Add")
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                SettingsHeading("Account names")

                Text(
                    "Names learned from messages and names you " +
                            "save yourself. A name you save is never " +
                            "replaced by a name from a message.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(10.dp))

                SettingsSectionCard {
                    Column {
                        OutlinedTextField(
                            value = savedId,
                            onValueChange = {
                                if (
                                    it.length <= 4 &&
                                    it.all { c -> c.isDigit() }
                                ) {
                                    savedId = it
                                }
                            },
                            label = { Text("Last 4 digits") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(10.dp))

                        OutlinedTextField(
                            value = savedName,
                            onValueChange = { savedName = it },
                            label = { Text("Full name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))

                        Button(
                            enabled = savedId.length == 4 &&
                                    savedName.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val id = savedId
                                val name = savedName.trim()

                                scope.launch {
                                    val existing = accounts.firstOrNull {
                                        it.id == id
                                    }

                                    dao.upsertAccount(
                                        KnownAccount(
                                            id = id,
                                            name = name,
                                            isOwn = existing?.isOwn ?: false,
                                            userSaved = true,
                                            lastSeen = existing?.lastSeen ?: 0L
                                        )
                                    )
                                }

                                savedId = ""
                                savedName = ""
                            }
                        ) {
                            Text("Save name")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            items(named, key = { "name-" + it.id }) { acc ->
                AccountRow(
                    title = "…${acc.id}   ${acc.name}",
                    subtitle = if (acc.userSaved) {
                        "Saved by you"
                    } else {
                        "Learned from messages"
                    },
                    onDelete = {
                        scope.launch {
                            dao.deleteAccount(acc)
                        }
                    }
                )
            }

            item {
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

// ---------------------------------------------------------
// REUSABLE SETTINGS COMPONENTS

@Composable
private fun SettingsHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium
    )
}

@Composable
private fun SettingsSectionCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content
        )
    }
}

@Composable
private fun AccountRow(
    title: String,
    subtitle: String?,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge
            )

            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}