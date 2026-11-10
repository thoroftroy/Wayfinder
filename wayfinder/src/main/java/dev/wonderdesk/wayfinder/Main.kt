package dev.wonderdesk.wayfinder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import android.util.LruCache
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : WonderActivity() {
    @Composable override fun Content() = WayfinderApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent }
    companion object { val incoming = mutableStateOf<Intent?>(null) }
}

private const val ABOUT = "Wayfinder is a full file manager for your phone and SD cards: browse every folder, copy, move, rename and delete with a recycle bin, " +
    "search by name, see what fills your storage, compress and extract ZIP files, and open anything in the right app."

fun kindIcon(k: Kind): Pair<ImageVector, Color> = when (k) {
    Kind.Folder -> WonderIcons.Folder to Color(0xFF4A90E2)
    Kind.Image -> WonderIcons.Image to Color(0xFF2E9E44)
    Kind.Video -> WonderIcons.Movie to Color(0xFFE5483C)
    Kind.Audio -> WonderIcons.MusicNote to Color(0xFF8E44AD)
    Kind.Pdf -> WonderIcons.PictureAsPdf to Color(0xFFD63031)
    Kind.Document -> WonderIcons.Description to Color(0xFF1E7FE8)
    Kind.Archive -> WonderIcons.FolderZip to Color(0xFFE67E22)
    Kind.Apk -> WonderIcons.Android to Color(0xFF3DDC84)
    Kind.Text -> WonderIcons.TextSnippet to Color(0xFF7A7F87)
    Kind.Code -> WonderIcons.Code to Color(0xFF34495E)
    Kind.Other -> WonderIcons.InsertDriveFile to Color(0xFF95A5A6)
}

private fun hasAccess() = Environment.isExternalStorageManager()

/** Clipboard of files waiting to be pasted. */
private data class Clip(val files: List<File>, val move: Boolean)

@Composable
fun WayfinderApp() {
    val context = LocalContext.current
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var access by remember { mutableStateOf(hasAccess()) }
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { access = hasAccess(); refresh++ } }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    if (!access) {
        WonderApp(appName = "Wayfinder", about = ABOUT) {
            EmptyState("Let Wayfinder manage your files", "To work like a real file manager, Wayfinder needs Android's “All files access”. It only uses it to show and change files you ask it to. Nothing leaves your phone.",
                action = { WButton("Allow all files access", {
                    context.launchSafely(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))) {
                        context.launchSafely(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) {}
                    }
                }, icon = WonderIcons.FolderOpen, kind = ButtonKind.Primary) })
        }
        return
    }
    var tab by rememberSaveable { mutableStateOf("home") }
    var path by rememberSaveable { mutableStateOf(Storage.primary.path) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var focusName by remember { mutableStateOf<String?>(null) }
    val incoming by MainActivity.incoming
    LaunchedEffect(incoming) {
        val i = incoming ?: (context as ComponentActivity).intent
        MainActivity.incoming.value = null
        val p = i?.getStringExtra("path") ?: i?.data?.takeIf { it.scheme == "file" }?.path
        if (p != null) {
            val f = runCatching { File(p).canonicalFile }.getOrDefault(File(p))
            if (f.isDirectory) path = f.path else if (f.exists()) { path = f.parent ?: path; focusName = f.name }
            tab = "browse"; category = null
        }
    }
    when {
        tab == "browse" || category != null -> Browser(
            dir = File(path), category = category, refresh = refresh, focusName = focusName,
            onNavigate = { path = it.path; category = null; focusName = null },
            onHome = { tab = "home"; category = null },
            onTab = { tab = it; category = null },
        )
        tab == "trash" -> TrashScreen(onTab = { tab = it })
        else -> Home(refresh, onOpen = { path = it.path; tab = "browse"; category = null }, onCategory = { category = it; tab = "browse" }, onTab = { tab = it })
    }
}

private val TABS = listOf(WonderTab("home", "Home", WonderIcons.Home), WonderTab("browse", "Browse", WonderIcons.FolderOpen), WonderTab("trash", "Recycle bin", WonderIcons.Delete))

