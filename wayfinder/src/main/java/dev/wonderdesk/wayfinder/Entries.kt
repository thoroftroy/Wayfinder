package dev.wonderdesk.wayfinder

import android.content.Context
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.async

/**
 * A file with everything the list shows already read, so scrolling never touches the disk.
 * Built on a background thread by [Listing].
 */
data class Entry(
    val file: File,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: Kind,
    /** Items inside a folder; -1 for files. */
    val children: Int,
    val hidden: Boolean,
) {
    val path: String get() = file.path
}

object Listing {
    /** Folder counts not read yet (shown as just the date until they arrive). */
    const val COUNT_PENDING = -2

    /** Type, size and date in one read of the disk, instead of three. */
    private fun quickEntry(f: File): Entry {
        val a = runCatching { java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull()
        val dir = a?.isDirectory ?: f.isDirectory
        return Entry(f, f.name, dir, if (dir) 0 else a?.size() ?: f.length(), a?.lastModifiedTime()?.toMillis() ?: f.lastModified(),
            if (dir) Kind.Folder else kindOf(f.name), if (dir) COUNT_PENDING else -1, f.name.startsWith("."))
    }

    fun entry(f: File): Entry = quickEntry(f).let { if (it.isDir) it.copy(children = f.list()?.size ?: 0) else it }

    /** Every item in a folder (hidden ones too) with names, sizes and dates, but folder counts still to come. */
    fun quick(dir: File): List<Entry> = dir.listFiles()?.map { quickEntry(it) }.orEmpty()

    fun list(dir: File, hidden: Boolean): List<Entry> =
        dir.listFiles()?.asSequence()?.filter { hidden || !it.name.startsWith(".") }?.map { entry(it) }?.toList().orEmpty()

    /** Folders seen lately, so going back (or into a folder read ahead) shows its contents at once. */
    private val recent = android.util.LruCache<String, List<Entry>>(80)
    fun cached(dir: File): List<Entry>? = recent.get(dir.path)
    fun keep(dir: File, all: List<Entry>) { recent.put(dir.path, all) }
    fun forget(dir: File) { recent.remove(dir.path) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val readers = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(6)

    /**
     * Fills in how many items each folder holds, several folders at a time. While it is in there it keeps the folder's
     * contents too (for folders of up to 400 items), so tapping into one opens instantly.
     */
    suspend fun fillCounts(list: List<Entry>): List<Entry> = kotlinx.coroutines.coroutineScope {
        // Only the most likely next stops are read ahead (newest folders first), to go easy on the battery.
        val ahead = list.filter { it.isDir }.sortedByDescending { it.modified }.take(24).map { it.path }.toSet()
        list.map { e ->
            if (!e.isDir || e.children >= 0 && recent.get(e.path) != null) kotlinx.coroutines.CompletableDeferred(e)
            else async(readers) {
                val names = e.file.list()
                if (e.path in ahead && names != null && names.size <= 400 && recent.get(e.path) == null) runCatching { keep(e.file, quick(e.file)) }
                e.copy(children = names?.size ?: 0)
            }
        }.map { it.await() }
    }

    fun sort(list: List<Entry>, by: String, desc: Boolean, foldersFirst: Boolean = true): List<Entry> {
        val cmp: Comparator<Entry> = when (by) {
            "Date" -> compareBy { it.modified }
            "Size" -> compareBy { if (it.isDir) it.children.toLong() else it.size }
            "Type" -> compareBy<Entry> { it.name.substringAfterLast('.', "").lowercase() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            else -> compareBy(NaturalOrder) { it.name }
        }
        val ordered = if (desc) cmp.reversed() else cmp
        return if (foldersFirst) list.sortedWith(compareByDescending<Entry> { it.isDir }.then(ordered)) else list.sortedWith(ordered)
    }

    /** "file2" before "file10". */
    object NaturalOrder : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            var i = 0; var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]; val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val c = na.compareTo(nb); if (c != 0) return c
                    i = ei; j = ej
                } else {
                    val c = ca.lowercaseChar().compareTo(cb.lowercaseChar()); if (c != 0) return c
                    i++; j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }

    /**
     * Files of one kind from Android's media index: instant, even on a full phone.
     * Falls back to walking storage for kinds the index doesn't track well (APKs, archives).
     */
    fun category(c: Context, kind: Kind?, recentDays: Int?, limit: Int = 3000): List<Entry> {
        val uri = MediaStore.Files.getContentUri("external")
        val sel = StringBuilder("${MediaStore.Files.FileColumns.MEDIA_TYPE} != ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} OR ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
        val args = mutableListOf<String>()
        when (kind) {
            Kind.Image -> { sel.setLength(0); sel.append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}") }
            Kind.Video -> { sel.setLength(0); sel.append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}") }
            Kind.Audio -> { sel.setLength(0); sel.append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}") }
            Kind.Document -> { sel.setLength(0); sel.append("${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_DOCUMENT} OR ${MediaStore.Files.FileColumns.MIME_TYPE} LIKE 'text/%' OR ${MediaStore.Files.FileColumns.MIME_TYPE} = 'application/pdf'") }
            Kind.Archive, Kind.Apk -> return walkCategory(kind, limit)
            else -> {}
        }
        if (recentDays != null) { sel.insert(0, "("); sel.append(") AND ${MediaStore.Files.FileColumns.DATE_MODIFIED} >= ?"); args += ((System.currentTimeMillis() / 1000) - recentDays * 86_400L).toString() }
        val out = ArrayList<Entry>()
        runCatching {
            c.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED),
                sel.toString(), args.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < limit) {
                    val path = cur.getString(0) ?: continue
                    val name = path.substringAfterLast('/')
                    if (name.startsWith(".") || "/." in path) continue
                    val f = File(path)
                    out += Entry(f, name, false, cur.getLong(1), cur.getLong(2) * 1000, kindOf(name), -1, false)
                }
            }
        }
        return if (out.isEmpty() && kind != null) walkCategory(kind, limit) else out.filter { kind == null || recentDays != null || it.kind == kind || kind == Kind.Document }
    }

    private fun walkCategory(kind: Kind, limit: Int): List<Entry> = Ops.category(Storage.primary, kind, null, limit).map { entry(it) }

    /**
     * Searches names under [root], reporting batches as it goes so the first results show at once.
     * Uses the media index first for speed, then walks folders for everything else.
     */
    @Volatile var searchCancel = false
    fun search(c: Context, root: File, query: String, hidden: Boolean, onBatch: (List<Entry>) -> Unit) {
        searchCancel = false
        val q = query.lowercase()
        val seen = HashSet<String>()
        val batch = ArrayList<Entry>()
        fun flush() { if (batch.isNotEmpty()) { onBatch(batch.toList()); batch.clear() } }
        runCatching {
            c.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DATA),
                "${MediaStore.Files.FileColumns.DATA} LIKE ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?", arrayOf(root.path + "/%", "%$query%"),
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { cur ->
                while (cur.moveToNext() && seen.size < 300) {
                    val p = cur.getString(0) ?: continue
                    if (!hidden && "/." in p) continue
                    val f = File(p); if (!f.exists()) continue
                    if (seen.add(p)) batch += entry(f)
                }
            }
        }
        flush()
        var count = seen.size
        fun walk(d: File, depth: Int) {
            if (searchCancel || count >= 1000 || depth > 24) return
            val kids = d.listFiles() ?: return
            for (f in kids) {
                if (searchCancel) return
                if (!hidden && f.name.startsWith(".")) continue
                if (f.name.lowercase().contains(q) && seen.add(f.path)) { batch += entry(f); count++; if (batch.size >= 25) flush() }
                if (f.isDirectory && !(f.path.endsWith("/Android/data") || f.path.endsWith("/Android/obb"))) walk(f, depth + 1)
            }
        }
        walk(root, 0)
        flush()
    }
}

/** Kind from the name alone (no disk access). */
fun kindOf(name: String): Kind = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "avif", "dng", "raw" -> Kind.Image
    "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "wmv", "flv" -> Kind.Video
    "mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "amr", "mid", "midi" -> Kind.Audio
    "pdf" -> Kind.Pdf
    "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "epub" -> Kind.Document
    "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst" -> Kind.Archive
    "apk", "apks", "xapk" -> Kind.Apk
    "txt", "md", "log", "csv", "ini", "cfg", "conf", "srt", "vtt", "nfo" -> Kind.Text
    "kt", "java", "py", "js", "html", "css", "json", "xml", "yaml", "yml", "sh", "c", "cpp", "h", "rs", "go", "toml", "gradle", "kts" -> Kind.Code
    else -> Kind.Other
}
