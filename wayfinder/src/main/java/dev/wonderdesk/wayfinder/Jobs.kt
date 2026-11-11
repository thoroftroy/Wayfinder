package dev.wonderdesk.wayfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * File work that runs in the background: copy, move, delete, compress, extract, network transfers and
 * the Safe folder. Jobs run one after another, keep going when you leave the app, show progress in a
 * notification with Cancel, and never block the screen.
 */
object Jobs {
    class Job(val id: Int, val label: String, val work: (Job) -> Unit) {
        var progress by mutableStateOf<Progress?>(null)
        var state by mutableStateOf("waiting")   // waiting | running | done | failed | cancelled
        var error by mutableStateOf<String?>(null)
        var startedAt = 0L
        @Volatile var cancelled = false
        fun report(p: Progress) { progress = p; Ops.cancel = cancelled; if (cancelled) throw Cancelled() }
        fun check() { if (cancelled) throw Cancelled() }
        /** Rough time left from the speed so far. */
        val remaining: Long? get() {
            val p = progress ?: return null
            if (p.done <= 0 || startedAt == 0L) return null
            val el = System.currentTimeMillis() - startedAt
            return ((p.total - p.done) * el / p.done).takeIf { el > 1500 }
        }
    }

    val all = mutableStateListOf<Job>()
    val active: List<Job> get() = all.filter { it.state == "waiting" || it.state == "running" }
    /** Called on the main thread when a job ends (for refreshing lists and toasts). */
    val listeners = mutableListOf<(Job) -> Unit>()
    private var nextId = 1
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: kotlinx.coroutines.Job? = null

    fun enqueue(c: Context, label: String, work: (Job) -> Unit): Job {
        val j = Job(nextId++, label, work)
        all.add(j)
        all.removeAll { it.state in setOf("done", "failed", "cancelled") && all.indexOf(it) < all.size - 20 }
        c.startForegroundService(Intent(c, JobService::class.java))
        if (worker?.isActive != true) worker = scope.launch { run(c.applicationContext) }
        return j
    }

    fun cancel(j: Job) { j.cancelled = true; if (j.state == "waiting") j.state = "cancelled"; Ops.cancel = true }
    fun clearFinished() { all.removeAll { it.state in setOf("done", "failed", "cancelled") } }

    private suspend fun run(c: Context) {
        while (true) {
            val j = all.firstOrNull { it.state == "waiting" } ?: break
            j.state = "running"; j.startedAt = System.currentTimeMillis(); Ops.cancel = false
            JobService.update(c)
            val r = withContext(Dispatchers.IO) { runCatching { j.work(j) } }
            j.state = when { r.isSuccess -> "done"; r.exceptionOrNull() is Cancelled || j.cancelled -> "cancelled"; else -> "failed" }
            j.error = r.exceptionOrNull()?.takeIf { it !is Cancelled }?.let { it.message ?: it.javaClass.simpleName }
            Ops.cancel = false
            listeners.toList().forEach { it(j) }
            JobService.update(c)
        }
        JobService.finish(c)
    }
}

/** Keeps file jobs alive in the background, with a progress notification and Cancel. */
class JobService : Service() {
    companion object {
        private const val CH = "file-jobs"
        private const val ID = 31
        private var instance: JobService? = null

        fun update(c: Context) = instance?.post()
        fun finish(c: Context) { instance?.let { s ->
            val last = Jobs.all.lastOrNull()
            s.stopForeground(STOP_FOREGROUND_REMOVE)
            if (last != null && last.state != "cancelled") {
                val n = Notification.Builder(s, CH).setSmallIcon(if (last.state == "done") android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                    .setContentTitle(if (last.state == "done") "${last.label} finished" else "${last.label} didn't finish").setContentText(last.error ?: "Tap to open Wayfinder")
                    .setContentIntent(PendingIntent.getActivity(s, 1, Intent(s, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).setTimeoutAfter(10_000).build()
                s.getSystemService(NotificationManager::class.java).notify(ID + 1, n)
            }
            s.stopSelf()
        } }
    }

    private var wake: PowerManager.WakeLock? = null
    private var lastPost = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate(); instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CH, "File operations", NotificationManager.IMPORTANCE_LOW))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wayfinder:jobs").apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { Jobs.all.firstOrNull { it.state == "running" }?.let { Jobs.cancel(it) }; return START_NOT_STICKY }
        val n = build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(ID, n)
        // Refresh the progress bar about twice a second while work runs.
        Thread { while (instance === this && Jobs.active.isNotEmpty()) { post(); Thread.sleep(500) } }.start()
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val j = Jobs.all.firstOrNull { it.state == "running" } ?: Jobs.active.firstOrNull()
        val p = j?.progress
        val waiting = Jobs.active.size - 1
        val cancel = PendingIntent.getService(this, 2, Intent(this, JobService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE)
        val pct = p?.let { (it.done * 100 / it.total.coerceAtLeast(1)).toInt().coerceIn(0, 100) }
        val left = j?.remaining?.let { ms -> " · " + if (ms > 90_000) "${ms / 60_000} min left" else "${(ms / 1000).coerceAtLeast(1)} s left" } ?: ""
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle((j?.label ?: "Working") + (pct?.let { " · $it%" } ?: ""))
            .setContentText((p?.let { "${dev.wonderdesk.shared.formatBytes(it.done)} of ${dev.wonderdesk.shared.formatBytes(it.total)} · ${it.current}" } ?: "Preparing") + left + if (waiting > 0) " · $waiting more waiting" else "")
            .setProgress(100, pct ?: 0, pct == null).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }

    fun post() {
        val now = System.currentTimeMillis(); if (now - lastPost < 400) return; lastPost = now
        if (Jobs.active.isEmpty()) return
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, build()) }
    }

    override fun onDestroy() { instance = null; runCatching { if (wake?.isHeld == true) wake?.release() }; super.onDestroy() }
}
