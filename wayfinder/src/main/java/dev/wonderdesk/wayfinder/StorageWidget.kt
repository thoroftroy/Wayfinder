package dev.wonderdesk.wayfinder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews
import dev.wonderdesk.shared.formatBytes

/** Free space and one-tap shortcuts to the folders you use most. */
class StorageWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(c.packageName, R.layout.widget_storage)
        val st = StatFs(Environment.getExternalStorageDirectory().path)
        v.setProgressBar(R.id.w_bar, 100, ((st.totalBytes - st.availableBytes) * 100 / st.totalBytes.coerceAtLeast(1)).toInt(), false)
        v.setTextViewText(R.id.w_free, "${formatBytes(st.availableBytes)} free of ${formatBytes(st.totalBytes)}")
        fun open(rc: Int, block: Intent.() -> Unit) = PendingIntent.getActivity(c, rc, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(block), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun dir(d: String) = Environment.getExternalStoragePublicDirectory(d).path
        v.setOnClickPendingIntent(R.id.w_root, open(61) {})
        v.setOnClickPendingIntent(R.id.w_dl, open(62) { putExtra("path", dir(Environment.DIRECTORY_DOWNLOADS)) })
        v.setOnClickPendingIntent(R.id.w_cam, open(63) { putExtra("path", dir(Environment.DIRECTORY_DCIM)) })
        v.setOnClickPendingIntent(R.id.w_docs, open(64) { putExtra("path", dir(Environment.DIRECTORY_DOCUMENTS)) })
        v.setOnClickPendingIntent(R.id.w_safe, open(65) { putExtra("tab", "safe") })
        ids.forEach { mgr.updateAppWidget(it, v) }
    }
}
