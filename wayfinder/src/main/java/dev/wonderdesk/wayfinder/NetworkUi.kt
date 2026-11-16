@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.wonderdesk.wayfinder

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Remote items waiting to be downloaded into a phone folder (set from the network browser). */
data class NetClip(val server: NetServer, val entries: List<NetEntry>)
var netClip by mutableStateOf<NetClip?>(null)

/** A paired Ripple computer Wayfinder may browse. Access comes from a persisted system
 *  folder grant (picked once in the system picker), never from querying Ripple's provider
 *  directly — Android forbids apps from reading another app's DocumentsProvider that way. */
data class RippleComputer(val fp: String, val name: String, val summary: String, val docId: String, val tree: String)

object RippleShare {
    const val AUTHORITY = "dev.wonderdesk.ripple.files"
    const val PKG = "dev.wonderdesk.ripple"

    fun isInstalled(c: Context): Boolean = runCatching {
        c.packageManager.getPackageInfo(PKG, 0); true
    }.getOrDefault(false) || runCatching {
        c.packageManager.resolveContentProvider(AUTHORITY, 0) != null
    }.getOrDefault(false)

    private fun prefs(c: Context) = c.getSharedPreferences("wayfinder", Context.MODE_PRIVATE)

    /** Computers the user connected (persisted folder grants). */
    fun computers(c: Context): List<RippleComputer> = runCatching {
        org.json.JSONArray(prefs(c).getString("ripple_roots", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            RippleComputer(o.getString("fp"), o.optString("name", "Computer"), o.optString("summary", "Ripple"), o.getString("doc"), o.getString("tree"))
        } }
    }.getOrDefault(emptyList())

    private fun save(c: Context, list: List<RippleComputer>) = prefs(c).edit().putString("ripple_roots", org.json.JSONArray(list.map {
        org.json.JSONObject().put("fp", it.fp).put("name", it.name).put("summary", it.summary).put("doc", it.docId).put("tree", it.tree)
    }).toString()).apply()

