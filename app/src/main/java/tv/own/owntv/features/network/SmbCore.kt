package tv.own.owntv.features.network

import android.content.Context
import android.util.Log
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.security.bc.BCSecurityProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One saved network folder: an SMB share on a NAS or a computer. A blank [username] signs in as guest. */
data class SmbShare(
    val id: String,
    val name: String,
    val host: String,
    val share: String,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    /** A folder inside the share to start in ("Movies/4K"); blank is the share's top. */
    val startPath: String = "",
)

/** A file or folder inside a share. [path] is relative to the share, '/' separated, no leading slash. */
data class SmbEntry(val name: String, val path: String, val isFolder: Boolean, val size: Long)

/** The pure rules, kept out of Android and the network so they can be unit tested. */
object SmbRules {
    private val VIDEO = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "mts", "wmv", "flv", "webm", "mpg", "mpeg", "vob", "3gp", "ogv", "divx", "iso",
    )
    private val AUDIO = setOf("mp3", "flac", "aac", "m4a", "wav", "ogg", "opus", "wma")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isVideo(name: String): Boolean = extension(name) in VIDEO
    fun isPlayable(name: String): Boolean = extension(name).let { it in VIDEO || it in AUDIO }

    fun mimeType(name: String): String = when (extension(name)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi", "divx" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mpg", "mpeg", "vob" -> "video/mpeg"
        "wmv" -> "video/x-ms-wmv"
        "flv" -> "video/x-flv"
        "3gp" -> "video/3gpp"
        "ogv" -> "video/ogg"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** Joins share-relative parts with '/', dropping empty bits and stray slashes either way. */
    fun join(vararg parts: String): String =
        parts.flatMap { it.replace('\\', '/').split('/') }.filter { it.isNotBlank() }.joinToString("/")

    /** The folder above [path], or "" at the share's top. */
    fun parent(path: String): String = join(path).substringBeforeLast('/', "")

    /** smbj wants Windows separators. */
    fun smbPath(path: String): String = join(path).replace('/', '\\')

    /** Hidden and system names a file browser does not show. */
    fun hidden(name: String): Boolean = name == "." || name == ".." || name.startsWith('.') || name.endsWith('$') ||
        name.equals("@eaDir", true) || name.equals("#recycle", true) || name.equals("\$RECYCLE.BIN", true) ||
        name.equals("System Volume Information", true)

    /**
     * What the user typed in the server box, made into host + share + folder. Accepts "192.168.1.20",
     * "nas.local:4450", "\\NAS\Movies", "smb://nas/Movies/Kids". A share typed in its own box wins.
     */
    fun parseServer(server: String, share: String): Triple<String, String, String> {
        val cleaned = server.trim().removePrefix("smb://").removePrefix("SMB://").replace('\\', '/').trim('/')
        val bits = cleaned.split('/').filter { it.isNotBlank() }
        val host = bits.firstOrNull().orEmpty()
        val typedShare = share.trim().replace('\\', '/').trim('/')
        return if (typedShare.isNotEmpty()) {
            Triple(host, typedShare.substringBefore('/'), join(typedShare.substringAfter('/', ""), bits.drop(2).joinToString("/")))
        } else {
            Triple(host, bits.getOrNull(1).orEmpty(), bits.drop(2).joinToString("/"))
        }
    }

    /** host and port from "nas.local" or "nas.local:4450"; 445 by default. */
    fun hostPort(host: String): Pair<String, Int> {
        val h = host.trim()
        val colon = h.lastIndexOf(':')
        if (colon > 0 && h.indexOf(':') == colon) {
            h.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 }?.let { return h.substring(0, colon) to it }
        }
        return h to 445
    }

    /**
     * An HTTP "Range: bytes=a-b" for a file of [length] bytes, as an inclusive start..end, or null for
     * the whole file. A range that starts past the end comes back as an empty range (start > end).
     */
    fun parseRange(header: String?, length: Long): LongRange? {
        if (header == null) return null
        val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
        if (!header.trim().startsWith("bytes=") || '-' !in spec) return null
        val (a, b) = spec.split('-', limit = 2).map { it.trim() }
        return when {
            a.isEmpty() -> {
                val suffix = b.toLongOrNull() ?: return null
                if (suffix <= 0) return null
                (length - suffix).coerceAtLeast(0)..(length - 1)
            }
            else -> {
                val start = a.toLongOrNull() ?: return null
                if (start >= length) return LongRange.EMPTY
                val end = b.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1)
                if (end < start) null else start..end
            }
        }
    }

    /** Folders first, then files, each A→Z ignoring case; numbers in names sort as people expect. */
    val order: Comparator<SmbEntry> = compareBy<SmbEntry> { !it.isFolder }.thenComparator { x, y -> natural(x.name, y.name) }

    private fun natural(a: String, b: String): Int {
        val re = Regex("(\\d+)|(\\D+)")
        val pa = re.findAll(a.lowercase()).map { it.value }.toList()
        val pb = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(pa.size, pb.size)) {
            val x = pa[i]; val y = pb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return pa.size.compareTo(pb.size)
    }
}

