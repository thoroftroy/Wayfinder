package dev.wonderdesk.wayfinder

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Wayfinder's Safe folder, locked with its own password (not the screen lock or fingerprint).
 *
 * The password (PBKDF2-HMAC-SHA256, 310,000 rounds, random salt) unlocks a random 256-bit file key.
 * Files are encrypted with AES-256-GCM in 1 MB chunks, each with its own nonce and authentication tag,
 * so huge files stream with real progress, cancel straight away, and any tampering is detected.
 * Everything lives in Wayfinder's private storage, where no other app can see it.
 */
object Safe {
    private const val ITER = 310_000
    private const val CHUNK = 1 shl 20
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), '2'.code.toByte())
    data class Item(val id: String, val name: String, val size: Long, val added: Long, val from: String)

    @Volatile private var key: SecretKeySpec? = null
    @Volatile var lastUse = 0L
    val unlocked get() = key != null && System.currentTimeMillis() - lastUse < 5 * 60_000
    fun lock() { key = null }
    private fun k(): SecretKeySpec { val kk = key ?: throw IllegalStateException("The Safe folder is locked"); lastUse = System.currentTimeMillis(); return kk }

    fun dir(c: Context) = File(c.filesDir, "safe").apply { mkdirs() }
    private fun keyFile(c: Context) = File(dir(c), "key.json")
    fun isSetUp(c: Context) = keyFile(c).isFile
    fun hint(c: Context) = runCatching { JSONObject(keyFile(c).readText()).optString("hint") }.getOrDefault("")

    private fun derive(password: String, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iter, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun random(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    /** First use: choose the password. Items from the old (screen-lock) Safe folder are re-encrypted. */
    fun setUp(c: Context, password: String, hint: String) {
        require(password.length >= 4) { "Use at least 4 characters" }
        val salt = random(16); val kek = derive(password, salt, ITER)
        val fileKey = random(32); val iv = random(12)
        val wrapped = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(128, iv)) }.doFinal(fileKey)
        keyFile(c).writeText(JSONObject().put("v", 2).put("salt", b64(salt)).put("iter", ITER).put("iv", b64(iv)).put("key", b64(wrapped)).put("hint", hint.take(80)).toString())
        key = SecretKeySpec(fileKey, "AES"); lastUse = System.currentTimeMillis()
        LegacySafe.migrate(c)
        if (!File(dir(c), "index.v2").isFile) saveIndex(c, emptyList())
    }

    /** Returns false for a wrong password. Slow on purpose (about half a second). */
    fun unlock(c: Context, password: String): Boolean {
        val o = JSONObject(keyFile(c).readText())
        val kek = derive(password, unb64(o.getString("salt")), o.optInt("iter", ITER))
        val fileKey = try { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, unb64(o.getString("iv")))) }.doFinal(unb64(o.getString("key"))) }
            catch (e: AEADBadTagException) { return false }
        key = SecretKeySpec(fileKey, "AES"); lastUse = System.currentTimeMillis()
        return true
    }

    fun changePassword(c: Context, old: String, new: String): Boolean {
        if (!unlock(c, old)) return false
        val fileKey = k().encoded
        val salt = random(16); val kek = derive(new, salt, ITER); val iv = random(12)
        val wrapped = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(128, iv)) }.doFinal(fileKey)
        val o = JSONObject(keyFile(c).readText()).put("salt", b64(salt)).put("iter", ITER).put("iv", b64(iv)).put("key", b64(wrapped))
        keyFile(c).writeText(o.toString())
        return true
    }

    /** Forgotten password: there's no back door, so this deletes everything in the Safe folder. */
    fun erase(c: Context) { lock(); dir(c).deleteRecursively() }

    /* ---------------------------------------------- chunked streams ---------------------------------------------- */

    private fun nonce(prefix: ByteArray, counter: Int) = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()
    private fun aad(counter: Int, last: Boolean) = ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array()

    /** Encrypts [input] to [out]; [onBytes] gets plain bytes done. Cancels between chunks. */
    private fun encrypt(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit = {}, check: () -> Unit = {}) {
        val kk = k(); val prefix = random(8)
        out.write(MAGIC); out.write(ByteBuffer.allocate(4).putInt(CHUNK).array()); out.write(prefix)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val buf = ByteArray(CHUNK); var counter = 0
        var have = readFully(input, buf)
        while (true) {
            check()
            val next = ByteArray(1); val more = input.read(next)
            val last = more < 0
            cipher.init(Cipher.ENCRYPT_MODE, kk, GCMParameterSpec(128, nonce(prefix, counter)))
            cipher.updateAAD(aad(counter, last))
            out.write(cipher.doFinal(buf, 0, have))
            onBytes(have.toLong())
            if (last) break
            counter++
            buf[0] = next[0]
            have = 1 + readFully(input, buf, 1)
        }
    }

    private fun readFully(i: InputStream, b: ByteArray, off: Int = 0): Int {
        var n = off
        while (n < b.size) { val r = i.read(b, n, b.size - n); if (r < 0) break; n += r }
        return n - off
    }

    /** A stream that decrypts and checks each chunk as it's read. */
    private class DecryptStream(private val input: InputStream, private val key: SecretKeySpec) : InputStream() {
        private var pendingByte = -1
        private val prefix = ByteArray(8); private var chunk = CHUNK
        private var counter = 0; private var buf = ByteArray(0); private var pos = 0; private var done = false
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        init {
            val m = ByteArray(4); if (input.readNBytesCompat(m) != 4 || !m.contentEquals(MAGIC)) throw java.io.IOException("Not a Safe folder file")
            val cs = ByteArray(4); input.readNBytesCompat(cs); chunk = ByteBuffer.wrap(cs).int
            input.readNBytesCompat(prefix)
        }
        private fun fill(): Boolean {
            if (done) return false
            val enc = ByteArray(chunk + 16)
            val n = input.readNBytesCompat(enc)
            if (n <= 0) throw EOFException("The file was cut short")
            val peek = input.read()
            val last = peek < 0
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ByteBuffer.allocate(12).put(prefix).putInt(counter).array()))
            cipher.updateAAD(ByteBuffer.allocate(5).putInt(counter).put(if (last) 1 else 0).array())
            buf = cipher.doFinal(enc, 0, n); pos = 0; counter++
            if (last) done = true else pendingByte = peek
            return true
        }
        private fun InputStream.readNBytesCompat(b: ByteArray): Int {
            var n = 0
            if (pendingByte >= 0 && b.isNotEmpty()) { b[0] = pendingByte.toByte(); pendingByte = -1; n = 1 }
            while (n < b.size) { val r = read(b, n, b.size - n); if (r < 0) break; n += r }
            return n
        }
        override fun read(): Int { if (pos >= buf.size && !fill()) return -1; return buf[pos++].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= buf.size && !fill()) return -1
            val n = minOf(len, buf.size - pos); System.arraycopy(buf, pos, b, off, n); pos += n; return n
        }
        override fun close() = input.close()
    }

    private fun decryptStream(f: File): InputStream = DecryptStream(f.inputStream().buffered(CHUNK + 64), k())

    /* --------------------------------------------------- index ---------------------------------------------------- */

    fun items(c: Context): List<Item> {
        val f = File(dir(c), "index.v2"); if (!f.isFile) return emptyList()
        val o = JSONObject(decryptStream(f).use { it.readBytes().decodeToString() })
        return o.keys().asSequence().map { id -> o.getJSONObject(id).let { Item(id, it.getString("n"), it.optLong("s"), it.optLong("a"), it.optString("f")) } }.toList().sortedByDescending { it.added }
    }

    @Synchronized private fun saveIndex(c: Context, list: List<Item>) {
        val o = JSONObject(); list.forEach { o.put(it.id, JSONObject().put("n", it.name).put("s", it.size).put("a", it.added).put("f", it.from)) }
        val tmp = File(dir(c), "index.tmp")
        tmp.outputStream().use { out -> encrypt(o.toString().byteInputStream(), out) }
        tmp.renameTo(File(dir(c), "index.v2"))
    }

    private fun newId(): String = random(12).joinToString("") { "%02x".format(it) }

    /** Encrypts [files] (and folders) into the safe, then deletes the originals. Reports byte progress. */
    fun add(c: Context, files: List<File>, job: Jobs.Job) {
        val all = files.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it) }
        val total = all.sumOf { it.length() }.coerceAtLeast(1); var done = 0L
        val list = items(c).toMutableList()
        for (f in all) {
            job.check()
            val id = newId(); val out = File(dir(c), "$id.bin")
            try {
                f.inputStream().buffered(CHUNK).use { i -> out.outputStream().buffered(CHUNK + 64).use { o ->
                    encrypt(i, o, onBytes = { n -> done += n; job.report(Progress(done, total, f.name)) }, check = { job.check() })
                } }
            } catch (e: Throwable) { out.delete(); throw e }
            list += Item(id, f.name, f.length(), System.currentTimeMillis(), f.parent ?: "")
            saveIndex(c, list)
            f.delete()
        }
        files.filter { it.isDirectory }.forEach { it.deleteRecursively() }
    }

    /** Decrypts back to where it came from (or [dest]) and removes it from the safe. */
    fun restore(c: Context, item: Item, job: Jobs.Job? = null, dest: File? = null): File {
        val parent = dest ?: File(item.from).takeIf { it.isDirectory || it.mkdirs() } ?: Storage.primary
        val target = Ops.uniqueName(parent, item.name)
        copyOut(item, target, c, job)
        File(dir(c), "${item.id}.bin").delete()
        saveIndex(c, items(c).filter { it.id != item.id })
        return target
    }

    private fun copyOut(item: Item, target: File, c: Context, job: Jobs.Job?) {
        var done = 0L
        try {
            decryptStream(File(dir(c), "${item.id}.bin")).use { i -> target.outputStream().buffered(CHUNK).use { o ->
                val b = ByteArray(256 * 1024)
                while (true) { job?.check(); val n = i.read(b); if (n < 0) break; o.write(b, 0, n); done += n; job?.report(Progress(done, item.size.coerceAtLeast(done), item.name)) }
            } }
        } catch (e: Throwable) { target.delete(); throw e }
    }

    /** A temporary decrypted copy for viewing; wiped when you leave the Safe folder. */
    fun openTemp(c: Context, item: Item): File {
        val d = File(c.cacheDir, "safe-view").apply { mkdirs() }
        val f = File(d, item.name)
        copyOut(item, f, c, null)
        return f
    }

    fun wipeTemp(c: Context) { File(c.cacheDir, "safe-view").deleteRecursively() }

    fun delete(c: Context, item: Item) { File(dir(c), "${item.id}.bin").delete(); saveIndex(c, items(c).filter { it.id != item.id }) }

    fun rename(c: Context, item: Item, name: String) = saveIndex(c, items(c).map { if (it.id == item.id) it.copy(name = name) else it })
}

/** Reads items from the first Safe folder version (Keystore key + screen lock) so they can move to the password-based format. */
private object LegacySafe {
    fun migrate(c: Context) {
        val old = File(Safe.dir(c), "index.bin"); if (!old.isFile) return
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = (ks.getEntry("wayfinder-safe-v1", null) as? java.security.KeyStore.SecretKeyEntry)?.secretKey ?: return
            fun dec(f: File): ByteArray = f.inputStream().use { i ->
                val iv = ByteArray(12); i.read(iv)
                javax.crypto.CipherInputStream(i, Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }).readBytes()
            }
            val o = JSONObject(dec(old).decodeToString())
            val tmpDir = File(c.cacheDir, "safe-migrate").apply { mkdirs() }
            val files = o.keys().asSequence().toList().mapNotNull { id ->
                val meta = o.getJSONObject(id); val bin = File(Safe.dir(c), "$id.bin")
                if (!bin.isFile) return@mapNotNull null
                File(tmpDir, meta.getString("n")).also { it.writeBytes(dec(bin)); bin.delete() }
            }
            old.delete()
            Safe.add(c, files, Jobs.Job(0, "Migrate") {})
            tmpDir.deleteRecursively()
        }
    }
}