    /** Remembers a computer picked in the system folder picker, keeping its access grant. */
    fun connect(c: Context, treeUri: android.net.Uri): RippleComputer? = runCatching {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        c.contentResolver.takePersistableUriPermission(treeUri, flags)
        val docId = DocumentsContract.getDocumentId(treeUri)
        val fp = docId.substringBefore('|')
        val name = runCatching {
            c.contentResolver.query(DocumentsContract.buildDocumentUri(AUTHORITY, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { docId.substringAfterLast('/').ifBlank { "Computer" } }
        val rc = RippleComputer(fp, name, "Ripple · stays on your computer", docId, treeUri.toString())
        save(c, (computers(c).filter { it.tree != rc.tree } + rc))
        rc
    }.getOrNull()

    fun forget(c: Context, rc: RippleComputer) {
        runCatching { c.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(rc.tree),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        save(c, computers(c).filter { it.tree != rc.tree })
    }

    /** Opens the computer in Ripple's file browser. */
    fun open(c: Context, fp: String) {
        val explicit = Intent(Intent.ACTION_MAIN)
            .setClassName(PKG, "$PKG.MainActivity")
            .putExtra("computer", fp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (c.packageManager.resolveActivity(explicit, 0) != null) {
            runCatching { c.startActivity(explicit) }.onSuccess { return }
        }
        runCatching {
            c.startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Streams a remote file straight to a viewer — no manual download, the file stays on the PC. */
    fun view(c: Context, tree: android.net.Uri, docId: String, mime: String, name: String, edit: Boolean = false): Boolean = runCatching {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        val type = mime.ifBlank { "*/*" }
        val intent = (if (edit) Intent(Intent.ACTION_EDIT) else Intent(Intent.ACTION_VIEW)).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or if (edit) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        c.startActivity(Intent.createChooser(intent, (if (edit) "Edit " else "Open ") + name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/** One file or folder on a Ripple computer, read live through Ripple's provider. */
data class RippleDoc(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long, val mime: String)

object RippleDocs {
    fun children(c: Context, tree: android.net.Uri, docId: String): List<RippleDoc> {
        val out = mutableListOf<RippleDoc>()
        // Null projection: some provider versions crash when asked for a subset of columns.
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            val iId = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = cur.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val iSize = cur.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val iMod = cur.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cur.moveToNext()) {
                val id = if (iId >= 0) cur.getString(iId) else continue
                if (id.isNullOrBlank()) continue
                val mime = if (iMime >= 0) cur.getString(iMime).orEmpty() else ""
                out += RippleDoc(
                    id = id,
                    name = (if (iName >= 0) cur.getString(iName) else null).orEmpty().ifBlank { id.substringAfterLast('/') },
                    isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    size = if (iSize >= 0) cur.getLong(iSize) else 0L,
                    modified = if (iMod >= 0) cur.getLong(iMod) else 0L,
                    mime = mime,
                )
            }
        } ?: throw java.io.FileNotFoundException("Couldn't reach the computer. Open Ripple to reconnect, then try again.")
        return out.sortedWith(compareByDescending<RippleDoc> { it.isDir }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    fun mkdir(c: Context, tree: android.net.Uri, parentId: String, name: String): String =
        DocumentsContract.createDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
            DocumentsContract.Document.MIME_TYPE_DIR, name)?.let { DocumentsContract.getDocumentId(it) }
            ?: throw java.io.FileNotFoundException("Couldn't create the folder")

    fun rename(c: Context, tree: android.net.Uri, docId: String, name: String): String =
        DocumentsContract.renameDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, docId), name)
            ?.let { DocumentsContract.getDocumentId(it) }
            ?: throw java.io.FileNotFoundException("Couldn't rename")

    fun delete(c: Context, tree: android.net.Uri, doc: RippleDoc) {
        if (!DocumentsContract.deleteDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)))
            throw java.io.FileNotFoundException("Couldn't delete ${doc.name}")
    }

    /** Copies a phone file into the remote folder. Streams both ways; nothing is kept locally. */
    fun upload(c: Context, tree: android.net.Uri, parentId: String, name: String, mime: String, src: android.net.Uri, onBytes: (Long) -> Unit) {
        val created = DocumentsContract.createDocument(c.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), mime, name)
            ?: throw java.io.FileNotFoundException("Couldn't upload $name")
        c.contentResolver.openInputStream(src)?.use { i ->
            c.contentResolver.openOutputStream(created, "w")?.use { o ->
                val b = ByteArray(256 * 1024)
                while (true) { if (Ops.cancel) throw Cancelled(); val n = i.read(b); if (n < 0) break; o.write(b, 0, n); onBytes(n.toLong()) }
            } ?: throw java.io.FileNotFoundException("Couldn't write $name")
        } ?: throw java.io.FileNotFoundException("Couldn't read the file to send")
    }

    /** Optional offline copy: streams the remote file into a phone folder. */
    fun download(c: Context, tree: android.net.Uri, doc: RippleDoc, dest: File, onBytes: (Long) -> Unit) {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, doc.id)
        val t = Ops.uniqueName(dest, doc.name)
        c.contentResolver.openInputStream(uri)?.use { i ->
            t.outputStream().use { o ->
                val b = ByteArray(256 * 1024)
                while (true) { if (Ops.cancel) throw Cancelled(); val n = i.read(b); if (n < 0) break; o.write(b, 0, n); onBytes(n.toLong()) }
            }
        } ?: throw java.io.FileNotFoundException("Couldn't open ${doc.name}")
        if (doc.modified > 0) t.setLastModified(doc.modified)
    }
}

fun downloadFromNet(c: NetClip, dest: File, rep: (Progress) -> Unit) {
    val total = c.entries.sumOf { if (it.isDir) 0L else it.size }.coerceAtLeast(1)
    var done = 0L
    Ops.cancel = false
    SmbSession.open(c.server).use { s -> c.entries.forEach { e -> s.download(e, dest) { n -> done += n; rep(Progress(done, maxOf(total, done), e.name)) } } }
}

private const val NET_ABOUT = "Browse Windows, Mac, Linux and NAS shares (SMB), and share this phone's files over Wi-Fi with FTP and a web page."

@Composable
fun NetworkScreen(onTab: (String) -> Unit, onShowLocal: (File) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var servers by remember { mutableStateOf(NetServers.all(context)) }
    var editing by remember { mutableStateOf<NetServer?>(null) }
    var open by remember { mutableStateOf<NetServer?>(null) }
    var shareSettings by remember { mutableStateOf(false) }
    var cfg by remember { mutableStateOf(ShareConfig.load(context)) }
    val notifPerm = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    var ripple by remember { mutableStateOf(RippleShare.computers(context)) }
    var rippleInstalled by remember { mutableStateOf(false) }
    var openRipple by remember { mutableStateOf<RippleComputer?>(null) }
    var forgetRipple by remember { mutableStateOf<RippleComputer?>(null) }
    val toastRef0 = remember { mutableStateOf<(String) -> Unit>({}) }
    val connectRipple = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (uri.authority != RippleShare.AUTHORITY) { toastRef0.value("Pick one of your Ripple computers"); return@rememberLauncherForActivityResult }
        val rc = RippleShare.connect(context, uri)
        if (rc != null) { ripple = RippleShare.computers(context); openRipple = rc }
        else toastRef0.value("Couldn't keep access to that folder")
    }
    LaunchedEffect(Unit) {
        rippleInstalled = withContext(Dispatchers.IO) { RippleShare.isInstalled(context) }
    }
    open?.let { s -> NetBrowser(s, onClose = { open = null }); return }
    openRipple?.let { r -> RippleBrowser(r, onClose = { openRipple = null }) ; return }
    WonderApp(appName = "Wayfinder", about = NET_ABOUT, tabs = TABS, selectedTab = "network", onTab = onTab, title = "Network",
        actions = { ToolbarButton(WonderIcons.Add, "Add a share", { editing = NetServer(NetServers.newId(), "", "", "", "", "", "") }) }) {
        val toast = LocalToast.current
        toastRef0.value = toast
        LaunchedEffect(ShareService.lastError) { ShareService.lastError?.let { toast(it) } }
        Page {
            Panel(title = "Share this phone", footer = "Anyone on the same Wi-Fi can open the address in a browser or an FTP app. It stops by itself after ${cfg.idleMinutes} minutes with no one connected.") {
                val ip = remember(ShareService.running) { ShareService.localAddress(context) }
                SwitchRow("Share over Wi-Fi", ShareService.running, { on ->
                    if (on) { if (android.os.Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS); ShareService.start(context) } else ShareService.stop(context)
                }, subtitle = if (ShareService.running) "${ShareService.clients} connected" else "Shares “${folderLabel(cfg.root)}”${if (cfg.readOnly) ", read-only" else ""}",
                    icon = WonderIcons.Wifi, tint = Color(0xFF16A085))
                if (ShareService.running) {
                    PanelDivider(0.dp)
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val addr = ShareService.address ?: ip
                        if (addr == null) Text("Connect to Wi-Fi to get an address.", color = p.warning)
                        else SelectionContainer {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (cfg.http) AddressLine("Browser", "http://$addr:${cfg.httpPort}")
                                if (cfg.ftp) AddressLine("FTP", "ftp://$addr:${cfg.ftpPort}")
                            }
                        }
                        if (cfg.password.isNotEmpty()) Text("User: wayfinder · Password: ${cfg.password}", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
                    }
                }
                PanelDivider()
                ListRow("Sharing options", subtitle = "Folder, password, read-only, ports", icon = WonderIcons.Tune, tint = Color(0xFF7A7F87), chevron = true, onClick = { shareSettings = true })
            }
            Panel(title = "Network shares", footer = if (servers.isEmpty()) "Add a Windows, Mac, Linux (Samba) or NAS share. You'll need its address (like 192.168.1.20) and share name." else null) {
                if (servers.isEmpty()) ListRow("Add a share", icon = WonderIcons.Add, tint = p.accent, onClick = { editing = NetServer(NetServers.newId(), "", "", "", "", "", "") })
                servers.forEachIndexed { i, s ->
                    if (i > 0) PanelDivider()
                    ListRow(s.label, subtitle = "smb://${s.host}/${s.share}" + if (s.guest) " · guest" else " · ${s.user}", icon = WonderIcons.Storage, tint = Color(0xFF1E7FE8), chevron = true,
                        onClick = { open = s }, onLongClick = { editing = s })
                }
            }
            if (rippleInstalled || ripple.isNotEmpty()) Panel(title = "Ripple computers",
                footer = "Pick a computer once in the system picker — Wayfinder remembers it and browses its files right here. Files stay on the computer; nothing downloads unless you tap “Save to phone”.") {
                ripple.forEachIndexed { i, r ->
                    if (i > 0) PanelDivider()
                    ListRow(r.name, subtitle = (r.summary.ifBlank { "Ripple" }) + " · tap to browse", icon = WonderIcons.DeveloperBoard, tint = Color(0xFF16A085), chevron = true,
                        onClick = { openRipple = r }, onLongClick = { forgetRipple = r })
                }
                if (ripple.isNotEmpty()) PanelDivider()
                ListRow(if (ripple.isEmpty()) "Connect a Ripple computer" else "Connect another computer",
                    subtitle = if (ripple.isEmpty()) "Pair in Ripple first, then pick the computer here" else "Pick another paired computer",
                    icon = WonderIcons.Add, tint = p.accent, chevron = true,
                    onClick = { connectRipple.launch(null) })
            }
        }
    }
    editing?.let { s -> ServerDialog(s, onDismiss = { editing = null }, onDelete = { NetServers.remove(context, s.id); servers = NetServers.all(context); editing = null }) { saved ->
        NetServers.upsert(context, saved); servers = NetServers.all(context); editing = null
    } }
    forgetRipple?.let { r -> WonderDialog(onDismissRequest = { forgetRipple = null }, title = { Text(r.name) }, text = {
        Column {
            Panel {
                ListRow("Open in Ripple's app", icon = WonderIcons.DeveloperBoard, tint = Color(0xFF16A085), onClick = { forgetRipple = null; RippleShare.open(context, r.fp) }); PanelDivider()
                ListRow("Forget this computer", subtitle = "Removes it from Wayfinder. Files stay on the computer", icon = WonderIcons.Delete, danger = true, onClick = {
                    forgetRipple = null; RippleShare.forget(context, r); ripple = RippleShare.computers(context); if (openRipple?.tree == r.tree) openRipple = null
                })
            }
        }
    }, confirmButton = { TextButton(onClick = { forgetRipple = null }) { Text("Close") } }) }
    if (shareSettings) ShareSettingsDialog(cfg, onDismiss = { shareSettings = false }) { n -> cfg = n; n.save(context); shareSettings = false; if (ShareService.running) { ShareService.stop(context) } }
}

@Composable
private fun AddressLine(label: String, url: String) {
    val p = wonderPalette()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), color = p.secondaryText, style = MaterialTheme.typography.labelLarge)
        Text(url, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), color = p.accent)
    }
}

