package com.example.expensians

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Restores an Expensians JSON export (made with Export > JSON).
 * It only MERGES: nothing in the app is deleted or overwritten, and anything that is
 * already there is skipped, so the same file can be imported twice without harm.
 */
object DataImport {
    private const val MAX_BYTES = 20 * 1024 * 1024

    /** Returns the text to show the user when the import is finished. */
    suspend fun importFrom(context: Context, uri: Uri, dao: FinanceDao): String =
        withContext(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw ImportException("Could not open the file.")
                val text = input.use { readLimited(it) }
                ImportCore.summaryText(ImportCore.merge(dao, ImportCore.parse(text)))
            } catch (e: ImportException) {
                e.message ?: "Import failed."
            } catch (e: Exception) {
                "Import failed. The file could not be read."
            }
        }

    private fun readLimited(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw ImportException("The file is too large (over 20 MB).")
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

// ===== PURE LOGIC (no Android code below this line) =====

class ImportException(message: String) : Exception(message)

data class ImportData(
    val transactions: List<Transaction>,
    val accounts: List<KnownAccount>,
    val topUps: List<Long>,
    val goals: List<Goal>,
    val rules: List<CategoryRule>,
    val invalid: Int                       // records that were skipped because they were not valid
)

data class ImportSummary(
    val txAdded: Int, val txSkipped: Int, val accounts: Int,
    val topUps: Int, val goals: Int, val rules: Int, val invalid: Int
)

object ImportCore {
    private val TYPES = setOf("DEBIT", "CREDIT", "UNKNOWN")
    private val STATES = setOf("NONE", "POSSIBLE", "PROBABLE", "CONFIRMED", "REJECTED")
    private val KINDS = setOf("SPEND_LIMIT", "EARN_TARGET")
    private val PERIODS = setOf("DAILY", "WEEKLY", "MONTHLY", "FIXED")
    private val UNITS = setOf("DAYS", "WEEKS", "MONTHS", "YEARS")
    private const val MAX_ITEMS = 200_000

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)
    private fun isId(s: String?): Boolean = s != null && s.length == 4 && s.all { it in '0'..'9' }

    /** Reads and checks the file. Records that are not valid are skipped and counted. */
    fun parse(raw: String): ImportData {
        val text = raw.removePrefix("\uFEFF").trim()
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ImportException("This is not an Expensians export. Only the JSON export can be imported.")
        }
        if (root.optString("app") != "Expensians") {
            throw ImportException("This file was not exported by Expensians.")
        }
        val version = root.optInt("formatVersion", 0)
        if (version < 1) throw ImportException("This file has no format version, so it cannot be imported.")
        if (version > DataExport.FORMAT_VERSION) {
            throw ImportException("This file comes from a newer version of Expensians. Update the app first.")
        }

        var invalid = 0

        val txs = ArrayList<Transaction>()
        root.optJSONArray("transactions")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
                val o = arr.optJSONObject(i)
                val type = o?.str("type")
                val amount = o?.optLong("amount", -1L) ?: -1L
                val date = o?.optLong("date", 0L) ?: 0L
                if (o == null || type == null || type !in TYPES || amount < 0 || date <= 0) {
                    invalid++
                    continue
                }
                txs.add(
                    Transaction(
                        senderName = (o.str("senderName") ?: "Unknown").take(100),
                        amount = amount,
                        type = type,
                        reason = (o.str("reason") ?: "Uncategorized").take(60),
                        date = date,
                        isParsed = o.optBoolean("isParsed", type != "UNKNOWN"),
                        yourAccount = o.str("yourAccount")?.takeIf { isId(it) } ?: "",
                        otherAccount = o.str("otherAccount")?.takeIf { isId(it) },
                        balance = if (o.isNull("balance")) null else o.optLong("balance", 0L),
                        isTopUp = o.optBoolean("isTopUp", false),
                        topUpState = o.str("topUpState")?.takeIf { it in STATES } ?: "NONE",
                        autoLinked = o.optBoolean("autoLinked", false),
                        nameConflict = o.optBoolean("nameConflict", false),
                        ownTransfer = o.optBoolean("ownTransfer", false)
                    )
                )
            }
        }

        val accounts = ArrayList<KnownAccount>()
        root.optJSONArray("accounts")?.let { arr ->
            for (i in 0 until minOf(arr.length(), MAX_ITEMS)) {
                val o = arr.optJSONObject(i)
                val id = o?.str("id")
                if (o == null || !isId(id)) { invalid++; continue }
                accounts.add(
                    KnownAccount(
                        id = id!!,
                        name = o.str("name")?.take(100)?.takeIf { it.isNotBlank() },
                        isOwn = o.optBoolean("isOwn", false),
                        userSaved = o.optBoolean("userSaved", false),
                        lastSeen = o.optLong("lastSeen", 0L)
                    )
                )
            }
        }

        val topUps = ArrayList<Long>()
        root.optJSONArray("topUpAmounts")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 1000)) {
                val v = arr.optLong(i, -1L)
                if (v in 1L..100L) topUps.add(v) else invalid++
            }
        }

        val goals = ArrayList<Goal>()
        root.optJSONArray("goals")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 1000)) {
                val o = arr.optJSONObject(i)
                val kind = o?.str("kind")
                val period = o?.str("period")
                val unit = o?.str("unit") ?: "DAYS"
                val amount = o?.optLong("amount", 0L) ?: 0L
                val length = o?.optInt("length", 1) ?: 1
                val start = o?.optLong("startDate", 0L) ?: 0L
                if (o == null || kind !in KINDS || period !in PERIODS || unit !in UNITS ||
                    amount <= 0 || length !in 1..999 || start <= 0
                ) { invalid++; continue }
                goals.add(
                    Goal(kind = kind!!, amount = amount, period = period!!, length = length,
                        unit = unit, startDate = start)
                )
            }
        }

        val rules = ArrayList<CategoryRule>()
        root.optJSONArray("rules")?.let { arr ->
            for (i in 0 until minOf(arr.length(), 1000)) {
                val o = arr.optJSONObject(i)
                val mn = o?.optDouble("minAmount", -1.0) ?: -1.0
                val mx = o?.optDouble("maxAmount", -1.0) ?: -1.0
                if (o == null || mn < 0 || mx < mn) { invalid++; continue }
                rules.add(CategoryRule(minAmount = mn, maxAmount = mx, reason = (o.str("reason") ?: "").take(60)))
            }
        }

        return ImportData(txs, accounts, topUps, goals, rules, invalid)
    }

    private fun txKey(t: Transaction) = "${t.date}|${t.amount}|${t.type}|${t.yourAccount}|${t.balance}"

    /** Adds what is new. New rows always get new ids, so nothing can overwrite an existing row. */
    suspend fun merge(dao: FinanceDao, d: ImportData): ImportSummary {
        // transactions: an exact repeat (same time, amount, direction, account, balance) is skipped
        val seen = dao.getAllTransactions().first().map { txKey(it) }.toHashSet()
        var added = 0
        var skipped = 0
        for (t in d.transactions) {
            if (seen.add(txKey(t))) { dao.insertTransaction(t.copy(id = 0L)); added++ } else skipped++
        }

        // accounts: a name the user saved here always wins, a saved name from the file fills a gap
        val known = dao.getAccountsOnce().associateBy { it.id }
        var accountsChanged = 0
        for (a in d.accounts) {
            val cur = known[a.id]
            val merged = if (cur == null) a else {
                val keepOurs = cur.userSaved && cur.name != null
                val takeTheirs = !keepOurs && a.userSaved && a.name != null
                cur.copy(
                    name = when {
                        keepOurs -> cur.name
                        takeTheirs -> a.name
                        cur.name != null -> cur.name
                        else -> a.name
                    },
                    userSaved = keepOurs || takeTheirs,
                    isOwn = cur.isOwn || a.isOwn,
                    lastSeen = maxOf(cur.lastSeen, a.lastSeen)
                )
            }
            if (merged != cur) { dao.upsertAccount(merged); accountsChanged++ }
        }

        val haveTopUps = dao.getTopUpAmountsOnce().toHashSet()
        var topUpsAdded = 0
        for (v in d.topUps) if (haveTopUps.add(v)) { dao.insertTopUp(TopUpAmount(v)); topUpsAdded++ }

        fun goalKey(g: Goal) = "${g.kind}|${g.amount}|${g.period}|${g.length}|${g.unit}|${g.startDate}"
        val haveGoals = dao.getGoalsOnce().map { goalKey(it) }.toHashSet()
        var goalsAdded = 0
        for (g in d.goals) if (haveGoals.add(goalKey(g))) { dao.upsertGoal(g.copy(id = 0L)); goalsAdded++ }

        fun ruleKey(r: CategoryRule) = "${r.minAmount}|${r.maxAmount}|${r.reason}"
        val haveRules = dao.getRulesOnce().map { ruleKey(it) }.toHashSet()
        var rulesAdded = 0
        for (r in d.rules) if (haveRules.add(ruleKey(r))) { dao.insertRule(r.copy(id = 0L)); rulesAdded++ }

        return ImportSummary(added, skipped, accountsChanged, topUpsAdded, goalsAdded, rulesAdded, d.invalid)
    }

    fun summaryText(s: ImportSummary): String {
        val lines = mutableListOf<String>()
        val nothingNew = s.txAdded == 0 && s.accounts == 0 && s.topUps == 0 && s.goals == 0 && s.rules == 0
        if (nothingNew) {
            lines.add("Nothing new: everything in this file is already in the app.")
        } else {
            lines.add("Imported ${s.txAdded} new transactions.")
            if (s.accounts > 0) lines.add("Accounts added or updated: ${s.accounts}.")
            if (s.topUps > 0) lines.add("Top-up amounts added: ${s.topUps}.")
            if (s.goals > 0) lines.add("Goals added: ${s.goals}.")
            if (s.rules > 0) lines.add("Rules added: ${s.rules}.")
        }
        if (s.txSkipped > 0) lines.add("${s.txSkipped} transactions were already in the app and were skipped.")
        if (s.invalid > 0) lines.add("${s.invalid} records in the file were not valid and were skipped.")
        return lines.joinToString("\n")
    }
}