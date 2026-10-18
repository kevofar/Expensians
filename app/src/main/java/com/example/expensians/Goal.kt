package com.example.expensians

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar

@Entity(tableName = "goals")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,               // SPEND_LIMIT or EARN_TARGET
    val amount: Long,               // ETB, no cents
    val period: String,             // DAILY, WEEKLY, MONTHLY (repeat) or FIXED (runs once)
    val length: Int = 1,            // FIXED only: how many units
    val unit: String = "DAYS",      // FIXED only: DAYS, WEEKS, MONTHS or YEARS
    val startDate: Long,            // FIXED only: start of the first day
    val notifiedWindow: Long = 0L,  // start of the period that was last notified
    val notifiedLevel: Int = 0      // 0 nothing sent, 1 = 80% sent, 2 = limit/goal reached sent
)

data class GoalWindow(val start: Long, val end: Long)   // end is exclusive

data class GoalProgress(
    val goal: Goal,
    val window: GoalWindow,
    val current: Long,      // spent (limit) or earned (target) inside the window
    val fraction: Float,    // 0..1 for the progress bar
    val level: Int,         // 0 normal, 1 = 80% of a limit, 2 = limit reached / target reached
    val finished: Boolean,  // only FIXED goals can finish
    val achieved: Boolean,  // result of a finished goal
    val daysLeft: Int
)

// Pure logic: no Android code here.
object GoalEngine {
    const val SPEND = "SPEND_LIMIT"
    const val EARN = "EARN_TARGET"

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun startOfDay(ms: Long): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun shiftBy(ms: Long, unit: String, n: Int): Long = Calendar.getInstance().apply {
        timeInMillis = ms
        when (unit) {
            "DAYS" -> add(Calendar.DAY_OF_YEAR, n)
            "WEEKS" -> add(Calendar.WEEK_OF_YEAR, n)
            "MONTHS" -> add(Calendar.MONTH, n)
            "YEARS" -> add(Calendar.YEAR, n)
        }
    }.timeInMillis

    /** The period the goal is looking at right now. */
    fun window(goal: Goal, now: Long): GoalWindow {
        val day = startOfDay(now)
        return when (goal.period) {
            "DAILY" -> GoalWindow(day, shiftBy(day, "DAYS", 1))
            "WEEKLY" -> {
                // weeks run Monday to Sunday
                val cal = Calendar.getInstance().apply { timeInMillis = day }
                val sinceMonday = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7   // Mon=0 ... Sun=6
                cal.add(Calendar.DAY_OF_YEAR, -sinceMonday)
                val monday = cal.timeInMillis
                GoalWindow(monday, shiftBy(monday, "DAYS", 7))
            }
            "MONTHLY" -> {
                val first = Calendar.getInstance().apply {
                    timeInMillis = day
                    set(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
                GoalWindow(first, shiftBy(first, "MONTHS", 1))
            }
            else -> GoalWindow(goal.startDate, shiftBy(goal.startDate, goal.unit, goal.length))
        }
    }

    /** Parsed messages only, own-account transfers excluded (same as Today's spending). */
    fun sum(transactions: List<Transaction>, kind: String, w: GoalWindow): Long {
        val type = if (kind == SPEND) "DEBIT" else "CREDIT"
        return transactions
            .filter { it.isParsed && !it.ownTransfer && it.type == type && it.date >= w.start && it.date < w.end }
            .sumOf { it.amount }
    }

    fun level(goal: Goal, current: Long): Int = when {
        goal.amount <= 0 -> 0
        current >= goal.amount -> 2
        goal.kind == SPEND && current * 100 >= goal.amount * 80 -> 1
        else -> 0
    }

    fun progress(goal: Goal, transactions: List<Transaction>, now: Long): GoalProgress {
        val w = window(goal, now)
        val current = sum(transactions, goal.kind, w)
        val finished = goal.period == "FIXED" && now >= w.end
        val achieved = if (goal.kind == SPEND) current <= goal.amount else current >= goal.amount
        val fraction =
            if (goal.amount <= 0) 0f else (current.toFloat() / goal.amount.toFloat()).coerceIn(0f, 1f)
        val daysLeft =
            if (finished) 0 else (((w.end - now) + DAY_MS - 1) / DAY_MS).toInt().coerceAtLeast(1)
        return GoalProgress(goal, w, current, fraction, level(goal, current), finished, achieved, daysLeft)
    }

    fun title(g: Goal): String {
        val what = if (g.kind == SPEND) "spending limit" else "earning goal"
        return when (g.period) {
            "DAILY" -> "Daily $what"
            "WEEKLY" -> "Weekly $what"
            "MONTHLY" -> "Monthly $what"
            else -> {
                val u = when (g.unit) {
                    "DAYS" -> "day"
                    "WEEKS" -> "week"
                    "MONTHS" -> "month"
                    else -> "year"
                }
                "${g.length}-$u $what"
            }
        }
    }
}