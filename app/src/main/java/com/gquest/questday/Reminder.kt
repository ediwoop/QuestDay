package com.gquest.questday

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class Reminder(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = Repository(applicationContext)
        try {
            val s = repo.state.first { it.ready }
            val today = LocalDate.now()
            if (
                s.profile.reminders && !s.rested(today) && s.today(today).any { !s.done(it, today) }
            ) {
                val nm = applicationContext.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(
                        "missions",
                        "Misiones de hoy",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
                if (
                    Build.VERSION.SDK_INT < 33 ||
                        applicationContext.checkSelfPermission(
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val pending =
                        PendingIntent.getActivity(
                            applicationContext,
                            0,
                            Intent(applicationContext, MainActivity::class.java),
                            PendingIntent.FLAG_IMMUTABLE,
                        )
                    nm.notify(
                        1,
                        Notification.Builder(applicationContext, "missions")
                            .setSmallIcon(com.gquest.questday.R.drawable.ic_quest)
                            .setContentTitle("Tu aventura te espera")
                            .setContentText(
                                "Un pequeño paso también cuenta. Revisa tus misiones de hoy."
                            )
                            .setContentIntent(pending)
                            .setAutoCancel(true)
                            .build(),
                    )
                }
            }
            schedule(applicationContext, s.profile.reminders, s.profile.hour)
            return Result.success()
        } finally {
            repo.db.close()
        }
    }

    companion object {
        fun schedule(c: Context, enabled: Boolean, hour: Int) {
            val wm = WorkManager.getInstance(c)
            if (!enabled) {
                wm.cancelUniqueWork("daily-reminder")
                return
            }
            val now = ZonedDateTime.now()
            var next = now.withHour(hour).withMinute(0).withSecond(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            wm.enqueueUniqueWork(
                "daily-reminder",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<Reminder>()
                    .setInitialDelay(Duration.between(now, next).seconds, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