@Composable
private fun ServerDialog(s: NetServer, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (NetServer) -> Unit) {
    var name by remember { mutableStateOf(s.name) }
    var host by remember { mutableStateOf(s.host) }
    var share by remember { mutableStateOf(s.share) }
    var user by remember { mutableStateOf(s.user) }
    var pass by remember { mutableStateOf(runCatching { NetServers.open(s.secret) }.getOrDefault("")) }
    var domain by remember { mutableStateOf(s.domain) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun build(): NetServer {
        // Accept "\\host\share" or "smb://host/share" pasted into the address box.
        val h = host.trim().removePrefix("smb://").removePrefix("\\\\").replace('\\', '/')
        val (hh, sh) = if ('/' in h) h.substringBefore('/') to h.substringAfter('/').trim('/') else h to share.trim()
        return s.copy(name = name.trim(), host = hh.substringBefore(':'), port = hh.substringAfter(':', "445").toIntOrNull() ?: 445, share = sh.ifBlank { share.trim() }, user = user.trim(), domain = domain.trim(), secret = NetServers.seal(pass))
    }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (s.host.isEmpty()) "Add a network share" else "Edit share") }, text = {
        Column(Modifier.verticalScrollSafe(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(host, { host = it }, "Address", supporting = "e.g. 192.168.1.20 or nas.local", keyboard = KeyboardType.Uri)
            Field(share, { share = it }, "Share name", supporting = "The shared folder, e.g. Public or Media")
            Field(user, { user = it }, "User name", supporting = "Leave empty to connect as a guest")
            Field(pass, { pass = it }, "Password", visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password)
            Field(domain, { domain = it }, "Domain or workgroup (optional)")
            Field(name, { name = it }, "Name (optional)")
            if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WButton("Test", {
                    testing = true; result = null
                    val b = build()
                    scope.launch {
                        result = withContext(Dispatchers.IO) { runCatching { SmbSession.open(b).use { "Connected. ${it.list("").size} items at the top." } }.getOrElse { it.message ?: "Couldn't connect" } }
                        testing = false
                    }
                }, icon = WonderIcons.Sync)
                if (s.host.isNotEmpty()) WButton("Remove", onDelete, kind = ButtonKind.Danger)
            }
        }
    }, confirmButton = { TextButton(enabled = host.isNotBlank(), onClick = { onSave(build()) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier = this.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())

@Composable
private fun ShareSettingsDialog(cfg: ShareConfig, onDismiss: () -> Unit, onSave: (ShareConfig) -> Unit) {
    var c by remember { mutableStateOf(cfg) }
    var folders by remember { mutableStateOf<List<Volume>>(emptyList()) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { folders = withContext(Dispatchers.IO) { Storage.volumes(context) } }
    WonderDialog(onDismissRequest = onDismiss, title = { Text("Sharing options") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Folder to share", style = MaterialTheme.typography.titleSmall)
            val options = folders.map { it.name to it.root.path } + listOf("Downloads" to android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).path,
                "Camera" to android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM).path)
            Column { options.forEach { (n, path) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(c.root == path, { c = c.copy(root = path) }); Text(n) } } }
            Panel {
                SwitchRow("Web page (any browser)", c.http, { c = c.copy(http = it) })
                PanelDivider()
                SwitchRow("FTP (file managers, PCs)", c.ftp, { c = c.copy(ftp = it) })
                PanelDivider()
                SwitchRow("Read-only", c.readOnly, { c = c.copy(readOnly = it) }, subtitle = "Visitors can download but not change anything")
            }
            Field(c.password, { c = c.copy(password = it) }, "Password (optional)", supporting = "User name is “wayfinder”. Empty means no password.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(c.httpPort.toString(), { v -> v.toIntOrNull()?.let { c = c.copy(httpPort = it.coerceIn(1024, 65535)) } }, "Web port", Modifier.weight(1f), keyboard = KeyboardType.Number)
                Field(c.ftpPort.toString(), { v -> v.toIntOrNull()?.let { c = c.copy(ftpPort = it.coerceIn(1024, 65535)) } }, "FTP port", Modifier.weight(1f), keyboard = KeyboardType.Number)
            }
            Text("Stop after no visitors for", style = MaterialTheme.typography.titleSmall)
            Segmented(listOf("10 min", "30 min", "2 h", "Never"), listOf(10, 30, 120, 0).indexOf(c.idleMinutes).coerceAtLeast(0)) { c = c.copy(idleMinutes = listOf(10, 30, 120, 0)[it]) }
        }
    }, confirmButton = { TextButton(onClick = { onSave(c) }) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/* ---------------------------------------------------- SMB browser ---------------------------------------------- */

@Composable
private fun NetBrowser(server: NetServer, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<NetEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember(path) { mutableStateOf(setOf<String>()) }
    var progress by remember { mutableStateOf<Pair<String, Progress?>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    LaunchedEffect(path, tick) {
        error = null; if (tick == 0) items = null
        withContext(Dispatchers.IO) { runCatching { SmbSession.open(server).use { s -> s.list(path) } } }
            .onSuccess { list -> items = list.sortedWith(compareByDescending<NetEntry> { it.isDir }.thenBy(Listing.NaturalOrder) { it.name }) }
            .onFailure { error = it.message ?: "Couldn't open the share" }
    }
    fun runOp(label: String, work: ((Progress) -> Unit) -> Unit) { Jobs.enqueue(context, label) { job -> work { pr -> job.report(pr) } }; selected = emptySet() }
    JobListener { j -> tick++; if (!j.label.startsWith("Opening")) toastRef.value(when (j.state) { "done" -> "${j.label} done"; "cancelled" -> "${j.label} cancelled"; else -> j.error ?: "${j.label} didn't finish" }) }
    val selection = items.orEmpty().filter { it.path in selected }
    BackHandler { when { selected.isNotEmpty() -> selected = emptySet(); path.isNotEmpty() -> path = path.substringBeforeLast('/', ""); else -> onClose() } }
    WonderApp(appName = "Wayfinder", about = NET_ABOUT, title = if (selected.isNotEmpty()) "${selected.size} selected" else path.substringAfterLast('/').ifEmpty { server.label },
        subtitle = "smb://${server.host}/${server.share}/$path".trimEnd('/'), onBack = { if (selected.isNotEmpty()) selected = emptySet() else if (path.isNotEmpty()) path = path.substringBeforeLast('/', "") else onClose() },
        actions = {
            if (selected.isNotEmpty()) {
                ToolbarButton(WonderIcons.Download, "Download", { netClip = NetClip(server, selection); selected = emptySet(); toastRef.value("Open a folder in Browse and tap Download here") })
                ToolbarButton(WonderIcons.Delete, "Delete", { dialog = "delete" })
            } else {
                ToolbarButton(WonderIcons.CreateNewFolder, "New folder", { dialog = "mkdir" })
                ToolbarButton(WonderIcons.Refresh, "Refresh", { tick++ })
            }
        },
        menu = if (selected.size == 1) listOf(WonderMenuItem("Rename", WonderIcons.DriveFileRenameOutline) { dialog = "rename" }) else emptyList()) {
        toastRef.value = LocalToast.current
        Box(Modifier.fillMaxSize()) {
            val current = items; val err = error
            when {
                err != null -> EmptyState("Couldn't open this share", err, icon = WonderIcons.Error, action = { WButton("Try again", { tick++ }, icon = WonderIcons.Refresh) })
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                current.isEmpty() -> EmptyState("This folder is empty", "Copy files in Browse, then come here and tap Upload here.", icon = WonderIcons.FolderOpen)
                else -> LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                    val list = current
                    itemsIndexed(list, key = { _, e -> e.path }) { i, e ->
                        val sel = e.path in selected
                        val (icon, tint) = kindIcon(e.kind)
                        ListRow(e.name, Modifier.panelItem(i, list.size), subtitle = (if (e.isDir) "Folder" else formatBytes(e.size)) + " · " + dev.wonderdesk.shared.relativeTime(e.modified),
                            leading = { IconTile(icon, tint, 40.dp) }, selected = sel,
                            trailing = if (selected.isNotEmpty()) ({ Checkbox(sel, { on -> selected = if (on) selected + e.path else selected - e.path }) }) else null,
                            onLongClick = { selected = selected + e.path },
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (sel) selected - e.path else selected + e.path
                                else if (e.isDir) path = e.path
                                else runOp("Opening ${e.name}") { rep ->
                                    val d = File(context.cacheDir, "net").apply { deleteRecursively(); mkdirs() }
                                    var done = 0L
                                    SmbSession.open(server).use { s -> s.download(e, d) { n -> done += n; rep(Progress(done, e.size.coerceAtLeast(done), e.name)) } }
                                    val f = File(d, e.name)
                                    scope.launch { openFile(context, f) { toastRef.value(it) } }
                                }
                            })
                    }
                }
            }
            JobsStrip(Modifier.align(Alignment.BottomCenter).padding(bottom = if (clip != null) 76.dp else 0.dp))
            clip?.let { c ->
                if (selected.isEmpty()) Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(p.panel)
                    .border(1.dp, p.panelBorder, RoundedCornerShape(14.dp)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.files.size} item${if (c.files.size == 1) "" else "s"} from your phone", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    WButton("Cancel", { clip = null }, kind = ButtonKind.Plain)
                    WButton("Upload here", {
                        val cc = c; clip = null
                        runOp("Upload") { rep ->
                            val total = cc.files.sumOf { Ops.totalSize(it) }.coerceAtLeast(1); var done = 0L; Ops.cancel = false
                            SmbSession.open(server).use { s -> cc.files.forEach { f -> s.upload(f, path) { n -> done += n; rep(Progress(done, total, f.name)) } } }
                            if (cc.move) cc.files.forEach { it.deleteRecursively() }
                        }
                    }, kind = ButtonKind.Primary)
                }
            }
        }
    }
    when (dialog) {
        "mkdir" -> InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { dialog = null }) { n -> runOp("New folder") { SmbSession.open(server).use { s -> s.mkdir((path + "/" + n).trim('/')) } } }
        "rename" -> selection.firstOrNull()?.let { e -> InputDialog("Rename", "New name", initial = e.name, confirm = "Rename", onDismiss = { dialog = null }) { n -> runOp("Rename") { SmbSession.open(server).use { s -> s.rename(e, n) } } } }
        "delete" -> ConfirmDialog("Delete ${selection.size} item${if (selection.size == 1) "" else "s"} from ${server.label}?", "Network shares have no recycle bin, so this can't be undone.", "Delete",
            onConfirm = { val src = selection; runOp("Delete") { SmbSession.open(server).use { s -> src.forEach { s.delete(it) } } } }, onDismiss = { dialog = null }, danger = true)
    }
}