/** The saved network folders, in the app's private preferences. */
class SmbShareStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mptv_network_folders", Context.MODE_PRIVATE)
    private val _shares = MutableStateFlow(load())
    val shares: StateFlow<List<SmbShare>> = _shares.asStateFlow()

    fun get(id: String): SmbShare? = _shares.value.firstOrNull { it.id == id }

    fun save(share: SmbShare) {
        val list = _shares.value.filterNot { it.id == share.id } + share
        _shares.value = list
        persist(list)
    }

    fun remove(id: String) {
        val list = _shares.value.filterNot { it.id == id }
        _shares.value = list
        persist(list)
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun load(): List<SmbShare> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SmbShare(
                id = o.getString("id"), name = o.optString("name"), host = o.optString("host"), share = o.optString("share"),
                username = o.optString("user"), password = o.optString("pass"), domain = o.optString("domain"),
                startPath = o.optString("path"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<SmbShare>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("host", it.host).put("share", it.share)
                    .put("user", it.username).put("pass", it.password).put("domain", it.domain).put("path", it.startPath),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object { const val KEY = "shares" }
}

/** Signed-in SMB shares, one connection per saved folder, reopened when the NAS drops it. Blocking: call off the main thread. */
class SmbClientPool(private val store: SmbShareStore) : Closeable {
    private val client = SMBClient(
        SmbConfig.builder()
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(45, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build(),
    )
    private class Open(val connection: Connection, val session: Session, val disk: DiskShare)
    private val open = ConcurrentHashMap<String, Open>()

    @Synchronized
    private fun disk(share: SmbShare): DiskShare {
        open[share.id]?.let { if (it.disk.isConnected && it.connection.isConnected) return it.disk else drop(share.id) }
        val (host, port) = SmbRules.hostPort(share.host)
        val connection = client.connect(host, port)
        val auth = if (share.username.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(share.username, share.password.toCharArray(), share.domain.ifBlank { null })
        val session = connection.authenticate(auth)
        val disk = session.connectShare(share.share) as? DiskShare
            ?: run { runCatching { connection.close() }; error("not a folder share") }
        open[share.id] = Open(connection, session, disk)
        return disk
    }

    private fun drop(id: String) {
        open.remove(id)?.let { runCatching { it.disk.close() }; runCatching { it.session.close() }; runCatching { it.connection.close() } }
    }

    /** Forgets [share]'s connection, after its details change. */
    fun forget(share: SmbShare) = drop(share.id)

    private fun <T> withRetry(share: SmbShare, block: (DiskShare) -> T): T = try {
        block(disk(share))
    } catch (e: Exception) {
        // A NAS that slept or rebooted leaves a dead connection behind: reconnect once.
        drop(share.id)
        block(disk(share))
    }

    fun list(share: SmbShare, path: String): List<SmbEntry> = withRetry(share) { disk ->
        disk.list(SmbRules.smbPath(path)).asSequence()
            .filterNot { SmbRules.hidden(it.fileName) }
            .filterNot { (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value) != 0L }
            .map {
                val folder = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                SmbEntry(it.fileName, SmbRules.join(path, it.fileName), folder, it.endOfFile)
            }
            .filter { it.isFolder || SmbRules.isPlayable(it.name) }
            .sortedWith(SmbRules.order)
            .toList()
    }

    /** A random-access reader for one file; close it when done. */
    fun openFile(share: SmbShare, path: String): SmbReader = withRetry(share) { disk ->
        val file = disk.openFile(
            SmbRules.smbPath(path), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        SmbReader(file)
    }

    override fun close() {
        open.keys.toList().forEach(::drop)
        runCatching { client.close() }
    }
}

class SmbReader(private val file: com.hierynomus.smbj.share.File) : Closeable {
    val length: Long = file.fileInformation.standardInformation.endOfFile
    fun read(buffer: ByteArray, offset: Long, count: Int): Int = file.read(buffer, offset, 0, count)
    override fun close() { runCatching { file.close() } }
}

/**
 * A tiny HTTP server on 127.0.0.1 that serves SMB files with byte ranges, so the built-in player
 * (and an external one) can seek through a film on the NAS like any web video. Each URL carries a
 * random key made at start-up, so other apps on the device cannot read the shares through it.
 */
class SmbStreamServer(private val store: SmbShareStore, private val pool: SmbClientPool) {
    private val key = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val pool2 = Executors.newCachedThreadPool { r -> Thread(r, "mptv-smb-http").apply { isDaemon = true } }
    @Volatile private var server: ServerSocket? = null

    @Synchronized
    private fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool2.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                pool2.execute { handle(socket) }
            }
        }
        return s.localPort
    }

    /** The http://127.0.0.1 address that plays [path] from [share]. */
    fun urlFor(share: SmbShare, path: String): String {
        val encoded = SmbRules.join(path).split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "http://127.0.0.1:${port()}/$key/${share.id}/$encoded"
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 60_000
            val input = s.getInputStream()
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
            try {
                val requestLine = readLine(input) ?: return
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val segments = parts.getOrNull(1).orEmpty().substringBefore('?').trimStart('/').split('/')
                if (method != "GET" && method != "HEAD") return status(out, 405, "Method Not Allowed")
                if (segments.size < 3 || segments[0] != key) return status(out, 404, "Not Found")
                val share = store.get(segments[1]) ?: return status(out, 404, "Not Found")
                val path = segments.drop(2).joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
                pool.openFile(share, path).use { reader -> serve(out, reader, path, headers["range"], method == "HEAD") }
            } catch (e: Exception) {
                Log.w(TAG, "network folder stream ended: ${e.message}")
            } finally {
                runCatching { out.flush() }
            }
        }
    }

    private fun serve(out: BufferedOutputStream, reader: SmbReader, path: String, rangeHeader: String?, headOnly: Boolean) {
        val length = reader.length
        val range = SmbRules.parseRange(rangeHeader, length)
        if (range != null && range.isEmpty()) {
            return write(out, "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$length\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        }
        val start = range?.first ?: 0L
        val end = range?.last ?: (length - 1)
        val count = if (length == 0L) 0L else end - start + 1
        val head = StringBuilder()
            .append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            .append("Content-Type: ").append(SmbRules.mimeType(path)).append("\r\n")
            .append("Accept-Ranges: bytes\r\n")
            .append("Content-Length: ").append(count).append("\r\n")
            .apply { if (range != null) append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n") }
            .append("Connection: close\r\n\r\n")
        write(out, head.toString())
        if (headOnly || count == 0L) return
        val buffer = ByteArray(CHUNK)
        var pos = start
        while (pos <= end) {
            val want = minOf(CHUNK.toLong(), end - pos + 1).toInt()
            val n = reader.read(buffer, pos, want)
            if (n <= 0) break
            out.write(buffer, 0, n)
            pos += n
        }
    }

    private fun status(out: BufferedOutputStream, code: Int, text: String) =
        write(out, "HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")

    private fun write(out: BufferedOutputStream, text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private companion object {
        const val TAG = "MptvSmb"
        const val CHUNK = 256 * 1024
    }
}
