package dev.wonderdesk.shared

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

private fun Context.documentFlags(uri: Uri): Long = contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
    if (it.moveToFirst()) it.getLong(0) else 0L
} ?: 0L

private data class FileRow(val name: String, val folder: Boolean, val bytes: Long, val modified: Long, val doc: DocumentFile?, val raw: File?, val uri: String)

enum class FileOrder(val label: String) { NAME_ASC("Name A–Z"), NAME_DESC("Name Z–A"), SIZE_DESC("Largest first") }
data class FileSortKey(val name: String, val folder: Boolean, val bytes: Long)

/** Count files (not folders) and sum their bytes. Pure for testing; display formatting stays with the UI. */
fun summarizeSortKeys(keys: List<FileSortKey>): Pair<Int, Long> {
    var bytes = 0L
    var files = 0
    keys.forEach { if (!it.folder && it.bytes >= 0) { files++; bytes += it.bytes } }
    return files to bytes
}

/** Directories remain first in every order; ties are deterministic. */
fun compareFileKeys(a: FileSortKey, b: FileSortKey, order: FileOrder): Int {
    if (a.folder != b.folder) return if (a.folder) -1 else 1
    val names = a.name.lowercase(Locale.ROOT).compareTo(b.name.lowercase(Locale.ROOT))
    val result = when (order) {
        FileOrder.NAME_ASC -> names
        FileOrder.NAME_DESC -> -names
        FileOrder.SIZE_DESC -> if (!a.folder) b.bytes.compareTo(a.bytes).takeIf { it != 0 } ?: names else names
    }
    return if (result != 0) result else a.name.compareTo(b.name)
}

fun Context.hasAllFilesAccess(): Boolean =
    Environment.isExternalStorageManager() || runCatching { File(Environment.getExternalStorageDirectory(), "Download").canRead() }.getOrDefault(false)

