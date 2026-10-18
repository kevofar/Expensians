package com.example.expensians

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/**
 * Called after every saved SMS. Looks at every goal and sends a notification
 * when a goal crosses a new level inside its current period:
 *   level 1 (80% of a spending limit)  -> normal notification, no custom sound
 *   level 2 (spending limit reached)   -> why.mp3
 *   level 2 (earning goal reached)     -> how.mp3
 * Each level is sent only once per period.
 */
object GoalNotifier {
    const val PREFS = "expensians_prefs"
    const val PREF_VOLUME = "goal_volume_50"

    private const val CH_WARNING = "goal_warning"
    private const val CH_LIMIT = "goal_limit_sound"
    private const val CH_REACHED = "goal_reached_sound"

    suspend fun check(context: Context, dao: FinanceDao) {
        try {
            val now = System.currentTimeMillis()
            for (g in dao.getGoalsOnce()) {
                val w = GoalEngine.window(g, now)
                if (now >= w.end) continue                      // a finished fixed goal

                val type = if (g.kind == GoalEngine.SPEND) "DEBIT" else "CREDIT"
                val current = dao.sumBetween(type, w.start, w.end)
                val level = GoalEngine.level(g, current)
                val already = if (g.notifiedWindow == w.start) g.notifiedLevel else 0

                if (level > already) {
                    dao.upsertGoal(g.copy(notifiedWindow = w.start, notifiedLevel = level))
                    post(context, g, level, current)
                }
            }
        } catch (e: Exception) {
            // a goal problem must never stop SMS messages from being saved
        }
    }

    private fun money(n: Long): String = "ETB " + String.format(Locale.US, "%,d", n)

    private fun soundUri(context: Context, limitSound: Boolean): Uri =
        Uri.parse("android.resource://" + context.packageName + "/" + (if (limitSound) R.raw.why else R.raw.how))

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CH_WARNING, "Spending limit warning (80%)", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "A normal notification when 80% of a spending limit is used." }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LIMIT, "Spending limit reached", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Plays your limit sound when a spending limit is reached."
                    setSound(soundUri(context, true), attrs)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REACHED, "Earning goal reached", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Plays your celebration sound when an earning goal is reached."
                    setSound(soundUri(context, false), attrs)
                }
        )
    }

    /** Sets the notification volume to 50%, unless the phone is on silent or vibrate. */
    private fun setVolumeHalf(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_VOLUME, true)) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, maxOf(1, max / 2), 0)
        } catch (e: SecurityException) {
            // Do Not Disturb is on and the phone does not allow the change
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, g: Goal, level: Int, current: Long) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        ensureChannels(context)

        val spend = g.kind == GoalEngine.SPEND
        val channel: String
        val title: String
        when {
            spend && level == 2 -> { channel = CH_LIMIT; title = "Spending limit reached" }
            spend -> { channel = CH_WARNING; title = "80% of your spending limit used" }
            else -> { channel = CH_REACHED; title = "You made it! Goal reached 🎉" }
        }
        val text = money(current) + " of " + money(g.amount) + " · " + GoalEngine.title(g)

        if (level == 2) setVolumeHalf(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(if (level == 2) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)

        // Android 7 has no channels: the sound goes on the notification itself
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && level == 2) {
            builder.setSound(soundUri(context, spend))
        }

        nm.notify((g.id * 10 + level).toInt(), builder.build())
    }
}