/* -------------------------------------------------- Ripple browser --------------------------------------------- */
/* Live view of a paired computer: files stay on the PC and stream straight into viewers.
   Nothing is downloaded unless you explicitly choose "Save to phone". */

@Composable
private fun RippleBrowser(computer: RippleComputer, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var stack by remember { mutableStateOf(listOf(computer.docId)) }
    val cur = stack.last()
    var docs by remember { mutableStateOf<List<RippleDoc>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember(cur) { mutableStateOf(setOf<String>()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var menuDoc by remember { mutableStateOf<RippleDoc?>(null) }
    var shownFor by remember { mutableStateOf<String?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val tree = remember(computer.tree) { android.net.Uri.parse(computer.tree) }
    LaunchedEffect(cur, tick) {
        error = null
        if (cur != shownFor) docs = null
        withContext(Dispatchers.IO) { runCatching { RippleDocs.children(context, tree, cur) } }
            .onSuccess { docs = it; shownFor = cur }
            .onFailure {
                if (docs == null || cur != shownFor) {
                    docs = null
                    error = it.message ?: "Couldn't reach ${computer.name}. Open Ripple to reconnect, then try again."
                } else toastRef.value(it.message ?: "Couldn't refresh")
            }
    }
    fun runOp(label: String, work: ((Progress) -> Unit) -> Unit) { Jobs.enqueue(context, label) { job -> work { pr -> job.report(pr) } }; selected = emptySet() }
    JobListener { j -> tick++; if (!j.label.startsWith("Opening")) toastRef.value(when (j.state) { "done" -> "${j.label} done"; "cancelled" -> "${j.label} cancelled"; else -> j.error ?: "${j.label} didn't finish" }) }
    val selection = docs.orEmpty().filter { it.id in selected }
    val upload = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        runOp("Upload") { rep ->
            var done = 0L; Ops.cancel = false
            val total = uris.sumOf { u ->
                runCatching {
                    context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                        if (it.moveToFirst()) it.getLong(0).coerceAtLeast(0L) else 0L
                    } ?: 0L
                }.getOrDefault(0L)
            }.coerceAtLeast(1L)
            uris.forEach { u ->
                val name = runCatching {
                    context.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: "Upload"
                val mime = context.contentResolver.getType(u) ?: "*/*"
                RippleDocs.upload(context, tree, cur, name, mime, u) { n -> done += n; rep(Progress(done, total, name)) }
            }
        }
    }
    BackHandler { when { selected.isNotEmpty() -> selected = emptySet(); stack.size > 1 -> stack = stack.dropLast(1); else -> onClose() } }
    WonderApp(appName = "Wayfinder", about = NET_ABOUT, title = if (selected.isNotEmpty()) "${selected.size} selected" else docs?.let { cur.substringAfterLast('/') }?.takeIf { it.isNotBlank() } ?: computer.name,
        subtitle = "${computer.name} · stays on your computer", onBack = { if (selected.isNotEmpty()) selected = emptySet() else if (stack.size > 1) stack = stack.dropLast(1) else onClose() },
        actions = {
            if (selected.isNotEmpty()) {
                ToolbarButton(WonderIcons.Download, "Save to phone", {
                    val sel = selection.filter { !it.isDir }
                    if (sel.isEmpty()) toastRef.value("Pick files to save, not folders")
                    else { val dst = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS); runOp("Save") { rep ->
                        val total = sel.sumOf { it.size.coerceAtLeast(1) }; var done = 0L; Ops.cancel = false
                        sel.forEach { d -> RippleDocs.download(context, tree, d, dst) { n -> done += n; rep(Progress(done, total, d.name)) } } } }
                })
                ToolbarButton(WonderIcons.Delete, "Delete", { dialog = "delete" })
            } else {
                ToolbarButton(WonderIcons.UploadFile, "Send files here", { upload.launch(arrayOf("*/*")) })
                ToolbarButton(WonderIcons.CreateNewFolder, "New folder", { dialog = "mkdir" })
                ToolbarButton(WonderIcons.Refresh, "Refresh", { tick++; docs = null })
            }
        },
        menu = listOfNotNull(
            if (selected.size == 1) WonderMenuItem("Rename", WonderIcons.DriveFileRenameOutline) { dialog = "rename" } else null,
            WonderMenuItem("Open in Ripple app", WonderIcons.DeveloperBoard) { RippleShare.open(context, computer.fp) },
        )) {
        toastRef.value = LocalToast.current
        Box(Modifier.fillMaxSize()) {
            val current = docs; val err = error
            when {
                err != null && current == null -> EmptyState("Couldn't reach ${computer.name}", err, icon = WonderIcons.Error, action = { WButton("Try again", { tick++; docs = null }, icon = WonderIcons.Refresh) })
                current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                current.isEmpty() -> EmptyState("This folder is empty", "Send files here with the upload button — they land on ${computer.name}.", icon = WonderIcons.FolderOpen)
                else -> LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                    itemsIndexed(current, key = { _, e -> e.id }) { i, e ->
                        val sel = e.id in selected
                        val kind = if (e.isDir) Kind.Folder else kindOf(e.name)
                        val (icon, tint) = kindIcon(kind)
                        ListRow(e.name, Modifier.panelItem(i, current.size), subtitle = (if (e.isDir) "Folder" else formatBytes(e.size)) + " · " + dev.wonderdesk.shared.relativeTime(e.modified),
                            leading = { IconTile(icon, tint, 40.dp) }, selected = sel,
                            trailing = if (selected.isNotEmpty()) ({ Checkbox(sel, { on -> selected = if (on) selected + e.id else selected - e.id }) }) else null,
                            onLongClick = {
                                if (selected.isNotEmpty()) selected = selected + e.id
                                else if (e.isDir) selected = selected + e.id
                                else menuDoc = e
                            },
                            onClick = {
                                if (selected.isNotEmpty()) selected = if (sel) selected - e.id else selected + e.id
                                else if (e.isDir) { stack = stack + e.id; selected = emptySet() }
                                else if (!RippleShare.view(context, tree, e.id, e.mime.ifBlank { "*/*" }, e.name)) toastRef.value("No app here can open ${e.name}")
                            })
                    }
                }
            }
            JobsStrip(Modifier.align(Alignment.BottomCenter))
        }
    }
    when (dialog) {
        "mkdir" -> InputDialog("New folder", "Folder name", confirm = "Create", onDismiss = { dialog = null }) { n ->
            runOp("New folder") { RippleDocs.mkdir(context, tree, cur, n) }; tick++
        }
        "rename" -> selection.firstOrNull()?.let { e -> InputDialog("Rename", "New name", initial = e.name, confirm = "Rename", onDismiss = { dialog = null }) { n ->
            runOp("Rename") { RippleDocs.rename(context, tree, e.id, n) }; tick++
        } }
        "delete" -> ConfirmDialog("Delete ${selection.size} item${if (selection.size == 1) "" else "s"} from ${computer.name}?", "Files stay on the computer have no recycle bin, so this can't be undone.", "Delete",
            onConfirm = { val src = selection; runOp("Delete") { src.forEach { RippleDocs.delete(context, tree, it) } }; tick++ }, onDismiss = { dialog = null }, danger = true)
    }
    menuDoc?.let { e ->
        WonderDialog(onDismissRequest = { menuDoc = null }, title = { Text(e.name) }, text = {
            Column {
                Panel {
                    ListRow("Open", icon = WonderIcons.OpenInNew, onClick = { menuDoc = null; if (!RippleShare.view(context, tree, e.id, e.mime.ifBlank { "*/*" }, e.name)) toastRef.value("No app here can open ${e.name}") }); PanelDivider()
                    if (!e.isDir) {
                        ListRow("Edit", subtitle = "Saves back to ${computer.name}", icon = WonderIcons.Edit, onClick = { menuDoc = null; if (!RippleShare.view(context, tree, e.id, e.mime.ifBlank { "*/*" }, e.name, edit = true)) toastRef.value("No app here can edit ${e.name}") }); PanelDivider()
                        ListRow("Save to phone", subtitle = "Keeps a copy in Downloads", icon = WonderIcons.Download, onClick = {
                            menuDoc = null; runOp("Save") { rep ->
                                Ops.cancel = false
                                RippleDocs.download(context, tree, e, android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)) { n -> rep(Progress(n, e.size.coerceAtLeast(n), e.name)) } }
                        }); PanelDivider()
                    }
                    ListRow("Copy link", subtitle = "The path on ${computer.name}", icon = WonderIcons.ContentCopy, onClick = { menuDoc = null; context.copyText("Path", e.id.substringAfter('|')); toastRef.value("Path copied") })
                }
            }
        }, confirmButton = { TextButton(onClick = { menuDoc = null }) { Text("Close") } })
    }
}

fun folderLabel(path: String): String = when {
    path == Storage.primary.path -> "Internal storage"
    path.startsWith("/storage/") && path.count { it == '/' } == 2 -> "SD card or USB drive"
    else -> File(path).name.ifBlank { "Storage" }
}
