package dev.wonderdesk.wayfinder

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wayfinder as Android's file picker: other apps and websites ask for a file (GET_CONTENT, OPEN_DOCUMENT) or a place to save one
 * (CREATE_DOCUMENT) and get Wayfinder's browser, with the Safe folder, hidden files and every storage visible to the user.
 */
class PickerActivity : WonderActivity() {
    @Composable override fun Content() { PickerScreen(intent, { r -> setResult(r.first, r.second); finish() }) }
}

private fun mimeOf(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"

private fun matches(mime: String, filters: List<String>): Boolean = filters.isEmpty() || filters.any { f ->
    f == "*/*" || f == mime || (f.endsWith("/*") && mime.startsWith(f.dropLast(1)))
}

@Composable
private fun PickerScreen(intent: Intent, finish: (Pair<Int, Intent?>) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val create = intent.action == Intent.ACTION_CREATE_DOCUMENT
    val multiple = !create && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
    val filters = remember {
        val list = (intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList() ?: listOfNotNull(intent.type)).filter { it.isNotBlank() }
        list
    }
    val root = remember { Environment.getExternalStorageDirectory() }
    var dir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<Entry>?>(null) }
    var picked by remember { mutableStateOf<List<File>>(emptyList()) }
    var name by remember { mutableStateOf(intent.getStringExtra(Intent.EXTRA_TITLE) ?: "") }
    var showHidden by remember { mutableStateOf(false) }
    val allFiles = remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val resumed = rememberIsResumed()
    LaunchedEffect(resumed) { allFiles.value = Environment.isExternalStorageManager() }
    LaunchedEffect(dir, showHidden, allFiles.value) {
        entries = null
        entries = withContext(Dispatchers.IO) { Listing.sort(Listing.list(dir, showHidden).filter { it.isDir || create.not() && matches(mimeOf(it.file), filters) || create }, "Name", false) }
    }
    fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    fun deliver(files: List<File>, writable: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION else Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val uris = files.map { uriFor(it) }
        val r = Intent().setFlags(flags).setData(uris.first())
        if (uris.size > 1 || multiple) { val cd = ClipData.newRawUri(null, uris.first()); uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }; r.clipData = cd }
        finish(android.app.Activity.RESULT_OK to r)
    }
    fun up() { if (dir.path != root.path && dir.parentFile != null) dir = dir.parentFile!! else finish(android.app.Activity.RESULT_CANCELED to null) }
    BackHandler { up() }
    val title = when { create -> "Save as"; multiple -> "Choose files"; intent.action == Intent.ACTION_OPEN_DOCUMENT_TREE -> "Choose a folder"; else -> "Choose a file" }
    val rel = dir.path.removePrefix(root.path).ifBlank { "/" }
    WonderApp(appName = "Wayfinder", about = "Wayfinder is your file manager and the place other apps send you to pick a file.", title = title, subtitle = if (filters.isNotEmpty() && filters != listOf("*/*")) "Showing ${filters.joinToString { f -> when { f == "image/*" -> "pictures"; f == "video/*" -> "videos"; f == "audio/*" -> "audio"; f == "text/*" -> "text files"; f == "application/pdf" -> "PDFs"; f.endsWith("/*") -> f.substringBefore('/') + " files"; else -> f.substringAfter('/') } }}" else rel, onBack = { finish(android.app.Activity.RESULT_CANCELED to null) },
        actions = { ToolbarButton(if (showHidden) WonderIcons.VisibilityOff else WonderIcons.Visibility, "Hidden files", { showHidden = !showHidden }) }) {
        Column(Modifier.fillMaxSize()) {
            if (!allFiles.value) {
                Panel(Modifier.padding(16.dp)) {
                    EmptyState("Wayfinder needs all-files access", "Without it Wayfinder can only offer files Android already shares. Allow it once and every app that asks for a file gets your full storage.", icon = WonderIcons.Folder,
                        action = { WButton("Allow all files", { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) }, kind = ButtonKind.Primary) })
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Internal" to root, "Downloads" to File(root, "Download"), "Pictures" to File(root, "Pictures"), "DCIM" to File(root, "DCIM"), "Documents" to File(root, "Documents"), "Music" to File(root, "Music"), "Movies" to File(root, "Movies"))
                    .filter { it.second.isDirectory }.forEach { (n, f) -> Chip(n, dir.path == f.path, { dir = f }) }
            }
            Text(rel, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), color = p.secondaryText, style = MaterialTheme.typography.labelMedium)
            val list = entries
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list == null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { LoadingStatus("Reading ${dir.name.ifBlank { "storage" }}", detail = "Listing the files and folders inside") }
                    list.isEmpty() -> EmptyState(if (filters.isEmpty() || create) "Empty folder" else "Nothing to pick here", if (create) "Choose a name below and save here." else "No matching files in this folder.", icon = WonderIcons.Folder)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 12.dp)) {
                        item { Panel {
                            list.forEachIndexed { i, e ->
                                if (i > 0) PanelDivider()
                                val (icon, tint) = kindIcon(e.kind)
                                val on = e.file in picked
                                ListRow(e.name, subtitle = if (e.isDir) "${e.children} items" else formatBytes(e.size), icon = icon, tint = tint, chevron = e.isDir, selected = on,
                                    onClick = {
                                        when {
                                            e.isDir -> dir = e.file
                                            create -> name = e.name
                                            multiple -> picked = if (on) picked - e.file else picked + e.file
                                            else -> deliver(listOf(e.file), false)
                                        }
                                    })
                            }
                        } }
                    }
                }
            }
            if (create) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(name, { name = it }, "File name")
                WButton("Save here", {
                    val base = name.trim().ifBlank { "Untitled" }; var f = File(dir, base); var n = 1
                    while (f.exists()) { f = File(dir, base.substringBeforeLast('.', base) + " ($n)" + (if (base.contains('.')) "." + base.substringAfterLast('.') else "")); n++ }
                    runCatching { f.createNewFile() }.onSuccess { deliver(listOf(f), true) }
                }, Modifier.fillMaxWidth(), icon = WonderIcons.Check, kind = ButtonKind.Primary, enabled = name.isNotBlank())
            }
            if (multiple && picked.isNotEmpty()) WButton("Choose ${picked.size} file${if (picked.size == 1) "" else "s"}", { deliver(picked, false) }, Modifier.fillMaxWidth().padding(12.dp), icon = WonderIcons.Check, kind = ButtonKind.Primary)
        }
    }
}
