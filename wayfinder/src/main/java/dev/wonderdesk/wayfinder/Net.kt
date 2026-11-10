package dev.wonderdesk.wayfinder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/* ------------------------------------------------ saved servers ------------------------------------------------ */

/** A saved network share. The password is encrypted with a hardware-backed key before it's stored. */
data class NetServer(val id: String, val name: String, val host: String, val share: String, val user: String, val domain: String, val secret: String, val port: Int = 445) {
    val guest get() = user.isBlank()
    val label get() = name.ifBlank { "$host/$share" }
}

object NetServers {
    private const val ALIAS = "wayfinder-net-v1"
    private fun prefs(c: Context) = c.getSharedPreferences("network", Context.MODE_PRIVATE)

    fun all(c: Context): List<NetServer> = runCatching {
        JSONArray(prefs(c).getString("servers", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            NetServer(o.getString("id"), o.optString("name"), o.getString("host"), o.optString("share"), o.optString("user"), o.optString("domain"), o.optString("secret"), o.optInt("port", 445))
        } }
    }.getOrDefault(emptyList())

    fun save(c: Context, list: List<NetServer>) = prefs(c).edit().putString("servers", JSONArray(list.map {
        JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("share", it.share).put("user", it.user).put("domain", it.domain).put("secret", it.secret).put("port", it.port)
    }).toString()).apply()

    fun upsert(c: Context, s: NetServer) = save(c, all(c).filter { it.id != s.id } + s)
    fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })
    fun newId() = UUID.randomUUID().toString()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }

    fun seal(password: String): String {
        if (password.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(password.toByteArray()), Base64.NO_WRAP)
    }

    fun open(secret: String): String {
        if (secret.isEmpty()) return ""
        val b = Base64.decode(secret, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }
}

/* --------------------------------------------------- SMB client -------------------------------------------------- */

data class NetEntry(val name: String, val path: String, val isDir: Boolean, val size: Long, val modified: Long) { val kind get() = if (isDir) Kind.Folder else kindOf(name) }

/** One open connection to a share. Paths are share-relative with "/" separators ("" is the top). */
class SmbSession private constructor(private val client: SMBClient, private val conn: Connection, private val session: Session, val share: DiskShare) : AutoCloseable {
    companion object {
        private val config: SmbConfig = SmbConfig.builder().withTimeout(20, TimeUnit.SECONDS).withSoTimeout(40, TimeUnit.SECONDS).withMultiProtocolNegotiate(true).build()

        fun open(s: NetServer): SmbSession {
            val client = SMBClient(config)
            val conn = try { client.connect(s.host, s.port) } catch (e: Exception) { client.close(); throw java.io.IOException("Couldn't reach ${s.host}. Check the address and that you're on the same network.") }
            try {
                val auth = if (s.guest) AuthenticationContext.guest() else AuthenticationContext(s.user, NetServers.open(s.secret).toCharArray(), s.domain.ifBlank { null })
                val session = conn.authenticate(auth)
                val share = session.connectShare(s.share) as? DiskShare ?: throw java.io.IOException("“${s.share}” isn't a folder share.")
                return SmbSession(client, conn, session, share)
            } catch (e: Exception) {
                runCatching { conn.close() }; client.close()
                val m = e.message.orEmpty()
                throw java.io.IOException(when {
                    "STATUS_LOGON_FAILURE" in m -> "The user name or password was rejected."
                    "STATUS_BAD_NETWORK_NAME" in m -> "There's no share called “${s.share}” on ${s.host}."
                    "STATUS_ACCESS_DENIED" in m -> "${s.host} refused access to “${s.share}”."
                    else -> m.ifBlank { "Couldn't open the share." }
                })
            }
        }
    }

    private fun smb(path: String) = path.trim('/').replace('/', '\\')

    fun list(path: String): List<NetEntry> = share.list(smb(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
        val dir = EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        NetEntry(it.fileName, (path.trim('/') + "/" + it.fileName).trim('/'), dir, it.endOfFile, it.lastWriteTime.toEpochMillis())
    }

    fun read(path: String): InputStream = share.openFile(smb(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).inputStream

    fun write(path: String): OutputStream = share.openFile(smb(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).outputStream

    fun mkdir(path: String) = share.mkdir(smb(path))

    fun delete(e: NetEntry) { if (e.isDir) share.rmdir(smb(e.path), true) else share.rm(smb(e.path)) }

    fun rename(e: NetEntry, newName: String) {
        val target = (e.path.substringBeforeLast('/', "") + "/" + newName).trim('/')
        if (e.isDir) share.openDirectory(smb(e.path), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(smb(target)) }
        else share.openFile(smb(e.path), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)).use { it.rename(smb(target)) }
    }

    /** Copies a remote file or folder to [dest] on the phone. */
    fun download(e: NetEntry, dest: File, onBytes: (Long) -> Unit) {
        if (e.isDir) { val d = Ops.uniqueName(dest, e.name).apply { mkdirs() }; list(e.path).forEach { download(it, d, onBytes) }; return }
        val t = Ops.uniqueName(dest, e.name)
        read(e.path).use { i -> t.outputStream().use { o -> pump(i, o, onBytes) } }
        if (e.modified > 0) t.setLastModified(e.modified)
    }

    /** Copies a phone file or folder into remote folder [path]. */
    fun upload(f: File, path: String, onBytes: (Long) -> Unit) {
        val target = (path.trim('/') + "/" + f.name).trim('/')
        if (f.isDirectory) { runCatching { mkdir(target) }; f.listFiles()?.forEach { upload(it, target, onBytes) }; return }
        f.inputStream().use { i -> write(target).use { o -> pump(i, o, onBytes) } }
    }

    private fun pump(i: InputStream, o: OutputStream, onBytes: (Long) -> Unit) {
        val b = ByteArray(256 * 1024)
        while (true) { if (Ops.cancel) throw Cancelled(); val n = i.read(b); if (n < 0) break; o.write(b, 0, n); onBytes(n.toLong()) }
    }

    override fun close() { runCatching { share.close() }; runCatching { session.close() }; runCatching { conn.close() }; runCatching { client.close() } }
}
