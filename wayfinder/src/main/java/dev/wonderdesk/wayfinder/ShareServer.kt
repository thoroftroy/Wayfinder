package dev.wonderdesk.wayfinder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Settings for sharing a folder on the local network. */
data class ShareConfig(val root: String, val ftp: Boolean, val http: Boolean, val ftpPort: Int, val httpPort: Int, val password: String, val readOnly: Boolean, val idleMinutes: Int) {
    companion object {
        fun load(c: Context): ShareConfig = c.getSharedPreferences("share", Context.MODE_PRIVATE).let { p ->
            ShareConfig(p.getString("root", Storage.primary.path)!!, p.getBoolean("ftp", true), p.getBoolean("http", true), p.getInt("ftpPort", 2121), p.getInt("httpPort", 8080),
                p.getString("password", "") ?: "", p.getBoolean("readOnly", false), p.getInt("idle", 30))
        }
    }
    fun save(c: Context) = c.getSharedPreferences("share", Context.MODE_PRIVATE).edit().putString("root", root).putBoolean("ftp", ftp).putBoolean("http", http)
        .putInt("ftpPort", ftpPort).putInt("httpPort", httpPort).putString("password", password).putBoolean("readOnly", readOnly).putInt("idle", idleMinutes).apply()
}

/**
 * Shares a folder over Wi-Fi as an FTP server (for file managers and PCs) and a web page (for any
 * browser). Runs as a foreground service with a Stop button; keeps the CPU awake only while a
 * transfer is happening, and stops by itself after [ShareConfig.idleMinutes] with no visitors.
 */
class ShareService : Service() {
    companion object {
        var running by mutableStateOf(false); private set
        var address by mutableStateOf<String?>(null); private set
        var clients by mutableStateOf(0); private set
        var lastError by mutableStateOf<String?>(null)
        private const val CH = "share"
        fun start(c: Context) { lastError = null; c.startForegroundService(Intent(c, ShareService::class.java)) }
        fun stop(c: Context) { c.startService(Intent(c, ShareService::class.java).setAction("stop")) }

        fun localAddress(c: Context): String? {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
            return lp.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }

    private val pool = Executors.newCachedThreadPool()
    private var ftpSocket: ServerSocket? = null
    private var httpSocket: ServerSocket? = null
    private lateinit var cfg: ShareConfig
    private lateinit var root: File
    private val active = AtomicInteger(0)
    @Volatile private var lastActivity = System.currentTimeMillis()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (running) return START_NOT_STICKY
        cfg = ShareConfig.load(this); root = File(cfg.root).canonicalFile
        val ip = localAddress(this)
        goForeground(ip)
        try {
            if (cfg.ftp) ftpSocket = ServerSocket(cfg.ftpPort).also { s -> pool.execute { acceptLoop(s) { ftpSession(it) } } }
            if (cfg.http) httpSocket = ServerSocket(cfg.httpPort).also { s -> pool.execute { acceptLoop(s) { httpSession(it) } } }
        } catch (e: Exception) { lastError = "Couldn't start: ${e.message}"; stopSelf(); return START_NOT_STICKY }
        running = true; address = ip
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wayfinder:share").apply { setReferenceCounted(false) }
        pool.execute { idleWatch() }
        return START_NOT_STICKY
    }

    private fun goForeground(ip: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Sharing on the network", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, ShareService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java).putExtra("tab", "network"), PendingIntent.FLAG_IMMUTABLE)
        val c = ShareConfig.load(this)
        val text = listOfNotNull(if (c.http) "http://$ip:${c.httpPort}" else null, if (c.ftp) "ftp://$ip:${c.ftpPort}" else null).joinToString("  ·  ")
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Wayfinder is sharing ${folderLabel(c.root)}")
            .setContentText(if (ip == null) "Connect to Wi-Fi to share" else text).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(7, n)
    }

    private fun idleWatch() {
        while (running) {
            try { Thread.sleep(30_000) } catch (_: InterruptedException) { return }
            if (cfg.idleMinutes > 0 && active.get() == 0 && System.currentTimeMillis() - lastActivity > cfg.idleMinutes * 60_000L) { stopSelf(); return }
        }
    }

    override fun onDestroy() {
        running = false; address = null; clients = 0
        runCatching { ftpSocket?.close() }; runCatching { httpSocket?.close() }
        pool.shutdownNow()
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onDestroy()
    }

    private fun acceptLoop(server: ServerSocket, handle: (Socket) -> Unit) {
        while (!server.isClosed) {
            val s = runCatching { server.accept() }.getOrNull() ?: break
            pool.execute {
                begin()
                try { s.soTimeout = 5 * 60_000; handle(s) } catch (_: Exception) {} finally { runCatching { s.close() }; end() }
            }
        }
    }

