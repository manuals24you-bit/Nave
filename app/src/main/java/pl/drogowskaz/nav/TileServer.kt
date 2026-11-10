package pl.drogowskaz.nav

import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Mały serwer HTTP na 127.0.0.1: oddaje pobrane mapy (.pmtiles, z obsługą nagłówka Range)
 * oraz czcionki z zasobów aplikacji. Dzięki temu silnik mapy działa w pełni offline.
 */
class TileServer(private val assets: AssetManager) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val files = ConcurrentHashMap<String, File>()

    val port: Int get() = server?.localPort ?: 0

    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = s
        pool.execute {
            while (!s.isClosed) {
                try {
                    val c = s.accept()
                    pool.execute { handle(c) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    /** Rejestruje plik pod nazwą używaną w URL: /tiles/<name> */
    fun register(name: String, file: File) { files[name] = file }
    fun unregister(name: String) { files.remove(name) }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            try {
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
                var range: String? = null
                while (true) {
                    val h = readLine(input) ?: break
                    if (h.isEmpty()) break
                    if (h.startsWith("Range:", true)) range = h.substring(6).trim()
                }
                val head = method == "HEAD"
                when {
                    path.startsWith("/tiles/") -> {
                        val f = files[path.removePrefix("/tiles/")]
                        if (f == null || !f.exists()) notFound(out) else serveFile(out, f, range, head)
                    }
                    path.startsWith("/fonts/") -> serveAsset(out, "fonts/" + path.removePrefix("/fonts/"), head)
                    else -> notFound(out)
                }
                out.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun notFound(out: OutputStream) {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }

    private fun serveFile(out: OutputStream, f: File, range: String?, head: Boolean) {
        RandomAccessFile(f, "r").use { raf ->
            val size = raf.length()
            var start = 0L
            var end = size - 1
            var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val r = range.removePrefix("bytes=").split("-")
                if (r[0].isNotEmpty()) {
                    start = r[0].toLong()
                    if (r.size > 1 && r[1].isNotEmpty()) end = minOf(r[1].toLong(), size - 1)
                } else if (r.size > 1 && r[1].isNotEmpty()) { // ostatnie N bajtów
                    start = maxOf(0L, size - r[1].toLong())
                }
                partial = true
            }
            if (start >= size || start > end) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val len = end - start + 1
            val sb = StringBuilder()
            sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            sb.append("Content-Type: application/octet-stream\r\n")
            sb.append("Accept-Ranges: bytes\r\n")
            if (partial) sb.append("Content-Range: bytes $start-$end/$size\r\n")
            sb.append("Content-Length: $len\r\nConnection: close\r\n\r\n")
            out.write(sb.toString().toByteArray())
            if (head) return
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun serveAsset(out: OutputStream, path: String, head: Boolean) {
        try {
            val bytes = assets.open(path).use { it.readBytes() }
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/x-protobuf\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray()
            )
            if (!head) out.write(bytes)
        } catch (_: Exception) {
            // Brak zakresu glifów (np. znaki spoza łacińskich) - pusta odpowiedź zamiast błędu
            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/x-protobuf\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }
    }
}
