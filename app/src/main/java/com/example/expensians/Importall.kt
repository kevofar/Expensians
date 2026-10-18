package com.example.expensians

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The Import button calls this. It looks at the file and decides:
 *   starts with "{"  -> the JSON export  (uses ImportCore from DataImport.kt)
 *   anything else    -> the CSV export   (uses CsvImportCore below)
 * Both only ADD data. Nothing is deleted or overwritten.
 */
object ImportAll {
    private const val MAX_BYTES = 20 * 1024 * 1024

    /** Returns the text to show the user when the import is finished. */
    suspend fun run(context: Context, uri: Uri, dao: FinanceDao): String =
        withContext(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw ImportException("Could not open the file.")
                val text = input.use { readLimited(it) }
                val start = text.removePrefix("\uFEFF").trimStart()
                if (start.startsWith("{")) {
                    ImportCore.summaryText(ImportCore.merge(dao, ImportCore.parse(text)))
                } else {
                    CsvImportCore.importCsv(dao, text)
                }
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

object CsvImportCore {
    private val TYPES = setOf("DEBIT", "CREDIT", "UNKNOWN")
    private const val MAX_ROWS = 200_000

    private fun isId(s: String) = s.length == 4 && s.all { it in '0'..'9' }

    /** Splits CSV text into rows of fields. Quotes, commas and line breaks inside quotes are handled. */
    fun rows(text: String): List<List<String>> {
        val s = text.removePrefix("\uFEFF")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < s.length && s[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> {
                        row.add(field.toString())
                        field.setLength(0)
                    }
                    '\r' -> {}
                    '\n' -> {
                        row.add(field.toString())
                        field.setLength(0)
                        rows.add(row)
                        row = ArrayList()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private fun parseDate(s: String): Long? {
        for (pattern in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm")) {
            val f = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            val t = try {
                f.parse(s)?.time
            } catch (e: Exception) {
                null
            }
            if (t != null) return t
        }
        return null
    }

    // The CSV keeps the time to the second only, so a repeat is judged to the second.
    private fun key(t: Transaction) =
        "${t.date / 1000}|${t.amount}|${t.type}|${t.yourAccount}|${t.balance}"

    suspend fun importCsv(dao: FinanceDao, text: String): String {
        val all = rows(text).filter { r -> r.any { it.isNotBlank() } }
        if (all.isEmpty()) throw ImportException("The file is empty.")
        if (all.size > MAX_ROWS) throw ImportException("The file has too many rows.")

        val header = all[0].map { it.trim().lowercase() }
        if (listOf("date", "type", "amount").any { it !in header }) {
            throw ImportException(
                "This is not an Expensians file. A CSV must be the one made by Export > CSV, " +
                        "with its first row (date, type, amount, ...)."
            )
        }

        val iDate = header.indexOf("date")
        val iType = header.indexOf("type")
        val iAmount = header.indexOf("amount")
        val iName = header.indexOf("name")
        val iReason = header.indexOf("reason")
        val iYours = header.indexOf("your_account")
        val iOther = header.indexOf("other_account")
        val iBalance = header.indexOf("balance")
        val iOwn = header.indexOf("own_transfer")
        val iParsed = header.indexOf("parsed")

        fun cell(r: List<String>, i: Int): String = if (i >= 0 && i < r.size) r[i].trim() else ""

        val seen = dao.getAllTransactions().first().map { key(it) }.toHashSet()
        var added = 0
        var skipped = 0
        var invalid = 0

        for (r in all.drop(1)) {
            val type = cell(r, iType).uppercase()
            val amount = cell(r, iAmount).toLongOrNull()
            val date = parseDate(cell(r, iDate))
            val balText = cell(r, iBalance)
            val balance = if (balText.isEmpty()) null else balText.toLongOrNull()

            if (type !in TYPES || amount == null || amount < 0 || date == null ||
                (balText.isNotEmpty() && balance == null)
            ) {
                invalid++
                continue
            }

            val reasonRaw = cell(r, iReason)
            val reason =
                if (reasonRaw.isBlank() || reasonRaw.equals("Unknown", ignoreCase = true)) "Uncategorized"
                else reasonRaw.take(60)
            val parsedText = cell(r, iParsed)
            val parsed =
                if (parsedText.isEmpty()) type != "UNKNOWN" && balance != null
                else parsedText.equals("true", ignoreCase = true)

            val tx = Transaction(
                senderName = cell(r, iName).ifBlank { "Unknown" }.take(100),
                amount = amount,
                type = type,
                reason = reason,
                date = date,
                isParsed = parsed,
                yourAccount = cell(r, iYours).takeIf { isId(it) } ?: "",
                otherAccount = cell(r, iOther).takeIf { isId(it) },
                balance = balance,
                ownTransfer = cell(r, iOwn).equals("true", ignoreCase = true)
            )

            if (seen.add(key(tx))) {
                dao.insertTransaction(tx)
                added++
            } else {
                skipped++
            }
        }

        return ImportCore.summaryText(ImportSummary(added, skipped, 0, 0, 0, 0, invalid)) +
                "\nA CSV holds transactions only. Use the JSON export to also restore " +
                "accounts, goals and top-up amounts."
    }
}