    private fun begin() { lastActivity = System.currentTimeMillis(); if (active.incrementAndGet() == 1) runCatching { wake?.acquire(60 * 60_000L) }; clients = active.get() }
    private fun end() { lastActivity = System.currentTimeMillis(); if (active.decrementAndGet() == 0) runCatching { if (wake?.isHeld == true) wake?.release() }; clients = active.get() }

    /** Resolves a client path inside the shared root; null if it tries to escape. */
    private fun resolve(cwd: String, arg: String): File? {
        val rel = if (arg.startsWith("/")) arg else "$cwd/$arg"
        val f = File(root, rel).canonicalFile
        return f.takeIf { it.path == root.path || it.path.startsWith(root.path + File.separator) }
    }

    private fun virtual(f: File) = "/" + f.path.removePrefix(root.path).trimStart('/')

    /* ---------------------------------------------------- FTP ---------------------------------------------------- */

    private fun ftpSession(s: Socket) {
        val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
        val out = s.getOutputStream()
        fun reply(code: Int, msg: String) { out.write("$code $msg\r\n".toByteArray(Charsets.UTF_8)); out.flush() }
        reply(220, "Wayfinder FTP ready")
        var authed = cfg.password.isEmpty()
        var cwd = "/"
        var passive: ServerSocket? = null
        var renameFrom: File? = null
        var restart = 0L
        val ro = cfg.readOnly
        fun data(): Socket? = passive?.let { p -> runCatching { p.soTimeout = 20_000; p.accept() }.getOrNull().also { runCatching { p.close() }; passive = null } }
        while (true) {
            val line = input.readLine() ?: break
            lastActivity = System.currentTimeMillis()
            val cmd = line.substringBefore(' ').uppercase()
            val arg = line.substringAfter(' ', "").trim()
            if (!authed && cmd !in setOf("USER", "PASS", "QUIT", "SYST", "FEAT", "OPTS", "AUTH")) { reply(530, "Please log in"); continue }
            when (cmd) {
                "USER" -> if (cfg.password.isEmpty()) reply(230, "Logged in") else reply(331, "Password please")
                "PASS" -> if (cfg.password.isEmpty() || arg == cfg.password) { authed = true; reply(230, "Logged in") } else reply(530, "Wrong password")
                "AUTH" -> reply(502, "TLS not supported")
                "SYST" -> reply(215, "UNIX Type: L8")
                "FEAT" -> { out.write("211-Features\r\n UTF8\r\n MLSD\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n PASV\r\n211 End\r\n".toByteArray()); out.flush() }
                "OPTS" -> reply(200, "OK")
                "NOOP" -> reply(200, "OK")
                "TYPE", "MODE", "STRU" -> reply(200, "OK")
                "PWD", "XPWD" -> reply(257, "\"$cwd\"")
                "CWD", "XCWD" -> { val d = resolve(cwd, arg); if (d != null && d.isDirectory) { cwd = virtual(d); reply(250, "OK") } else reply(550, "No such folder") }
                "CDUP" -> { val d = resolve(cwd, ".."); cwd = d?.let { virtual(it) } ?: "/"; reply(250, "OK") }
                "PASV", "EPSV" -> {
                    runCatching { passive?.close() }
                    val p = ServerSocket(0); passive = p
                    if (cmd == "EPSV") reply(229, "Entering Extended Passive Mode (|||${p.localPort}|)")
                    else {
                        val ip = (s.localAddress as? Inet4Address ?: InetAddress.getByName(address ?: "127.0.0.1")).hostAddress!!.replace('.', ',')
                        reply(227, "Entering Passive Mode ($ip,${p.localPort / 256},${p.localPort % 256})")
                    }
                }
                "LIST", "NLST", "MLSD" -> {
                    val target = resolve(cwd, arg.split(' ').lastOrNull { !it.startsWith("-") } ?: "") ?: resolve(cwd, "")
                    val kids = target?.listFiles()?.sortedBy { it.name.lowercase() } ?: run { reply(550, "No such folder"); null }
                    if (kids != null) {
                        reply(150, "Here comes the listing")
                        data()?.use { d ->
                            val w = d.getOutputStream()
                            val fmt = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                            val mfmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                            kids.forEach { f ->
                                val l = when (cmd) {
                                    "NLST" -> f.name
                                    "MLSD" -> "type=${if (f.isDirectory) "dir" else "file"};size=${f.length()};modify=${mfmt.format(Date(f.lastModified()))}; ${f.name}"
                                    else -> "${if (f.isDirectory) "d" else "-"}rw-r--r-- 1 phone phone ${f.length()} ${fmt.format(Date(f.lastModified()))} ${f.name}"
                                }
                                w.write("$l\r\n".toByteArray(Charsets.UTF_8))
                            }
                        }
                        reply(226, "Done")
                    }
                }
                "SIZE" -> resolve(cwd, arg)?.takeIf { it.isFile }?.let { reply(213, it.length().toString()) } ?: reply(550, "No such file")
                "MDTM" -> resolve(cwd, arg)?.takeIf { it.exists() }?.let { reply(213, SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(it.lastModified()))) } ?: reply(550, "No such file")
                "REST" -> { restart = arg.toLongOrNull() ?: 0; reply(350, "Restarting at $restart") }
                "RETR" -> {
                    val f = resolve(cwd, arg)?.takeIf { it.isFile }
                    if (f == null) reply(550, "No such file") else {
                        reply(150, "Sending ${f.name}")
                        data()?.use { d -> f.inputStream().use { i -> if (restart > 0) i.skip(restart); i.copyTo(d.getOutputStream(), 256 * 1024) } }
                        restart = 0; reply(226, "Done")
                    }
                }
                "STOR", "APPE" -> {
                    val f = resolve(cwd, arg)
                    if (ro) reply(550, "This share is read-only") else if (f == null) reply(553, "Not allowed") else {
                        reply(150, "Ready for ${f.name}")
                        f.parentFile?.mkdirs()
                        data()?.use { d -> java.io.FileOutputStream(f, cmd == "APPE").use { o -> d.getInputStream().copyTo(o, 256 * 1024) } }
                        reply(226, "Saved")
                    }
                }
                "DELE" -> if (ro) reply(550, "Read-only") else resolve(cwd, arg)?.takeIf { it.isFile && it.delete() }?.let { reply(250, "Deleted") } ?: reply(550, "Couldn't delete")
                "MKD", "XMKD" -> if (ro) reply(550, "Read-only") else resolve(cwd, arg)?.takeIf { it.mkdirs() }?.let { reply(257, "\"${virtual(it)}\" created") } ?: reply(550, "Couldn't create")
                "RMD", "XRMD" -> if (ro) reply(550, "Read-only") else resolve(cwd, arg)?.takeIf { it.isDirectory && it != root && it.deleteRecursively() }?.let { reply(250, "Removed") } ?: reply(550, "Couldn't remove")
                "RNFR" -> { renameFrom = resolve(cwd, arg)?.takeIf { it.exists() }; if (renameFrom != null) reply(350, "Ready") else reply(550, "No such file") }
                "RNTO" -> { val t = resolve(cwd, arg); if (!ro && renameFrom != null && t != null && renameFrom!!.renameTo(t)) reply(250, "Renamed") else reply(553, "Couldn't rename"); renameFrom = null }
                "ABOR" -> { runCatching { passive?.close() }; passive = null; reply(226, "Aborted") }
                "QUIT" -> { reply(221, "Bye"); break }
                else -> reply(502, "Not supported")
            }
        }
        runCatching { passive?.close() }
    }

    /* ---------------------------------------------------- HTTP --------------------------------------------------- */

    private fun httpSession(s: Socket) {
        val input = s.getInputStream().buffered()
        val out = s.getOutputStream().buffered()
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) { val h = readLine(input) ?: return; if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
        val (method, rawPath) = requestLine.split(' ').let { (it.getOrNull(0) ?: "GET") to (it.getOrNull(1) ?: "/") }
        if (cfg.password.isNotEmpty()) {
            val auth = headers["authorization"]?.removePrefix("Basic ")?.let { runCatching { String(android.util.Base64.decode(it, 0)) }.getOrNull() }
            if (auth?.substringAfter(':') != cfg.password) {
                out.write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"Wayfinder\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
        }
        val path = URLDecoder.decode(rawPath.substringBefore('?'), "UTF-8")
        val f = resolve("/", path) ?: return status(out, 403, "Not allowed")
        when (method) {
            "GET", "HEAD" -> when {
                f.isDirectory -> html(out, listingPage(f))
                f.isFile -> sendFile(out, f, headers["range"], method == "HEAD")
                else -> status(out, 404, "Not found")
            }
            "PUT" -> {
                if (cfg.readOnly) return status(out, 403, "This share is read-only")
                val len = headers["content-length"]?.toLongOrNull() ?: return status(out, 411, "Length required")
                val target = Ops.uniqueName(f.parentFile ?: root, f.name)
                target.outputStream().use { o -> copyN(input, o, len) }
                status(out, 201, "Saved ${target.name}")
            }
            "DELETE" -> if (!cfg.readOnly && f != root && f.deleteRecursively()) status(out, 200, "Deleted") else status(out, 403, "Couldn't delete")
            else -> status(out, 405, "Not supported")
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) { val c = i.read(); if (c < 0) return if (sb.isEmpty()) null else sb.toString(); if (c == '\n'.code) return sb.toString().trimEnd('\r'); sb.append(c.toChar()); if (sb.length > 8192) return null }
    }

    private fun copyN(i: InputStream, o: OutputStream, n: Long) {
        val b = ByteArray(256 * 1024); var left = n
        while (left > 0) { val r = i.read(b, 0, minOf(b.size.toLong(), left).toInt()); if (r < 0) break; o.write(b, 0, r); left -= r; lastActivity = System.currentTimeMillis() }
    }

    private fun status(out: OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        out.write("HTTP/1.1 $code ${if (code < 300) "OK" else "Error"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
    }

    private fun html(out: OutputStream, page: String) {
        val body = page.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body); out.flush()
    }

    private fun sendFile(out: OutputStream, f: File, range: String?, head: Boolean) {
        val len = f.length()
        var start = 0L; var end = len - 1; var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val (a, b) = range.removePrefix("bytes=").substringBefore(',').split('-').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (a.isNotEmpty()) { start = a.toLong(); if (b.isNotEmpty()) end = b.toLong().coerceAtMost(len - 1) } else if (b.isNotEmpty()) start = (len - b.toLong()).coerceAtLeast(0)
            partial = true
        }
        val count = (end - start + 1).coerceAtLeast(0)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        val sb = StringBuilder(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: $mime\r\nContent-Length: $count\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$len\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (!head) f.inputStream().use { i -> i.skip(start); copyN(i, out, count) }
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun href(f: File) = virtual(f).split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } + if (f.isDirectory) "/" else ""

    private fun listingPage(dir: File): String {
        val kids = dir.listFiles()?.filter { !it.name.startsWith(".") }?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty()
        val crumbs = generateSequence(dir) { if (it == root) null else it.parentFile }.toList().reversed()
        val rows = kids.joinToString("") { f ->
            val icon = if (f.isDirectory) "📁" else when (kindOf(f.name)) { Kind.Image -> "🖼️"; Kind.Video -> "🎬"; Kind.Audio -> "🎵"; Kind.Pdf, Kind.Document -> "📄"; Kind.Archive -> "🗜️"; else -> "📃" }
            "<a class=row href=\"${href(f)}\"${if (f.isFile) " download" else ""}><span class=i>$icon</span><span class=n>${esc(f.name)}</span><span class=s>${if (f.isDirectory) "" else dev.wonderdesk.shared.formatBytes(f.length())}</span></a>"
        }
        val upload = if (cfg.readOnly) "" else """<label class=btn>Upload files<input id=up type=file multiple hidden></label><div id=st></div>
<script>document.getElementById('up').onchange=async e=>{const st=document.getElementById('st');for(const f of e.target.files){st.textContent='Uploading '+f.name+'…';
await fetch(location.pathname+encodeURIComponent(f.name),{method:'PUT',body:f});}st.textContent='Done';location.reload();}</script>"""
        return """<!doctype html><html><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1"><title>${esc(if (dir == root) folderLabel(root.path) else dir.name)} · Wayfinder</title>
<style>:root{color-scheme:light dark}body{margin:0;font:15px -apple-system,system-ui,Segoe UI,Roboto,sans-serif;background:linear-gradient(#eaf5fa,#dcebf1);color:#1b1d21;min-height:100vh}
header{background:linear-gradient(#ededed,#d4d4d4);border-bottom:1px solid #8c8c8c;padding:12px 16px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
h1{font-size:18px;margin:0;flex:1}nav a{color:#1e7fe8;text-decoration:none}main{max-width:820px;margin:16px auto;padding:0 12px}
.panel{background:#fff;border:1px solid #bfc3c8;border-radius:10px;overflow:hidden;box-shadow:0 1px 2px #0002}.row{display:flex;gap:12px;align-items:center;padding:11px 14px;border-top:1px solid #e3e5e8;color:inherit;text-decoration:none}
.row:first-child{border-top:0}.row:hover{background:#3875d71a}.i{width:24px;text-align:center}.n{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.s{color:#6b7078;font-size:13px}
.btn{background:linear-gradient(#6cb3fa,#1e7fe8);color:#fff;border-radius:14px;padding:6px 14px;cursor:pointer;border:1px solid #1663b8}#st{font-size:13px;color:#6b7078}
@media(prefers-color-scheme:dark){body{background:#1c1f23;color:#edeff2}header{background:linear-gradient(#454b54,#2a2e34);border-color:#15171a}.panel{background:#292d33;border-color:#3b4048}.row{border-color:#353a41}}</style></head>
<body><header><h1>${crumbs.joinToString(" › ") { c -> "<nav style=display:inline><a href=\"${href(c)}\">${esc(if (c == root) folderLabel(root.path) else c.name)}</a></nav>" }}</h1>$upload</header>
<main><div class=panel>${rows.ifEmpty { "<div class=row>This folder is empty</div>" }}</div><p style="text-align:center;color:#6b7078;font-size:12px">Shared from Wayfinder</p></main></body></html>"""
    }
}
