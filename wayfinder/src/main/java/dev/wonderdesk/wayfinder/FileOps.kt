package dev.wonderdesk.wayfinder

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class Kind { Folder, Image, Video, Audio, Document, Pdf, Archive, Apk, Text, Code, Other }

fun File.kind(): Kind {
    if (isDirectory) return Kind.Folder
    return when (extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif", "dng", "raw" -> Kind.Image
        "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "wmv", "flv" -> Kind.Video
        "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "amr", "mid", "midi" -> Kind.Audio
        "pdf" -> Kind.Pdf
        "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub" -> Kind.Document
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst" -> Kind.Archive
        "apk", "apks", "xapk" -> Kind.Apk
        "txt", "md", "log", "csv", "ini", "cfg", "conf", "srt", "vtt", "nfo" -> Kind.Text
        "kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "yaml", "yml", "sh", "c", "cpp", "h", "rs", "go", "toml", "gradle", "kts" -> Kind.Code
        else -> Kind.Other
    }
}

fun File.mime(): String = if (isDirectory) "resource/folder" else
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: when (kind()) { Kind.Text, Kind.Code -> "text/plain"; else -> "*/*" }

data class Volume(val name: String, val root: File, val total: Long, val free: Long, val removable: Boolean)

object Storage {
    val primary: File get() = Environment.getExternalStorageDirectory()

    fun volumes(context: Context): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            val st = runCatching { StatFs(dir.path) }.getOrNull() ?: return@mapNotNull null
            Volume(if (v.isPrimary) "Internal storage" else v.getDescription(context), dir, st.totalBytes, st.availableBytes, v.isRemovable)
        }
    }

    fun trashDir(): File = File(primary, ".WayfinderTrash").apply { mkdirs(); File(this, ".nomedia").createNewFile() }
}

/** Progress of a long operation, reported from a background thread. */
data class Progress(val done: Long, val total: Long, val current: String)

class Cancelled : Exception("Cancelled")

object Ops {
    @Volatile var cancel = false

