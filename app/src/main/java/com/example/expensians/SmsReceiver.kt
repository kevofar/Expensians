package com.example.expensians

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return

        // Only messages whose SENDER is CBE are read (a text that merely mentions "CBE" is ignored).
        val bySender = parts.groupBy { it.originatingAddress ?: "" }
            .filterKeys { it.contains("CBE", ignoreCase = true) }
        if (bySender.isEmpty()) return

        val pending = goAsync()   // keeps the process alive until the database write is done
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).financeDao()
                for ((_, group) in bySender) {
                    // A long SMS arrives in several parts: join them back into one message.
                    val body = group.joinToString("") { it.messageBody ?: "" }
                    SmsIngest.handle(dao, body, group[0].timestampMillis)
                }
                GoalNotifier.check(context.applicationContext, dao)
            } finally {
                pending.finish()
            }
        }
    }
}

// Reads one CBE message, applies the rules and saves the result.
object SmsIngest {
    suspend fun handle(dao: FinanceDao, body: String, time: Long) {
        val p = CbeParser.parse(body) ?: return          // Rule 1: not transactional -> ignored

        if (!CbeParser.isParsed(p)) {                    // gate passed, but direction/balance unclear
            dao.insertTransaction(
                Transaction(
                    senderName = "Unknown", amount = p.amount, type = p.direction,
                    reason = "Uncategorized", date = time, isParsed = false,
                    yourAccount = p.ownId, otherAccount = p.otherId, balance = p.balance
                )
            )
            return
        }

        val r = CbeParser.resolve(p, dao.getAccountsOnce(), dao.getTopUpAmountsOnce().toSet(), time)
        r.learn?.let { dao.upsertAccount(it) }

        val reason = if (p.direction == "DEBIT")
            dao.getRulesOnce().firstOrNull { p.amount.toDouble() in it.minAmount..it.maxAmount }?.reason ?: "Uncategorized"
        else "Uncategorized"

        dao.insertTransaction(
            Transaction(
                senderName = r.name, amount = p.amount, type = p.direction, reason = reason,
                date = time, isParsed = true, yourAccount = p.ownId, otherAccount = r.otherId,
                balance = p.balance, topUpState = r.topUpState, autoLinked = r.autoLinked,
                nameConflict = r.nameConflict, ownTransfer = r.ownTransfer
            )
        )
    }
}

// ===== PURE LOGIC (no Android code below this line) =====

data class ParsedSms(
    val direction: String,   // DEBIT, CREDIT or UNKNOWN
    val amount: Long,
    val balance: Long?,
    val ownId: String,       // last 4 digits of the user's account
    val otherId: String?,    // last 4 digits of the other account
    val name: String?        // name written in the message (if any)
)

data class Resolved(
    val name: String,
    val otherId: String?,
    val learn: KnownAccount?,
    val autoLinked: Boolean,
    val nameConflict: Boolean,
    val ownTransfer: Boolean,
    val topUpState: String
)

object CbeParser {
    private val IC = RegexOption.IGNORE_CASE
    private val SPACES = Regex("\\s+")

    // account representation: 1 + stars + 4 digits (the number of stars does not matter)
    private val ACCT = Regex("""(?<!\d)1\*+(\d{4})(?!\d)""")
    private val ETB = Regex("""ETB\s*(-?[\d,]+(?:\.\d+)?)""", IC)
    // balance = first ETB number after the word "current" or "balance"
    private val BAL = Regex("""(?:current|balance)\D{0,15}?ETB\s*(-?[\d,]+(?:\.\d+)?)""", IC)

    private val ACCT_NAME = Regex("""(?<!\d)1\*+(\d{4})\s*\(([^)]+)\)""")
    private val TRANSFER_TO = Regex("""transfer\w*\s+ETB\s*-?[\d,]+(?:\.\d+)?\s+to\s+(.+?)\s+on\s+\d""", IC)
    private val CREDITED_FROM = Regex("""credited\s+with\s+ETB\s*-?[\d,]+(?:\.\d+)?\s+from\s+(.+?),?\s+on\s+\d""", IC)
    private val MERCHANT = Regex("""debited\s+for\s+(.+?)\s+with\s+ETB""", IC)
    private val RECEIVED = Regex("""you have received""", IC)

    private val TITLES = setOf("mr", "mrs", "ms", "ato", "w/ro", "w/rt", "dr")

