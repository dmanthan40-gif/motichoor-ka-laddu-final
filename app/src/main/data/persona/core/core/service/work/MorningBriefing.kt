package com.duggu.laddu.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.duggu.laddu.core.AssistantEngine
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class MorningBriefingWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val briefing = buildBriefing()
        runCatching { AssistantEngine.respond(applicationContext, briefing) }
        AlarmReceiver.scheduleNext(applicationContext) // one-shot alarm: re-arm for tomorrow
        return Result.success()
    }

    private fun weatherCelsius(): Double? = runCatching {
        val url = "https://api.open-meteo.com/v1/forecast" + // free, no key
            "?latitude=30.3165&longitude=78.0322&current_weather=true" // Dehradun
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            Regex("\"temperature\":\\s*(-?[0-9.]+)")
                .find(resp.body?.string().orEmpty())?.groupValues?.get(1)?.toDoubleOrNull()
        }
    }.getOrNull()

    private suspend fun buildBriefing(): String {
        val today = LocalDate.now()
        var anniversary = LocalDate.of(2026, 5, 2)
        if (today.isAfter(anniversary)) anniversary = anniversary.plusYears(1)
        val daysToAnniversary = ChronoUnit.DAYS.between(today, anniversary)

        var nextBirthday = LocalDate.of(today.year, 5, 14)
        if (today.isAfter(nextBirthday)) nextBirthday = nextBirthday.plusYears(1)
        val daysToBirthday = ChronoUnit.DAYS.between(today, nextBirthday)
        val dugguAge = ChronoUnit.YEARS.between(LocalDate.of(2010, 5, 14), today)

        val weather = weatherCelsius()
        val weatherLine = weather?.let { "Current temperature in Dehradun is $it degrees Celsius." }
            ?: "I couldn't fetch the weather right now."

        // Sent as the "user" turn; Motichoor turns it into a loving spoken briefing.
        return "Good morning Motichoor! Give Duggu a warm morning briefing. " +
            "$weatherLine " +
            "Duggu is $dugguAge years old, her birthday is in $daysToBirthday days, " +
            "and the anniversary is in $daysToAnniversary days. " +
            "Open with something loving, mention the countdown, and keep it under 5 sentences."
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        scheduleNext(context) // re-arm first, always
        WorkManager.getInstance(context).enqueueUniqueWork(
            "morning_briefing",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<MorningBriefingWorker>().build()
        )
    }

    companion object {
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 1001, Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            var next = LocalDateTime.now().toLocalDate().atTime(7, 0)
            if (LocalDateTime.now() >= next) next = next.plusDays(1)
            val triggerAt = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) AlarmReceiver.scheduleNext(context)
    }
}