    fun totalSize(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { totalSize(it) } ?: 0 else f.length()
    fun countItems(f: File): Int = if (f.isDirectory) f.listFiles()?.sumOf { 1 + countItems(it) } ?: 0 else 0

    /** "name (2).ext" when [target] already exists. */
    fun uniqueName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) "" else ".$it" }
        var n = 2
        while (f.exists()) { f = File(dir, "$base ($n)$ext"); n++ }
        return f
    }

    fun copy(sources: List<File>, dest: File, move: Boolean, onProgress: (Progress) -> Unit) {
        cancel = false
        val total = sources.sumOf { totalSize(it) }.coerceAtLeast(1)
        var done = 0L
        for (src in sources) {
            if (src.parentFile == dest && move) continue
            require(!dest.canonicalPath.startsWith(src.canonicalPath + "/") && dest.canonicalPath != src.canonicalPath || !src.isDirectory) { "Can't put a folder inside itself" }
            val target = uniqueName(dest, src.name)
            if (move && src.renameTo(target)) { done += totalSize(target); onProgress(Progress(done, total, src.name)); continue }
            fun copyOne(s: File, t: File) {
                if (cancel) throw Cancelled()
                if (s.isDirectory) { t.mkdirs(); s.listFiles()?.forEach { copyOne(it, File(t, it.name)) } }
                else s.inputStream().use { i -> t.outputStream().use { o ->
                    val buf = ByteArray(256 * 1024)
                    while (true) { if (cancel) throw Cancelled(); val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; onProgress(Progress(done, total, s.name)) }
                } }.also { t.setLastModified(s.lastModified()) }
            }
            copyOne(src, target)
            if (move) src.deleteRecursively()
        }
    }

    /** Moves into Wayfinder's recycle bin, remembering where each item came from. */
    fun trash(files: List<File>) {
        val bin = Storage.trashDir()
        val index = trashIndex()
        files.forEach { f ->
            val id = System.currentTimeMillis().toString() + "_" + f.name
            val t = File(bin, id)
            if (f.renameTo(t) || run { copy(listOf(f), bin, move = true) {}; File(bin, f.name).renameTo(t) }) index.put(id, JSONObject().put("from", f.path).put("at", System.currentTimeMillis()))
        }
        saveTrash(index)
    }

    data class Trashed(val id: String, val file: File, val from: String, val at: Long)

    fun trashed(): List<Trashed> {
        val index = trashIndex()
        val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
        val out = mutableListOf<Trashed>()
        Storage.trashDir().listFiles()?.filter { it.name != ".nomedia" && it.name != "index.json" }?.forEach { f ->
            val meta = index.optJSONObject(f.name)
            val at = meta?.optLong("at") ?: f.lastModified()
            if (at < cutoff) { f.deleteRecursively(); index.remove(f.name) } else out += Trashed(f.name, f, meta?.optString("from") ?: File(Storage.primary, f.name.substringAfter('_')).path, at)
        }
        saveTrash(index)
        return out.sortedByDescending { it.at }
    }

    fun restore(t: Trashed) {
        val from = File(t.from)
        from.parentFile?.mkdirs()
        val target = uniqueName(from.parentFile ?: Storage.primary, from.name)
        if (!t.file.renameTo(target)) error("Couldn't restore ${from.name}")
        val index = trashIndex(); index.remove(t.id); saveTrash(index)
    }

    fun emptyTrash() { Storage.trashDir().listFiles()?.filter { it.name != ".nomedia" }?.forEach { it.deleteRecursively() } }

    private fun trashIndex() = runCatching { JSONObject(File(Storage.trashDir(), "index.json").readText()) }.getOrDefault(JSONObject())
    private fun saveTrash(o: JSONObject) { File(Storage.trashDir(), "index.json").writeText(o.toString()) }

    fun zip(sources: List<File>, out: File, onProgress: (Progress) -> Unit) {
        cancel = false
        val total = sources.sumOf { totalSize(it) }.coerceAtLeast(1); var done = 0L
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            fun add(f: File, path: String) {
                if (cancel) throw Cancelled()
                if (f.isDirectory) { zos.putNextEntry(ZipEntry("$path/")); zos.closeEntry(); f.listFiles()?.forEach { add(it, "$path/${it.name}") } }
                else {
                    zos.putNextEntry(ZipEntry(path).apply { time = f.lastModified() })
                    f.inputStream().use { i -> val b = ByteArray(128 * 1024); while (true) { val n = i.read(b); if (n < 0) break; zos.write(b, 0, n); done += n; onProgress(Progress(done, total, f.name)) } }
                    zos.closeEntry()
                }
            }
            sources.forEach { add(it, it.name) }
        }
    }

    /** Extracts safely: no paths outside [dest], at most 20,000 entries. */
    fun unzip(zip: File, dest: File, onProgress: (Progress) -> Unit) {
        cancel = false
        val total = zip.length().coerceAtLeast(1); var read = 0L; var count = 0
        val root = dest.canonicalFile
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                if (cancel) throw Cancelled()
                val e = zis.nextEntry ?: break
                require(++count <= 20_000) { "That archive has too many files" }
                val out = File(root, e.name).canonicalFile
                require(out.path.startsWith(root.path + File.separator) || out == root) { "Blocked an unsafe path in the archive" }
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> val b = ByteArray(128 * 1024); while (true) { val n = zis.read(b); if (n < 0) break; o.write(b, 0, n); read += n; onProgress(Progress(read.coerceAtMost(total), total, e.name)) } }
                    if (e.time > 0) out.setLastModified(e.time)
                }
            }
        }
    }

    /** Recursive name search, newest first, skipping Android/data and hidden folders unless asked. */
    fun search(root: File, query: String, hidden: Boolean, limit: Int = 500, onFound: (File) -> Unit) {
        cancel = false
        val q = query.lowercase()
        var found = 0
        fun walk(d: File, depth: Int) {
            if (cancel || found >= limit || depth > 24) return
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (!hidden && f.name.startsWith(".")) continue
                if (f.name.lowercase().contains(q)) { onFound(f); found++; if (found >= limit) return }
                if (f.isDirectory && !(f.path.endsWith("/Android/data") || f.path.endsWith("/Android/obb"))) walk(f, depth + 1)
            }
        }
        walk(root, 0)
    }

    /** Files of one kind across storage, newest first. */
    fun category(root: File, kind: Kind?, recentDays: Int? = null, limit: Int = 2000): List<File> {
        val out = mutableListOf<File>()
        val since = recentDays?.let { System.currentTimeMillis() - it * 86_400_000L }
        fun walk(d: File, depth: Int) {
            if (out.size >= limit * 2 || depth > 20) return
            d.listFiles()?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isDirectory) { if (!f.path.endsWith("/Android/data") && !f.path.endsWith("/Android/obb")) walk(f, depth + 1) }
                else if ((kind == null || f.kind() == kind || (kind == Kind.Document && f.kind() in setOf(Kind.Pdf, Kind.Text))) && (since == null || f.lastModified() >= since)) out += f
            }
        }
        walk(root, 0)
        return out.sortedByDescending { it.lastModified() }.take(limit)
    }
}

object Bookmarks {
    fun all(c: Context): List<String> = runCatching { JSONArray(c.getSharedPreferences("wayfinder", Context.MODE_PRIVATE).getString("bookmarks", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    fun set(c: Context, list: List<String>) = c.getSharedPreferences("wayfinder", Context.MODE_PRIVATE).edit().putString("bookmarks", JSONArray(list.distinct()).toString()).apply()
}
