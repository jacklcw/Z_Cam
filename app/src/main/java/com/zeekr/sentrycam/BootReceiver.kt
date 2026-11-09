package com.zeekr.sentrycam

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restart the sentry service after the head unit boots or the app is updated, so the
 * car keeps listening for capture commands without the user re-opening the app.
 * Only auto-starts if a pairing token exists (i.e. the car was already linked) or standalone mode is on.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        startIfWanted(ctx)
        ServiceWatchdog.schedule(ctx)
    }

    companion object {
        /** Starts [CamService] when something in the app needs it. A refused start (Android may block a camera service from the background) is retried by [ServiceWatchdog]. */
        fun startIfWanted(ctx: Context) {
            val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            val featureOn = listOf("bsHud", "revOn", "webHudOn", "webFloatOn", "flLeft", "flRight", "flRear", "flFront", "twOn", "pfOn").any { p.getBoolean(it, false) }
            if (p.getString("token", "").isNullOrEmpty() && !p.getBoolean("standalone", false) && !featureOn) return
            if (p.getBoolean("suspended", false)) return   // relay suspended it (battery floor)
            try {
                val i = Intent(ctx, CamService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Throwable) {
                Logger.log(ctx, "boot start refused (${e.javaClass.simpleName}); the watchdog job will retry")
            }
        }
    }
}

/**
 * Safety net for "I have to open the app once after driving": if the system killed the service while the car slept, or refused the start at
 * boot, this job (every 15 min, kept across reboots, and allowed to start a foreground service) starts it again. Starting an already running
 * service only re-applies the settings.
 */
class ServiceWatchdog : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean { BootReceiver.startIfWanted(applicationContext); return false }
    override fun onStopJob(params: JobParameters?) = false

    companion object {
        private const val JOB_ID = 7901
        fun schedule(ctx: Context) {
            try {
                val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                if (js.getPendingJob(JOB_ID) != null) return
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(ctx, ServiceWatchdog::class.java))
                    .setPeriodic(15 * 60_000L).setPersisted(true).build())
            } catch (_: Throwable) {}
        }
    }
}
