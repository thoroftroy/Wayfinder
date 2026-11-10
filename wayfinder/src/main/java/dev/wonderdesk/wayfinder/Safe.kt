package dev.wonderdesk.wayfinder

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wayfinder's Safe folder: files moved here are encrypted with AES-256-GCM using a key that lives
 * in the phone's secure hardware (Android Keystore), stored inside Wayfinder's private storage
 * where no other app can see them, and opened only after fingerprint, face or screen-lock unlock.
 *
 * Each item is two files: `<id>.bin` (12-byte IV + ciphertext) and the shared `index.json` that
 * maps ids to their original names, sizes and dates (the index is encrypted too).
 */
object Safe {
    private const val ALIAS = "wayfinder-safe-v1"
    private const val TAG_BITS = 128
    data class Item(val id: String, val name: String, val size: Long, val added: Long, val from: String)

    @Volatile var unlockedUntil = 0L
    val unlocked get() = System.currentTimeMillis() < unlockedUntil
    fun lock() { unlockedUntil = 0L }

    fun dir(c: Context) = File(c.filesDir, "safe").apply { mkdirs() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    private fun encrypt(input: InputStream, out: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        out.write(cipher.iv)
        CipherOutputStream(out, cipher).use { co -> input.copyTo(co, 256 * 1024) }
    }

    private fun decryptStream(f: File): InputStream {
        val input = f.inputStream().buffered(256 * 1024)
        val iv = ByteArray(12); require(input.read(iv) == 12) { "Damaged safe item" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
        return CipherInputStream(input, cipher)
    }

    private fun indexFile(c: Context) = File(dir(c), "index.bin")

    fun items(c: Context): List<Item> {
        val f = indexFile(c); if (!f.isFile) return emptyList()
        return runCatching {
            val o = JSONObject(decryptStream(f).use { it.readBytes().decodeToString() })
            o.keys().asSequence().map { id -> o.getJSONObject(id).let { Item(id, it.getString("n"), it.optLong("s"), it.optLong("a"), it.optString("f")) } }.toList()
        }.getOrDefault(emptyList()).sortedByDescending { it.added }
    }

    private fun saveIndex(c: Context, list: List<Item>) {
        val o = JSONObject(); list.forEach { o.put(it.id, JSONObject().put("n", it.name).put("s", it.size).put("a", it.added).put("f", it.from)) }
        val tmp = File(dir(c), "index.tmp")
        tmp.outputStream().use { out -> encrypt(o.toString().byteInputStream(), out) }
        tmp.renameTo(indexFile(c))
    }

    private fun newId(): String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    /** Encrypts [files] into the safe; originals are deleted only after their copy is verified. */
    fun add(c: Context, files: List<File>, onProgress: (Progress) -> Unit) {
        val all = files.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.toList() else listOf(it) }
        val total = all.sumOf { it.length() }.coerceAtLeast(1); var done = 0L
        val list = items(c).toMutableList()
        for (f in all) {
            if (Ops.cancel) throw Cancelled()
            val id = newId()
            val out = File(dir(c), "$id.bin")
            f.inputStream().use { i -> out.outputStream().use { o -> encrypt(i, o) } }
            // verify by decrypting and comparing length before removing the original
            val back = decryptStream(out).use { s -> var n = 0L; val b = ByteArray(256 * 1024); while (true) { val r = s.read(b); if (r < 0) break; n += r }; n }
            if (back != f.length()) { out.delete(); error("Couldn't safely encrypt ${f.name}") }
            list += Item(id, f.name, f.length(), System.currentTimeMillis(), f.parent ?: "")
            saveIndex(c, list)
            f.delete()
            done += f.length().coerceAtLeast(back); onProgress(Progress(done, total, f.name))
        }
        files.filter { it.isDirectory }.forEach { it.deleteRecursively() }
    }

    /** Decrypts an item back to [dest] (or where it came from) and removes it from the safe. */
    fun restore(c: Context, item: Item, dest: File? = null): File {
        val parent = dest ?: File(item.from).takeIf { it.isDirectory || it.mkdirs() } ?: Storage.primary
        val target = Ops.uniqueName(parent, item.name)
        decryptStream(File(dir(c), "${item.id}.bin")).use { i -> target.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
        File(dir(c), "${item.id}.bin").delete()
        saveIndex(c, items(c).filter { it.id != item.id })
        return target
    }

    /** A temporary decrypted copy for viewing; [wipeTemp] removes it when the user leaves. */
    fun openTemp(c: Context, item: Item): File {
        val d = File(c.cacheDir, "safe-view").apply { mkdirs() }
        val f = File(d, item.name)
        decryptStream(File(dir(c), "${item.id}.bin")).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    fun wipeTemp(c: Context) { File(c.cacheDir, "safe-view").deleteRecursively() }

    fun delete(c: Context, item: Item) {
        File(dir(c), "${item.id}.bin").delete()
        saveIndex(c, items(c).filter { it.id != item.id })
    }

    fun rename(c: Context, item: Item, name: String) = saveIndex(c, items(c).map { if (it.id == item.id) it.copy(name = name) else it })

    /** Asks for fingerprint, face or the screen lock. Unlocks for 5 minutes. */
    fun authenticate(a: Activity, onResult: (Boolean, String?) -> Unit) {
        val b = BiometricPrompt.Builder(a).setTitle("Unlock Safe folder").setSubtitle("Wayfinder")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
        val km = a.getSystemService(android.app.KeyguardManager::class.java)
        if (km?.isDeviceSecure != true) { onResult(false, "Set a screen lock in Android settings to use the Safe folder."); return }
        runCatching {
            b.build().authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlockedUntil = System.currentTimeMillis() + 5 * 60_000; onResult(true, null) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { onResult(false, if (code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) null else msg.toString()) }
            })
        }.onFailure { onResult(false, it.message) }
    }
}
