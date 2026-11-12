package dev.wonderdesk.wayfinder

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.StatFs
import android.widget.RemoteViews
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/** Free space and one-tap shortcuts to the four places you choose. */
class StorageWidget : AppWidgetProvider() {
    companion object {
        /** key to (button label, how to open it). */
        val PLACES: List<Triple<String, String, (Intent) -> Unit>> = listOf(
            Triple("dl", "Downloads", { i: Intent -> i.putExtra("path", dir(Environment.DIRECTORY_DOWNLOADS)) }),
            Triple("cam", "Camera", { i: Intent -> i.putExtra("path", dir(Environment.DIRECTORY_DCIM)) }),
            Triple("pics", "Pictures", { i: Intent -> i.putExtra("path", dir(Environment.DIRECTORY_PICTURES)) }),
            Triple("docs", "Documents", { i: Intent -> i.putExtra("path", dir(Environment.DIRECTORY_DOCUMENTS)) }),
            Triple("music", "Music", { i: Intent -> i.putExtra("path", dir(Environment.DIRECTORY_MUSIC)) }),
            Triple("movies", "Movies", { i: Intent -> i.putExtra("path", dir(Environment.DIRECTORY_MOVIES)) }),
            Triple("safe", "Safe", { i: Intent -> i.putExtra("tab", "safe") }),
            Triple("net", "Network", { i: Intent -> i.putExtra("tab", "network") }),
            Triple("bin", "Recycle bin", { i: Intent -> i.putExtra("tab", "trash") }),
            Triple("home", "Internal", { i: Intent -> i.putExtra("path", Environment.getExternalStorageDirectory().path) }),
        )
        val DEFAULT = listOf("dl", "cam", "docs", "safe")
        private fun dir(d: String) = Environment.getExternalStoragePublicDirectory(d).path

        fun build(c: Context, id: Int): RemoteViews = dev.wonderdesk.shared.responsiveWidget(R.layout.widget_storage_small, null, R.layout.widget_storage) { fill(c, id, it) }
        private fun fill(c: Context, id: Int, layout: Int): RemoteViews {
            val s = WidgetPrefs(c, id)
            val v = RemoteViews(c.packageName, layout)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            v.applyLook(look, R.id.w_root, Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear), listOf(R.id.w_label), listOf(R.id.w_free))
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            v.setProgressBar(R.id.w_bar, 100, ((st.totalBytes - st.availableBytes) * 100 / st.totalBytes.coerceAtLeast(1)).toInt(), false)
            v.setTextViewText(R.id.w_free, "${formatBytes(st.availableBytes)} free of ${formatBytes(st.totalBytes)}")
            fun open(rc: Int, block: (Intent) -> Unit) = PendingIntent.getActivity(c, rc, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).also(block), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            v.setOnClickPendingIntent(R.id.w_root, open(6100 + id % 100) {})
            val chosen = s.str("places", DEFAULT.joinToString(",")).split(',').filter { k -> PLACES.any { it.first == k } }.ifEmpty { DEFAULT }.take(4)
            listOf(R.id.w_dl, R.id.w_cam, R.id.w_docs, R.id.w_safe).forEachIndexed { i, btn ->
                val place = chosen.getOrNull(i)?.let { k -> PLACES.first { it.first == k } }
                if (place == null) v.setViewVisibility(btn, android.view.View.GONE) else {
                    v.setViewVisibility(btn, android.view.View.VISIBLE); v.setTextViewText(btn, place.second)
                    v.setOnClickPendingIntent(btn, open(6200 + (id % 100) * 10 + i, place.third))
                }
            }
            return v
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } } }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class StorageWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { AppWidgetManager.getInstance(this).updateAppWidget(widgetId, StorageWidget.build(this, widgetId)) }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        var chosen by remember { mutableStateOf(s.str("places", StorageWidget.DEFAULT.joinToString(",")).split(',').filter { it.isNotBlank() }) }
        WidgetSetup("Wayfinder", "Storage widget", onCancel = { finish() }, onSave = { s.put("look" to look.name, "places" to chosen.joinToString(",")); save() }) {
            Panel(title = "Shortcuts (up to 4)", footer = "They appear on the widget in the order you tick them.") {
                StorageWidget.PLACES.forEachIndexed { i, (k, label, _) ->
                    if (i > 0) PanelDivider()
                    val on = k in chosen
                    ListRow(label, subtitle = if (on) "Button ${chosen.indexOf(k) + 1}" else null,
                        trailing = { Checkbox(on, { t -> chosen = if (t) (chosen + k).take(4) else chosen - k }, enabled = on || chosen.size < 4) },
                        onClick = { chosen = if (on) chosen - k else if (chosen.size < 4) chosen + k else chosen })
                }
            }
            WidgetLookPicker(look) { look = it }
        }
    }
}
