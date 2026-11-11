package dev.wonderdesk.wayfinder

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

/** Runs [onEnd] on the main thread whenever a background job finishes, while this screen is shown. */
@Composable
fun JobListener(onEnd: (Jobs.Job) -> Unit) {
    val latest = rememberUpdatedState(onEnd)
    DisposableEffect(Unit) {
        val l: (Jobs.Job) -> Unit = { latest.value(it) }
        Jobs.listeners += l
        onDispose { Jobs.listeners -= l }
    }
}

/** A slim card above the tab bar showing the running job, its speed and time left, with Cancel. Never blocks the screen. */
@Composable
fun JobsStrip(modifier: Modifier = Modifier) {
    val p = wonderPalette()
    val active = Jobs.active
    val running = active.firstOrNull { it.state == "running" } ?: active.firstOrNull()
    var expanded by remember { mutableStateOf(false) }
    AnimatedVisibility(running != null, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        val j = running ?: return@AnimatedVisibility
        val pr = j.progress
        Column(Modifier.padding(12.dp).fillMaxWidth().shadow(6.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(p.panel)
            .border(1.dp, p.panelBorder, RoundedCornerShape(14.dp)).clickable { expanded = !expanded }.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(j.label + if (active.size > 1) "  ·  ${active.size - 1} more waiting" else "", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val left = j.remaining?.let { ms -> if (ms > 90_000) " · ${ms / 60_000} min left" else " · ${(ms / 1000).coerceAtLeast(1)} s left" } ?: ""
                    Text(pr?.let { "${formatBytes(it.done)} of ${formatBytes(it.total)}$left · ${it.current}" } ?: "Getting ready…", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { Jobs.cancel(j) }) { Text("Cancel", color = p.danger) }
            }
            AquaProgress(pr?.let { (it.done.toFloat() / it.total.coerceAtLeast(1)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(end = 10.dp, top = 4.dp))
            if (expanded && active.size > 1) active.drop(1).forEach { w ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(w.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    TextButton(onClick = { Jobs.cancel(w) }) { Text("Remove") }
                }
            }
        }
    }
}

/** Asks for the Safe folder password (or sets it up the first time), then runs [onOpen]. */
@Composable
fun SafePasswordDialog(onDismiss: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    val setUp = remember { Safe.isSetUp(context) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        if (!setUp && pw != pw2) { error = "The passwords don't match"; return }
        busy = true; error = null
        scope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { if (setUp) Safe.unlock(context, pw) else { Safe.setUp(context, pw, hint); true } } }
            busy = false
            r.onSuccess { ok -> if (ok) { onOpen() } else error = "That's not the Safe folder password" }.onFailure { error = it.message }
        }
    }
    WonderDialog(onDismissRequest = onDismiss, title = { Text(if (setUp) "Unlock Safe folder" else "Set a Safe folder password") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (setUp) "Enter the Safe folder's own password." else "This password protects only the Safe folder. It's separate from your screen lock and fingerprint, and it can't be recovered, so keep it somewhere safe.",
                style = MaterialTheme.typography.bodyMedium)
            Field(pw, { pw = it; error = null }, "Password", visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password, onDone = { if (setUp) go() })
            if (!setUp) {
                Field(pw2, { pw2 = it; error = null }, "Confirm password", visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password)
                Field(hint, { hint = it }, "Hint (optional)", supporting = "Shown on the unlock screen")
            } else Safe.hint(context).takeIf { it.isNotBlank() }?.let { Text("Hint: $it", style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = p.danger, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy && pw.length >= (if (setUp) 1 else 4), onClick = { go() }) { Text(if (setUp) "Unlock" else "Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

private const val ABOUT_SAFE = "The Safe folder has its own password, separate from your screen lock. Files are encrypted with AES-256 and only Wayfinder can read them, only after you unlock."

@Composable
fun SafeScreen(onTab: (String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var unlocked by remember { mutableStateOf(Safe.unlocked) }
    var items by remember { mutableStateOf<List<Safe.Item>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var menuFor by remember { mutableStateOf<Safe.Item?>(null) }
    var renaming by remember { mutableStateOf<Safe.Item?>(null) }
    var deleting by remember { mutableStateOf<Safe.Item?>(null) }
    var changing by remember { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(!Safe.unlocked) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val resumed = rememberIsResumed()
    LaunchedEffect(resumed) { if (!resumed) Safe.wipeTemp(context) else if (!Safe.unlocked) { unlocked = false; items = null } }
    LaunchedEffect(unlocked, tick) { if (unlocked) items = withContext(Dispatchers.IO) { runCatching { Safe.items(context) }.getOrDefault(emptyList()) } }
    JobListener { j -> tick++; if (j.state == "failed") toastRef.value(j.error ?: "Something went wrong") else if (j.state == "done" && j.label.startsWith("Move out")) toastRef.value("Moved out of the Safe folder") }
    fun open(item: Safe.Item) = Jobs.enqueue(context, "Opening ${item.name}") { _ -> val f = Safe.openTemp(context, item); scope.launch { openFile(context, f) { m -> toastRef.value(m) } } }
    WonderApp(appName = "Wayfinder", about = ABOUT_SAFE, tabs = TABS, selectedTab = "home", onTab = onTab, title = "Safe folder", onBack = { Safe.lock(); onTab("home") },
        actions = { if (unlocked) ToolbarButton(WonderIcons.Lock, "Lock", { Safe.lock(); Safe.wipeTemp(context); unlocked = false; items = null }) },
        menu = if (Safe.isSetUp(context)) listOf(WonderMenuItem("Change password", WonderIcons.Key) { changing = true }, WonderMenuItem("Erase Safe folder", WonderIcons.DeleteSweep, danger = true) { erasing = true }) else emptyList()) {
        toastRef.value = LocalToast.current
        Box(Modifier.fillMaxSize()) {
            when {
                !unlocked -> EmptyState(if (Safe.isSetUp(context)) "Safe folder is locked" else "Your private, encrypted folder",
                    if (Safe.isSetUp(context)) "Enter the Safe folder password to see what's inside." else "Files you move here are encrypted with their own password and hidden from every other app. Choose a password to start.",
                    icon = WonderIcons.Lock, action = { WButton(if (Safe.isSetUp(context)) "Unlock" else "Set a password", { asking = true }, icon = WonderIcons.LockOpen, kind = ButtonKind.Primary) })
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
                items!!.isEmpty() -> EmptyState("Nothing in the Safe folder", "In Browse, select files, open the ⋮ menu and choose “Move to Safe folder”. They're encrypted and hidden from every other app.", icon = WonderIcons.Shield)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 100.dp)) {
                    val list = items!!
                    item { Text("${list.size} item${if (list.size == 1) "" else "s"} · ${formatBytes(list.sumOf { it.size })} · encrypted", Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp), style = MaterialTheme.typography.bodySmall, color = p.secondaryText) }
                    itemsIndexed(list, key = { _, it -> it.id }) { i, it ->
                        val (icon, tint) = kindIcon(kindOf(it.name))
                        Box {
                            ListRow(it.name, Modifier.panelItem(i, list.size), subtitle = formatBytes(it.size) + " · added " + relativeTime(it.added).replaceFirstChar { c -> c.lowercase() }, leading = { IconTile(icon, tint, 40.dp) },
                                onLongClick = { menuFor = it }, onClick = { open(it) },
                                trailing = { IconButton(onClick = { menuFor = it }) { Icon(WonderIcons.MoreVert, "Options", tint = p.secondaryText) } })
                            DropdownMenu(menuFor == it, { menuFor = null }) {
                                DropdownMenuItem({ Text("Move out of Safe folder") }, { val item = it; menuFor = null; Jobs.enqueue(context, "Move out: ${item.name}") { job -> Safe.restore(context, item, job) } }, leadingIcon = { Icon(WonderIcons.LockOpen, null) })
                                DropdownMenuItem({ Text("Rename") }, { renaming = it; menuFor = null }, leadingIcon = { Icon(WonderIcons.DriveFileRenameOutline, null) })
                                DropdownMenuItem({ Text("Share") }, { val item = it; menuFor = null; Jobs.enqueue(context, "Preparing ${item.name}") { _ ->
                                    val f = Safe.openTemp(context, item)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                                    scope.launch { context.launchSafely(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(f.mime()).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) {} }
                                } }, leadingIcon = { Icon(WonderIcons.Share, null) })
                                DropdownMenuItem({ Text("Delete forever", color = p.danger) }, { deleting = it; menuFor = null }, leadingIcon = { Icon(WonderIcons.Delete, null, tint = p.danger) })
                            }
                        }
                    }
                }
            }
            JobsStrip(Modifier.align(Alignment.BottomCenter))
        }
    }
    if (asking) SafePasswordDialog(onDismiss = { asking = false }) { asking = false; unlocked = true; tick++ }
    renaming?.let { it -> InputDialog("Rename", "Name", initial = it.name, onDismiss = { renaming = null }) { n -> val item = it; scope.launch { withContext(Dispatchers.IO) { Safe.rename(context, item, n) }; tick++ } } }
    deleting?.let { it -> ConfirmDialog("Delete “${it.name}” forever?", "Items in the Safe folder don't go to the recycle bin.", "Delete", onConfirm = { val item = it; scope.launch { withContext(Dispatchers.IO) { Safe.delete(context, item) }; tick++ } }, onDismiss = { deleting = null }, danger = true) }
    if (changing) {
        var old by remember { mutableStateOf("") }; var new by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
        WonderDialog(onDismissRequest = { changing = false }, title = { Text("Change password") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(old, { old = it }, "Current password", visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password)
                Field(new, { new = it }, "New password", visual = PasswordVisualTransformation(), keyboard = KeyboardType.Password)
                err?.let { Text(it, color = p.danger) }
            }
        }, confirmButton = { TextButton(enabled = new.length >= 4, onClick = { scope.launch { val ok = withContext(Dispatchers.Default) { Safe.changePassword(context, old, new) }; if (ok) { changing = false; toastRef.value("Password changed") } else err = "The current password is wrong" } }) { Text("Change") } },
            dismissButton = { TextButton(onClick = { changing = false }) { Text("Cancel") } })
    }
    if (erasing) ConfirmDialog("Erase the Safe folder?", "Everything in it is deleted permanently and the password is removed. Use this only if you've forgotten the password.", "Erase",
        onConfirm = { Safe.erase(context); unlocked = false; items = null; tick++ }, onDismiss = { erasing = false }, danger = true)
}