/* ----------------------------------------------------- home ------------------------------------------------- */

private val CATEGORIES = listOf(
    Triple("Images", Kind.Image, null), Triple("Videos", Kind.Video, null), Triple("Audio", Kind.Audio, null), Triple("Documents", Kind.Document, null),
    Triple("Archives", Kind.Archive, null), Triple("Apps (APK)", Kind.Apk, null), Triple("Recent", null, 7), Triple("Downloads", null, null),
)

@Composable
private fun Home(refresh: Int, onOpen: (File) -> Unit, onCategory: (String) -> Unit, onTab: (String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val volumes = remember(refresh) { Storage.volumes(context) }
    var bookmarks by remember(refresh) { mutableStateOf(Bookmarks.all(context)) }
    WonderApp(appName = "Wayfinder", about = ABOUT, tabs = TABS, selectedTab = "home", onTab = onTab) {
        Page {
            Panel(title = "Storage") {
                volumes.forEachIndexed { i, v ->
                    if (i > 0) PanelDivider()
                    Column(Modifier.fillMaxWidth().clickable { onOpen(v.root) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(if (v.removable) WonderIcons.SdStorage else WonderIcons.PhoneAndroid, if (v.removable) Color(0xFFE67E22) else Color(0xFF1E7FE8))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(v.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${formatBytes(v.total - v.free)} used of ${formatBytes(v.total)} · ${formatBytes(v.free)} free", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                            }
                            Icon(WonderIcons.ChevronRight, null, tint = p.secondaryText)
                        }
                        val used = (v.total - v.free).toFloat() / v.total.coerceAtLeast(1)
                        AquaProgress(used, Modifier.fillMaxWidth(), if (used > .9f) p.danger else p.accent)
                    }
                }
            }
            Panel(title = "Categories") {
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().height(200.dp).padding(8.dp), userScrollEnabled = false) {
                    items(CATEGORIES) { (name, kind, _) ->
                        val (icon, tint) = when (name) { "Recent" -> WonderIcons.History to Color(0xFF16A085); "Downloads" -> WonderIcons.Download to Color(0xFF1E7FE8); else -> kindIcon(kind!!) }
                        Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable {
                            if (name == "Downloads") onOpen(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)) else onCategory(name)
                        }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            IconTile(icon, tint, 44.dp)
                            Spacer(Modifier.height(6.dp))
                            Text(name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Panel(title = "Places") {
                listOf(
                    Triple("Downloads", Environment.DIRECTORY_DOWNLOADS, WonderIcons.Download), Triple("Documents", Environment.DIRECTORY_DOCUMENTS, WonderIcons.Description),
                    Triple("Camera", Environment.DIRECTORY_DCIM, WonderIcons.PhotoCamera), Triple("Pictures", Environment.DIRECTORY_PICTURES, WonderIcons.Image),
                    Triple("Music", Environment.DIRECTORY_MUSIC, WonderIcons.MusicNote), Triple("Movies", Environment.DIRECTORY_MOVIES, WonderIcons.Movie),
                ).forEachIndexed { i, (name, dir, icon) ->
                    if (i > 0) PanelDivider()
                    ListRow(name, icon = icon, tint = Color(0xFF4A90E2), chevron = true, onClick = { onOpen(Environment.getExternalStoragePublicDirectory(dir)) })
                }
            }
            if (bookmarks.isNotEmpty()) Panel(title = "Bookmarks") {
                bookmarks.forEachIndexed { i, b ->
                    if (i > 0) PanelDivider()
                    ListRow(File(b).name.ifBlank { b }, subtitle = b.removePrefix(Storage.primary.path).ifBlank { "/" }, icon = WonderIcons.Bookmark, tint = Color(0xFFF2A516), chevron = true,
                        onClick = { onOpen(File(b)) }, onLongClick = { bookmarks = bookmarks - b; Bookmarks.set(context, bookmarks) })
                }
            }
        }
    }
}

/* ---------------------------------------------------- browser ----------------------------------------------- */

private val thumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

@Composable
private fun thumbnail(f: File): ImageBitmap? {
    val k = f.kind()
    if (k != Kind.Image && k != Kind.Video && k != Kind.Apk) return null
    val key = f.path + f.lastModified()
    var bmp by remember(key) { mutableStateOf(thumbs.get(key)) }
    val context = LocalContext.current
    LaunchedEffect(key) {
        if (bmp != null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                when (k) {
                    Kind.Image -> {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
                        var s = 1; while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 160) s *= 2
                        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) as Bitmap?
                    }
                    Kind.Video -> ThumbnailUtils.createVideoThumbnail(f, Size(256, 256), null) as Bitmap?
                    else -> context.packageManager.getPackageArchiveInfo(f.path, 0)?.applicationInfo?.let { ai ->
                        ai.sourceDir = f.path; ai.publicSourceDir = f.path
                        ai.loadIcon(context.packageManager).toBitmap(128, 128)
                    }
                }
            }.getOrNull()?.also { thumbs.put(key, it) }
        }
    }
    return bmp?.asImageBitmap()
}

@Composable
fun FileIcon(f: File, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val t = thumbnail(f)
    if (t != null) Image(t, null, Modifier.size(size).clip(RoundedCornerShape(size * .18f)), contentScale = ContentScale.Crop)
    else { val (icon, tint) = kindIcon(f.kind()); IconTile(icon, tint, size) }
}

private enum class SortBy(val label: String) { Name("Name"), Date("Date"), Size("Size"), Type("Type") }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Browser(dir: File, category: String?, refresh: Int, focusName: String?, onNavigate: (File) -> Unit, onHome: () -> Unit, onTab: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.prefs("wayfinder") }
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var sort by rememberSaveable { mutableStateOf(runCatching { SortBy.valueOf(prefs.getString("sort", "Name")!!) }.getOrDefault(SortBy.Name)) }
    var desc by rememberSaveable { mutableStateOf(prefs.getBoolean("desc", false)) }
    var grid by rememberSaveable { mutableStateOf(prefs.getBoolean("grid", false)) }
    var hidden by rememberSaveable { mutableStateOf(prefs.getBoolean("hidden", false)) }
    var files by remember { mutableStateOf<List<File>?>(null) }
    var selected by remember(dir.path, category) { mutableStateOf(setOf<String>()) }
    var tick by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<File>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<String, Progress?>?>(null) }
    var props by remember { mutableStateOf<List<File>?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    LaunchedEffect(dir.path, category, sort, desc, hidden, refresh, tick) {
        files = null
        files = withContext(Dispatchers.IO) {
            val list = if (category != null) {
                val c = CATEGORIES.first { it.first == category }
                Ops.category(Storage.primary, c.second, c.third)
            } else dir.listFiles()?.filter { hidden || !it.name.startsWith(".") }.orEmpty()
            val cmp = when (sort) {
                SortBy.Name -> compareBy<File> { it.name.lowercase() }
                SortBy.Date -> compareBy { it.lastModified() }
                SortBy.Size -> compareBy { if (it.isDirectory) -1 else it.length() }
                SortBy.Type -> compareBy<File> { it.extension.lowercase() }.thenBy { it.name.lowercase() }
            }.let { if (desc) it.reversed() else it }
            if (category != null && sort == SortBy.Name) list else list.sortedWith(compareByDescending<File> { it.isDirectory }.then(cmp))
        }
    }
    LaunchedEffect(query, searching) {
        if (!searching || query.length < 2) { results = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(300)
        val found = mutableListOf<File>()
        withContext(Dispatchers.IO) { Ops.search(if (category != null) Storage.primary else dir, query, hidden) { found += it } }
        results = found.sortedByDescending { it.lastModified() }
    }
    val shown = results ?: files.orEmpty()
    val selection = shown.filter { it.path in selected }
    fun runOp(label: String, work: suspend ((Progress) -> Unit) -> Unit) {
        progress = label to null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work { pr -> scope.launch { progress = label to pr } } } }
            progress = null; selected = emptySet(); tick++
            r.onFailure { toastRef.value(if (it is Cancelled) "Cancelled" else it.message ?: "Something went wrong") }
                .onSuccess { toastRef.value("$label done") }
        }
    }
    val inSelect = selected.isNotEmpty()
    if (dir.path != Storage.primary.path || category != null || inSelect || searching) androidx.activity.compose.BackHandler {
        when { inSelect -> selected = emptySet(); searching -> { searching = false; query = "" }; category != null -> onHome(); else -> dir.parentFile?.takeIf { it.canRead() }?.let(onNavigate) ?: onHome() }
    }
    WonderApp(
        appName = "Wayfinder", about = ABOUT, tabs = if (inSelect) emptyList() else TABS, selectedTab = "browse", onTab = onTab,
        title = when { inSelect -> "${selected.size} selected"; category != null -> category; dir.path == Storage.primary.path -> "Internal storage"; else -> dir.name },
        onBack = if (inSelect) ({ selected = emptySet() }) else null,
        actions = {
            if (inSelect) {
                ToolbarButton(WonderIcons.ContentCopy, "Copy", { clip = Clip(selection, false); selected = emptySet(); toastRef.value("Go to a folder and tap Paste") })
                ToolbarButton(WonderIcons.ContentCut, "Move", { clip = Clip(selection, true); selected = emptySet(); toastRef.value("Go to a folder and tap Paste") })
                ToolbarButton(WonderIcons.Delete, "Delete", { dialog = "delete" })
                ToolbarButton(WonderIcons.Share, "Share", {
                    val uris = ArrayList(selection.filter { it.isFile }.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
                    if (uris.isEmpty()) toastRef.value("Pick files to share, not folders") else context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) {}
                })
            } else {
                ToolbarButton(WonderIcons.Search, "Search", { searching = !searching; if (!searching) query = "" })
                if (category == null) ToolbarButton(WonderIcons.CreateNewFolder, "New folder", { dialog = "mkdir" })
                ToolbarButton(if (grid) WonderIcons.ViewList else WonderIcons.GridView, if (grid) "List view" else "Grid view", { grid = !grid; prefs.edit().putBoolean("grid", grid).apply() })
            }
        },
        menu = if (inSelect) listOfNotNull(
            if (selection.size == 1) WonderMenuItem("Rename", WonderIcons.DriveFileRenameOutline) { dialog = "rename" } else null,
            WonderMenuItem("Compress to ZIP", WonderIcons.FolderZip) { dialog = "zip" },
            if (selection.size == 1 && selection[0].extension.equals("zip", true)) WonderMenuItem("Extract here", WonderIcons.Unarchive) {
                val z = selection[0]; val out = Ops.uniqueName(z.parentFile ?: dir, z.nameWithoutExtension)
                runOp("Extract") { rep -> out.mkdirs(); Ops.unzip(z, out, rep) }
            } else null,
            WonderMenuItem("Select all", WonderIcons.SelectAll) { selected = shown.map { it.path }.toSet() },
            WonderMenuItem("Properties", WonderIcons.Info) { props = selection },
            if (selection.size == 1 && selection[0].isDirectory) WonderMenuItem("Bookmark", WonderIcons.Bookmark) { Bookmarks.set(context, Bookmarks.all(context) + selection[0].path); selected = emptySet(); toastRef.value("Bookmarked") } else null,
        ) else listOfNotNull(
            if (category == null) WonderMenuItem("New text file", WonderIcons.NoteAdd) { dialog = "touch" } else null,
            WonderMenuItem("Sort by…", WonderIcons.Sort) { dialog = "sort" },
            WonderMenuItem(if (hidden) "Hide hidden files" else "Show hidden files", WonderIcons.Visibility) { hidden = !hidden; prefs.edit().putBoolean("hidden", hidden).apply() },
            if (category == null) WonderMenuItem("Bookmark this folder", WonderIcons.Bookmark) { Bookmarks.set(context, Bookmarks.all(context) + dir.path); toastRef.value("Bookmarked") } else null,
            if (category == null) WonderMenuItem("Folder properties", WonderIcons.Info) { props = listOf(dir) } else null,
        ),
    ) {
        toastRef.value = LocalToast.current
        Column(Modifier.fillMaxSize()) {
            if (searching) SearchField(query, { query = it }, if (category != null) "Search all storage" else "Search in ${dir.name.ifBlank { "storage" }} and below", Modifier.padding(16.dp, 10.dp, 16.dp, 0.dp))
            if (category == null && !searching) Breadcrumbs(dir, onNavigate)
            Box(Modifier.weight(1f)) {
                when {
                    files == null && results == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                    shown.isEmpty() -> EmptyState(if (results != null) "Nothing found" else "This folder is empty", if (results != null) "No names contain “$query”." else "Use + to make a folder, or paste files here.", icon = WonderIcons.FolderOpen)
                    grid -> LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                        items(shown, key = { it.path }) { f ->
                            val sel = f.path in selected
                            Column(Modifier.padding(4.dp).clip(RoundedCornerShape(10.dp)).background(if (sel) p.selection.copy(alpha = .2f) else Color.Transparent)
                                .combinedClickable(onClick = { if (inSelect) selected = if (sel) selected - f.path else selected + f.path else if (f.isDirectory) onNavigate(f) else openFile(context, f) { toastRef.value(it) } },
                                    onLongClick = { selected = selected + f.path }).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                FileIcon(f, 72.dp)
                                Text(f.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                    else -> {
                        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                        LaunchedEffect(focusName, shown.size) { focusName?.let { n -> shown.indexOfFirst { it.name == n }.takeIf { it >= 0 }?.let { listState.scrollToItem(it); selected = setOf(shown[it].path) } } }
                        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 90.dp)) {
                            itemsIndexed(shown, key = { _, f -> f.path }) { i, f ->
                                val sel = f.path in selected
                                val sub = if (f.isDirectory) (f.list()?.size ?: 0).let { "$it item${if (it == 1) "" else "s"}" } else formatBytes(f.length())
                                ListRow(f.name, Modifier.panelItem(i, shown.size), subtitle = "$sub · " + DateUtils.getRelativeTimeSpanString(f.lastModified()) +
                                        if (results != null || category != null) " · " + (f.parent ?: "").removePrefix(Storage.primary.path).ifBlank { "/" } else "",
                                    leading = { FileIcon(f) }, selected = sel,
                                    trailing = if (inSelect) ({ Checkbox(sel, { on -> selected = if (on) selected + f.path else selected - f.path }) }) else null,
                                    onLongClick = { selected = selected + f.path },
                                    onClick = { if (inSelect) selected = if (sel) selected - f.path else selected + f.path else if (f.isDirectory) onNavigate(f) else openFile(context, f) { toastRef.value(it) } })
                            }
                        }
                    }
                }
                clip?.let { c ->
                    if (!inSelect && category == null) Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.panel)
                        .border(1.dp, p.panelBorder, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.files.size} item${if (c.files.size == 1) "" else "s"} to ${if (c.move) "move" else "copy"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        WButton("Cancel", { clip = null }, kind = ButtonKind.Plain)
                        WButton("Paste here", { val cc = c; clip = null; runOp(if (cc.move) "Move" else "Copy") { rep -> Ops.copy(cc.files, dir, cc.move, rep) } }, kind = ButtonKind.Primary)
                    }
                }
            }
        }
    }
    when (dialog) {
        "mkdir" -> InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { dialog = null }) { n ->
            if (!File(dir, n).mkdirs()) toastRef.value("Couldn't create that folder") else tick++
        }
        "touch" -> InputDialog("New text file", "File name", initial = "New file.txt", confirm = "Create", onDismiss = { dialog = null }) { n ->
            val f = Ops.uniqueName(dir, if ('.' in n) n else "$n.txt"); if (runCatching { f.createNewFile() }.getOrDefault(false)) { tick++; openFile(context, f) {} } else toastRef.value("Couldn't create that file")
        }
        "rename" -> selection.firstOrNull()?.let { f -> InputDialog("Rename", "New name", initial = f.name, confirm = "Rename", onDismiss = { dialog = null }) { n ->
            val t = File(f.parentFile, n); if (t.exists()) toastRef.value("Something with that name already exists") else if (f.renameTo(t)) { selected = emptySet(); tick++ } else toastRef.value("Couldn't rename")
        } }
        "zip" -> InputDialog("Compress to ZIP", "Archive name", initial = (if (selection.size == 1) selection[0].nameWithoutExtension else dir.name.ifBlank { "Archive" }) + ".zip", confirm = "Compress", onDismiss = { dialog = null }) { n ->
            val out = Ops.uniqueName(selection.first().parentFile ?: dir, if (n.endsWith(".zip")) n else "$n.zip"); val src = selection
            runOp("Compress") { rep -> Ops.zip(src, out, rep) }
        }
        "delete" -> ConfirmDialog("Move ${selection.size} item${if (selection.size == 1) "" else "s"} to the recycle bin?", "You can restore them from the Recycle bin for 30 days.", "Delete",
            onConfirm = { val src = selection; runOp("Delete") { Ops.trash(src) } }, onDismiss = { dialog = null }, danger = true)
        "sort" -> WonderDialog(onDismissRequest = { dialog = null }, title = { Text("Sort by") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Segmented(SortBy.entries.map { it.label }, sort.ordinal) { sort = SortBy.entries[it]; prefs.edit().putString("sort", sort.name).apply() }
                Segmented(listOf("Ascending", "Descending"), if (desc) 1 else 0) { desc = it == 1; prefs.edit().putBoolean("desc", desc).apply() }
            }
        }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("Done") } })
    }
    progress?.let { (label, pr) ->
        WonderDialog(onDismissRequest = {}, title = { Text("$label…") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AquaProgress(pr?.let { it.done.toFloat() / it.total }, Modifier.fillMaxWidth())
                Text(pr?.let { "${formatBytes(it.done)} of ${formatBytes(it.total)} · ${it.current}" } ?: "Preparing", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }, confirmButton = { TextButton(onClick = { Ops.cancel = true }) { Text("Cancel") } })
    }
    props?.let { list -> PropertiesDialog(list) { props = null } }
}