    // cents are dropped: 5,550.99 -> 5550, -8.39 -> -8
    private fun toLong(s: String): Long? =
        s.replace(",", "").trimEnd('.').toDoubleOrNull()?.toLong()

    /** Returns null when the message is NOT transactional (Rule 1). */
    fun parse(body: String): ParsedSms? {
        val text = body.replace(SPACES, " ").trim()
        val accts = ACCT.findAll(text).map { it.groupValues[1] }.toList()
        val first = ETB.find(text)
        if (accts.isEmpty() || first == null) return null                    // Rule 1
        val amount = toLong(first.groupValues[1]) ?: return null             // Rule 4

        val lower = text.lowercase()
        val debit = lower.contains("debit") || lower.contains("transfer")    // Rule 2
        val credit = lower.contains("credit") || lower.contains("receive")   // Rule 3
        val direction = when {
            debit && !credit -> "DEBIT"
            credit && !debit -> "CREDIT"
            else -> "UNKNOWN"
        }

        val balance = BAL.find(text)?.let { toLong(it.groupValues[1]) }      // Rule 5

        // In "received" messages the sender comes first and the user's account second.
        val received = RECEIVED.containsMatchIn(text)
        val ownId = if (received) accts.last() else accts.first()
        val otherId = if (accts.size >= 2) (if (received) accts.first() else accts[1]) else null

        val rawName = ACCT_NAME.findAll(text).firstOrNull { it.groupValues[1] != ownId }?.groupValues?.get(2)
            ?: TRANSFER_TO.find(text)?.groupValues?.get(1)
            ?: CREDITED_FROM.find(text)?.groupValues?.get(1)
            ?: MERCHANT.find(text)?.groupValues?.get(1)
        val name = rawName?.trim()?.takeIf { it.isNotEmpty() }

        return ParsedSms(direction, amount, balance, ownId, otherId, name)
    }

    /** A message is saved as "parsed" only if direction and balance are known. */
    fun isParsed(p: ParsedSms): Boolean = p.direction != "UNKNOWN" && p.balance != null

    fun firstName(name: String): String? =
        name.lowercase().trim().split(SPACES).firstOrNull { it.isNotEmpty() && it !in TITLES }

    private fun sameName(a: String, b: String): Boolean =
        a.trim().lowercase().replace(SPACES, " ") == b.trim().lowercase().replace(SPACES, " ")

    /** Rules 6-12: names, own accounts, first-name link and top-up flag. */
    fun resolve(p: ParsedSms, known: List<KnownAccount>, topUps: Set<Long>, now: Long): Resolved {
        var name: String? = p.name
        var otherId = p.otherId
        var learn: KnownAccount? = null
        var autoLinked = false
        var conflict = false
        var ownTransfer = false

        if (otherId != null) {
            val row = known.firstOrNull { it.id == otherId }
            when {
                row == null -> {                                                 // Rule 6: learn
                    learn = KnownAccount(otherId, p.name, false, false, now)
                }
                row.isOwn -> {                                                   // Rule 8: own account
                    ownTransfer = true
                    name = "Own account"
                    learn = row.copy(lastSeen = now)
                }
                row.userSaved && row.name != null -> {                           // Rule 7: user's name wins
                    name = row.name
                    learn = row.copy(lastSeen = now)
                }
                row.name != null -> {                                            // Rule 6: keep saved name
                    if (p.name != null && !sameName(row.name, p.name)) conflict = true
                    name = row.name
                    learn = row.copy(lastSeen = now)
                }
                else -> {                                                        // known ID, no name yet
                    learn = row.copy(name = p.name, lastSeen = now)
                }
            }
        } else if (p.name != null) {                                             // Rule 9: first-name link
            val f = firstName(p.name)
            val match = if (f == null) null else known
                .filter { !it.isOwn && it.name != null && firstName(it.name) == f }
                .maxByOrNull { it.lastSeen }
            if (match != null) {
                otherId = match.id
                name = match.name
                autoLinked = true
                learn = match.copy(lastSeen = now)
            }
        }

        // Rules 10-11: top-up flag (judged on what the MESSAGE says, not on later linking)
        val noCounterparty = p.otherId == null && p.name == null
        val topUp = if (p.direction == "DEBIT" && noCounterparty && p.amount in 1L..100L) {
            if (p.amount in topUps) "PROBABLE" else "POSSIBLE"
        } else "NONE"

        return Resolved(name ?: "Unknown", otherId, learn, autoLinked, conflict, ownTransfer, topUp)
    }
}