private fun Context.allFilesUri(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

private fun rawRow(file: File): FileRow = FileRow(
    file.name.ifBlank { file.path }, file.isDirectory, if (file.isFile) file.length() else 0L,
    file.lastModified(), null, file, file.toURI().toString()
)

private fun docRow(doc: DocumentFile, context: Context): FileRow = FileRow(
    doc.name ?: "Unnamed", doc.isDirectory, doc.length(), doc.lastModified(), doc, null, doc.uri.toString()
)
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("files", Context.MODE_PRIVATE) }
    val op = rememberOperation()
    var mode by remember { mutableStateOf(if (context.hasAllFilesAccess()) "raw" else "saf") }
    var rawDir by remember { mutableStateOf(File(Environment.getExternalStorageDirectory().path)) }
    var stack by remember { mutableStateOf(listOf<DocumentFile>()) }
    var rows by remember { mutableStateOf(listOf<FileRow>()) }
    var query by remember { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(FileOrder.NAME_ASC) }
    var sortMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FileRow?>(null) }
    var edit by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var pendingCopy by remember { mutableStateOf<FileRow?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var askAccess by remember { mutableStateOf(false) }

    fun currentLabel(): String = if (mode == "raw") rawDir.path else stack.joinToString(" / ") { it.name ?: "Folder" }
    fun currentParentRaw(): File? = if (mode == "raw") rawDir.parentFile else null
    fun currentParentDoc(): DocumentFile? = if (mode == "saf") stack.lastOrNull()?.parentFile else null

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                .onFailure { context.keepRead(uri) }
            prefs.edit().putString("root", uri.toString()).apply()
            stack = listOfNotNull(DocumentFile.fromTreeUri(context, uri))
        }
    }
    val copy = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val source = pendingCopy
        val dest = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && source != null && dest != null) {
            op.run("Copying to a new document", {
                try {
                    context.contentResolver.openInputStream(Uri.parse(source.uri))!!.use { input ->
                        context.contentResolver.openOutputStream(dest, "w")!!.use { output -> input.copyTo(output) }
                    }
                } catch (e: Exception) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, dest) }
                    throw e
                }
            }) { op.notice("Copy written. Original unchanged."); reload++ }
        }
        pendingCopy = null
    }

    fun readRaw(dir: File) {
        op.run("Reading folder", {
            require(dir.isDirectory && dir.canRead()) { "Folder unavailable: ${dir.path}" }
            dir.listFiles().orEmpty().map { rawRow(it) }.sortedWith(compareBy<FileRow> { !it.folder }.thenBy { it.name.lowercase() })
        }) { rows = it; op.notice("${it.size} items in ${dir.name.ifBlank { dir.path }}") }
    }
    fun readSaf(folder: DocumentFile?) {
        if (folder == null) return
        op.run("Reading folder", {
            require(folder.exists() && folder.canRead()) { "Folder unavailable; choose it again" }
            folder.listFiles().map { docRow(it, context) }.sortedWith(compareBy<FileRow> { !it.folder }.thenBy { it.name.lowercase() })
        }) { rows = it; op.notice("${it.size} items in ${folder.name ?: "folder"}") }
    }

    LaunchedEffect(Unit) {
        prefs.getString("root", null)?.let { saved ->
            runCatching { DocumentFile.fromTreeUri(context, Uri.parse(saved)) }.getOrNull()?.let { stack = listOf(it) }
        }
    }
    LaunchedEffect(mode, rawDir, stack.lastOrNull()?.uri, reload) {
        if (mode == "raw") readRaw(rawDir) else readSaf(stack.lastOrNull())
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Access mode row: all-files first, document picker fallback.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AquaButton(onClick = {
                if (context.hasAllFilesAccess()) { mode = "raw"; rawDir = File(Environment.getExternalStorageDirectory().path); reload++ }
                else askAccess = true
            }, enabled = !op.busy) { Text("All files") }
            AquaSecondaryButton(onClick = { mode = "saf"; picker.launch(null) }, enabled = !op.busy) { Text("Pick a folder") }
            if (mode == "raw") {
                AquaSecondaryButton(onClick = { rawDir = File(Environment.getExternalStorageDirectory().path); reload++ }, enabled = !op.busy) { Text("Home") }
            }
        }
        if (mode == "saf" && stack.isEmpty()) {
            Text("Pick a folder to browse, or use All files. Android still asks before granting access.")
        } else {
            Text(currentLabel(), style = MaterialTheme.typography.labelMedium, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    if (mode == "raw") { currentParentRaw()?.let { rawDir = it; reload++ } }
                    else if (stack.size > 1) stack = stack.dropLast(1)
                }, enabled = !op.busy && (mode == "raw" && currentParentRaw() != null || mode == "saf" && stack.size > 1)) { Text("Up") }
                TextButton(onClick = { reload++ }, enabled = !op.busy) { Text("Refresh") }
                TextButton(onClick = { name = ""; edit = "folder" }, enabled = !op.busy) { Text("New folder") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Filter this folder") }, singleLine = true)
            Box {
                TextButton(onClick = { sortMenu = true }) { Text("Sort: ${order.label} ▾") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    FileOrder.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) }, onClick = { order = choice; sortMenu = false })
                    }
                }
            }
        }
        OperationStatus(op)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val visible = rows.filter { it.name.contains(query, true) }.sortedWith { a, b ->
                compareFileKeys(FileSortKey(a.name, a.folder, a.bytes), FileSortKey(b.name, b.folder, b.bytes), order)
            }
            if (visible.isNotEmpty()) {
                val (fileCount, totalBytes) = summarizeSortKeys(visible.map { FileSortKey(it.name, it.folder, it.bytes) })
                item { Text("$fileCount files · ${android.text.format.Formatter.formatShortFileSize(context, totalBytes)} total in view", style = MaterialTheme.typography.bodySmall) }
            }
            items(visible, key = { it.uri }) { row ->
                ElevatedCard(Modifier.fillMaxWidth().combinedClickable(enabled = !op.busy, onLongClick = { selected = row }, onClick = {
                    if (row.folder) {
                        if (mode == "raw") { rawDir = row.raw ?: rawDir; reload++ }
                        else { rows = emptyList(); stack = stack + (row.doc ?: return@combinedClickable) }
                    } else {
                        val openUri = if (row.raw != null) context.allFilesUri(row.raw) else Uri.parse(row.uri)
                        val type = row.name.substringAfterLast('.', "").let { ext ->
                            when (ext.lowercase()) {
                                "jpg", "jpeg", "png", "gif", "webp" -> "image/*"
                                "mp3", "m4a", "aac", "wav", "ogg", "flac" -> "audio/*"
                                "mp4", "mkv", "webm", "3gp" -> "video/*"
                                "pdf" -> "application/pdf"
                                "zip" -> "application/zip"
                                "txt", "md" -> "text/plain"
                                else -> "*/*"
                            }
                        }
                        context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(openUri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with"), op::notice)
                    }
                })) {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                            Text((if (row.folder) "Folder · " else "") + row.name)
                            if (!row.folder) Text("${android.text.format.Formatter.formatShortFileSize(context, row.bytes)} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(row.modified))}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { selected = row }, Modifier.heightIn(min = 48.dp)) { Text("Actions") }
                    }
                }
            }
        }
    }
    selected?.let { row ->
        if (edit == null) AlertDialog(onDismissRequest = { selected = null }, title = { Text(row.name) }, text = {
            Column {
                if (!row.folder) {
                    TextButton(onClick = { context.share(Uri.parse(row.uri), row.name.substringAfterLast('.', "*/*"), op::notice); selected = null }) { Text("Share") }
                    TextButton(onClick = {
                        pendingCopy = row
                        copy.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(row.name.substringAfterLast('.', "application/octet-stream").let { if (it == "*/*") "application/octet-stream" else it }).putExtra(Intent.EXTRA_TITLE, row.name))
                        selected = null
                    }) { Text("Copy to new document…") }
                    if (row.name.endsWith(".zip", true)) TextButton(onClick = {
                        context.launchSafely(Intent(Intent.ACTION_VIEW).setClassName("dev.wonderdesk.packrat", "dev.wonderdesk.packrat.MainActivity").setDataAndType(if (row.raw != null) context.allFilesUri(row.raw) else Uri.parse(row.uri), "application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), op::notice)
                        selected = null
                    }) { Text("Open ZIP in Packrat") }
                }
                TextButton(onClick = { name = row.name; edit = "rename" }) { Text("Rename…") }
                TextButton(onClick = { edit = "delete" }) { Text("Permanently delete…") }
            }
        }, confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } })
    }
    if (edit != null) AlertDialog(onDismissRequest = { edit = null }, title = { Text(when (edit) { "delete" -> "Permanently delete?"; "rename" -> "Rename"; else -> "New folder" }) }, text = {
        if (edit == "delete") Text("Delete ${selected?.name} and any contents? There is no undo or trash.")
        else OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
    }, confirmButton = {
        TextButton(onClick = {
            val action = edit; val row = selected; val newName = name.trim()
            edit = null; selected = null
            op.run("Updating folder", {
                require(newName.isNotBlank() && newName !in listOf(".", "..") && '/' !in newName && '\\' !in newName) { "Use a non-empty name without slashes" }
                when (action) {
                    "delete" -> {
                        val ok = if (row?.raw != null) row.raw.deleteRecursively() else row?.doc?.delete() ?: false
                        if (!ok) throw IllegalStateException("Deletion failed")
                    }
                    "rename" -> {
                        val r = row!!
                        val exists = if (r.raw != null) File(r.raw.parentFile, newName).exists() else r.doc?.parentFile?.findFile(newName) != null
                        require(!exists || newName == r.name) { "An item already has that name" }
                        val ok = if (r.raw != null) r.raw.renameTo(File(r.raw.parentFile, newName)) else r.doc!!.renameTo(newName)
                        if (!ok) throw IllegalStateException("Rename failed")
                    }
                    else -> {
                        val ok = if (mode == "raw") {
                            File(rawDir, newName).mkdirs()
                        } else {
                            val parent = stack.lastOrNull()
                            require(parent != null && parent.findFile(newName) == null) { "An item already has that name" }
                            parent.createDirectory(newName) != null
                        }
                        if (!ok) throw IllegalStateException("Folder creation failed")
                    }
                }
            }) { op.notice("${if (action == "delete") "Deletion" else "Change"} completed."); reload++ }
        }) { Text(if (edit == "delete") "Delete permanently" else "Apply") }
    }, dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } })
    if (askAccess) AlertDialog(onDismissRequest = { askAccess = false }, title = { Text("Allow all files access") }, text = {
        Text("Wayfinder can manage every file on the phone. Android needs you to allow it once in Settings.")
    }, confirmButton = {
        TextButton(onClick = {
            askAccess = false
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                } else {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }.onFailure { op.notice("Could not open the permission screen.") }
        }) { Text("Open Settings") }
    }, dismissButton = { TextButton(onClick = { askAccess = false }) { Text("Cancel") } })
}