private var clip by mutableStateOf<Clip?>(null)

@Composable
private fun Breadcrumbs(dir: File, onNavigate: (File) -> Unit) {
    val p = wonderPalette()
    val root = Storage.primary
    val parts = generateSequence(dir) { it.parentFile }.takeWhile { it.path.length >= root.path.length || !it.path.startsWith(root.path) }.toList().reversed()
        .let { list -> if (dir.path.startsWith(root.path)) list.dropWhile { it.path.length < root.path.length } else list }
    val scroll = rememberScrollState()
    LaunchedEffect(dir.path) { scroll.scrollTo(scroll.maxValue) }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, f ->
            if (i > 0) Icon(WonderIcons.ChevronRight, null, tint = p.secondaryText, modifier = Modifier.size(18.dp))
            val last = i == parts.lastIndex
            Text(if (f.path == root.path) "Internal" else f.name.ifBlank { "/" }, Modifier.clip(RoundedCornerShape(8.dp)).background(if (last) p.accent.copy(alpha = .14f) else Color.Transparent)
                .clickable { onNavigate(f) }.padding(horizontal = 10.dp, vertical = 6.dp), color = if (last) p.accent else p.text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal))
        }
    }
}

@Composable
private fun PropertiesDialog(files: List<File>, onDismiss: () -> Unit) {
    var size by remember { mutableStateOf<Long?>(null) }
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(files) { withContext(Dispatchers.IO) { size = files.sumOf { Ops.totalSize(it) }; count = files.sumOf { if (it.isDirectory) Ops.countItems(it) else 0 } } }
    val p = wonderPalette()
    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (files.size == 1) files[0].name.ifBlank { "Storage" } else "${files.size} items") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (files.size == 1) FileIcon(files[0], 64.dp)
            val line: @Composable (String, String) -> Unit = { k, v -> Row { Text(k, Modifier.width(96.dp), color = p.secondaryText); Text(v) } }
            line("Size", size?.let { formatBytes(it) + if (it >= 1024) " (${"%,d".format(it)} bytes)" else "" } ?: "Measuring…")
            if (count > 0) line("Contains", "$count items")
            if (files.size == 1) {
                val f = files[0]
                line("Type", if (f.isDirectory) "Folder" else f.mime())
                line("Location", f.parent ?: "/")
                line("Modified", DateUtils.formatDateTime(null, f.lastModified(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR))
                line("Access", buildString { if (f.canRead()) append("Read "); if (f.canWrite()) append("Write"); if (f.isHidden) append(" · Hidden") })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

/** Opens a file in the best app: Wonderdesk apps first for their file types, otherwise Android's chooser. */
fun openFile(context: Context, f: File, report: (String) -> Unit) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    val mime = f.mime()
    val preferred = when (f.kind()) {
        Kind.Archive -> "dev.wonderdesk.packrat"
        Kind.Text, Kind.Code -> "dev.wonderdesk.scribble"
        Kind.Pdf -> "dev.wonderdesk.pages"
        Kind.Image -> "dev.wonderdesk.memories"
        Kind.Video -> "dev.wonderdesk.cinema"
        Kind.Audio -> "com.blissplayer.music.debug"
        else -> null
    }
    if (f.kind() == Kind.Apk) {
        context.launchSafely(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK), report)
        return
    }
    val base = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    if (preferred != null) {
        val direct = Intent(base).setPackage(preferred)
        if (context.packageManager.queryIntentActivities(direct, 0).isNotEmpty()) { context.launchSafely(direct, report); return }
    }
    if (f.kind() == Kind.Archive && f.extension.equals("zip", true)) { context.launchSafely(Intent(base).setDataAndType(uri, "application/zip"), report); return }
    context.launchSafely(Intent.createChooser(base, "Open ${f.name} with"), report)
}

/* ------------------------------------------------------ trash ---------------------------------------------- */

@Composable
private fun TrashScreen(onTab: (String) -> Unit) {
    val p = wonderPalette()
    var items by remember { mutableStateOf<List<Ops.Trashed>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(tick) { items = withContext(Dispatchers.IO) { Ops.trashed() } }
    WonderApp(appName = "Wayfinder", about = ABOUT, tabs = TABS, selectedTab = "trash", onTab = onTab, title = "Recycle bin",
        menu = listOf(WonderMenuItem("Empty recycle bin", WonderIcons.DeleteSweep, danger = true) { confirm = true })) {
        val toast = LocalToast.current
        if (items.isEmpty()) EmptyState("Recycle bin is empty", "Files you delete in Wayfinder wait here for 30 days before they're removed for good.", icon = WonderIcons.Delete)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item { Text("${items.size} items · ${formatBytes(items.sumOf { Ops.totalSize(it.file) })}", Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp), color = p.secondaryText, style = MaterialTheme.typography.bodySmall) }
            itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
                ListRow(File(t.from).name, Modifier.panelItem(i, items.size), subtitle = "From ${File(t.from).parent?.removePrefix(Storage.primary.path)?.ifBlank { "/" }} · deleted ${relativeTime(t.at)}",
                    leading = { FileIcon(t.file) }, trailing = {
                        TextButton(onClick = { runCatching { Ops.restore(t) }.onSuccess { toast("Restored"); tick++ }.onFailure { toast(it.message ?: "Couldn't restore") } }) { Text("Restore") }
                    })
            }
        }
    }
    if (confirm) ConfirmDialog("Empty the recycle bin?", "Everything in it will be deleted permanently.", "Empty", onConfirm = { Ops.emptyTrash(); tick++ }, onDismiss = { confirm = false }, danger = true)
}
