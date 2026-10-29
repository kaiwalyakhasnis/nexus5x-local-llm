package a2ui

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.delay
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EWOULDBLOCK
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFL
import platform.posix.F_SETFD
import platform.posix.F_SETFL
import platform.posix.INADDR_ANY
import platform.posix.MSG_DONTWAIT
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockopt
import platform.posix.listen
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import ui.log

// Sockets without threads: everything is non-blocking and polled from
// coroutines on the app's one thread (delay() between tries), so the screen
// keeps drawing while a model writes for a minute.

internal class NetException(message: String) : Exception(message)

/** Non-blocking, and not inherited by the programs the app starts (they would keep the port open). */
private fun nonBlocking(fd: Int) {
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL) or O_NONBLOCK)
    fcntl(fd, F_SETFD, FD_CLOEXEC)
}

private fun again() = errno == EAGAIN || errno == EWOULDBLOCK

/** "a.b.c.d" as in_addr's s_addr: network order, which on this little-endian CPU is a first. */
private fun ipv4(host: String): UInt {
    val p = host.split('.').map { it.toUIntOrNull() ?: throw NetException("not an IPv4 address: $host") }
    if (p.size != 4) throw NetException("not an IPv4 address: $host")
    return p[0] or (p[1] shl 8) or (p[2] shl 16) or (p[3] shl 24)
}

private fun swap16(port: Int) = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).toUShort()

/** Writes all of [data], waiting while the socket is full. */
private suspend fun sendAll(fd: Int, data: ByteArray, timeoutMs: Long = 5000) {
    var off = 0
    var waited = 0L
    while (off < data.size) {
        val n = data.usePinned { send(fd, it.addressOf(off), (data.size - off).convert(), MSG_NOSIGNAL) }
        if (n > 0) { off += n.toInt(); continue }
        if (n < 0 && again() && waited < timeoutMs) { delay(10); waited += 10; continue }
        throw NetException("send failed (errno $errno)")
    }
}

/** A minimal HTTP/1.1 client for a local server (an IP address, no TLS), streaming the response. */
internal class Http(private val host: String, private val port: Int) {
    val address get() = "$host:$port"

    /**
     * Sends a request and hands each line of the response body to [onLine]
     * as it arrives (Server-Sent Events come line by line). Returns the
     * status code. Cancelling the coroutine closes the connection, which
     * also stops a llama.cpp server generating for it.
     */
    suspend fun request(method: String, path: String, body: String? = null, onLine: (String) -> Unit): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) throw NetException("socket failed")
        try {
            nonBlocking(fd)
            connectTo(fd)
            val bytes = body?.encodeToByteArray()
            val head = buildString {
                append("$method $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: close\r\nAccept: */*\r\n")
                if (bytes != null) append("Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            sendAll(fd, head.encodeToByteArray())
            if (bytes != null) sendAll(fd, bytes)
            return readResponse(fd, onLine)
        } finally {
            close(fd)
        }
    }

    private suspend fun connectTo(fd: Int) {
        memScoped {
            val a = alloc<sockaddr_in>()
            a.sin_family = AF_INET.convert()
            a.sin_port = swap16(port)
            a.sin_addr.s_addr = ipv4(host)
            if (connect(fd, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) return
            if (errno != EINPROGRESS) throw NetException("cannot connect to $host:$port")
        }
        repeat(300) {  // 3 s
            val ready = memScoped {
                val p = alloc<pollfd>()
                p.fd = fd
                p.events = POLLOUT.convert()
                poll(p.ptr, 1u, 0)
            }
            if (ready > 0) {
                val err = memScoped {
                    val e = alloc<IntVar>()
                    val len = alloc<socklen_tVar>()
                    len.value = sizeOf<IntVar>().convert()
                    getsockopt(fd, SOL_SOCKET, SO_ERROR, e.ptr, len.ptr)
                    e.value
                }
                if (err != 0) throw NetException("cannot connect to $host:$port")
                return
            }
            delay(10)
        }
        throw NetException("no answer from $host:$port")
    }

    private suspend fun readResponse(fd: Int, onLine: (String) -> Unit): Int {
        val buf = ByteArray(16384)
        val raw = Bytes()        // as received
        val body = Bytes()       // de-chunked, not yet split into lines
        var status = -1
        var chunked = false
        var headersDone = false
        var chunkLeft = -1       // bytes left in the current chunk; -1: a size line comes next
        var ended = false
        while (!ended) {
            val n = buf.usePinned { recv(fd, it.addressOf(0), buf.size.convert(), MSG_DONTWAIT) }.toInt()
            if (n < 0) {
                if (again()) { delay(25); continue }
                throw NetException("receive failed (errno $errno)")
            }
            if (n == 0) ended = true else raw.add(buf, n)
            if (!headersDone) {
                val end = raw.indexOf("\r\n\r\n")
                if (end < 0) { if (ended) throw NetException("no response"); continue }
                val head = raw.take(end + 4).decodeToString()
                status = head.substringAfter(' ').take(3).toIntOrNull() ?: -1
                chunked = head.lines().any { it.lowercase().startsWith("transfer-encoding:") && "chunked" in it.lowercase() }
                headersDone = true
            }
            if (!chunked) {
                body.add(raw.take(raw.size))
            } else {
                while (true) {
                    if (chunkLeft < 0) {
                        val eol = raw.indexOf("\r\n")
                        if (eol < 0) break
                        val size = raw.take(eol + 2).decodeToString().trim().substringBefore(';').toIntOrNull(16) ?: 0
                        if (size == 0) { ended = true; break }
                        chunkLeft = size
                    }
                    if (chunkLeft == 0) {  // the CRLF after a chunk
                        if (raw.size < 2) break
                        raw.take(2)
                        chunkLeft = -1
                        continue
                    }
                    if (raw.size == 0) break
                    val part = raw.take(minOf(chunkLeft, raw.size))
                    body.add(part)
                    chunkLeft -= part.size
                }
            }
            while (true) {
                val eol = body.indexOf("\n")
                if (eol < 0) break
                onLine(body.take(eol + 1).decodeToString().trimEnd('\n', '\r'))
            }
        }
        if (body.size > 0) onLine(body.take(body.size).decodeToString())
        return status
    }
}

/** A growable byte queue: add at the end, take from the front. */
private class Bytes {
    private var data = ByteArray(4096)
    private var start = 0
    private var end = 0
    val size get() = end - start

    fun add(src: ByteArray, n: Int = src.size) {
        if (end + n > data.size) {
            val next = ByteArray(maxOf(data.size * 2, size + n))
            data.copyInto(next, 0, start, end)
            end -= start
            start = 0
            data = next
        }
        src.copyInto(data, end, 0, n)
        end += n
    }

    fun take(n: Int): ByteArray = data.copyOfRange(start, start + n).also { start += n }

    fun indexOf(s: String): Int {
        val pat = s.encodeToByteArray()
        outer@ for (i in start..end - pat.size) {
            for (k in pat.indices) if (data[i + k] != pat[k]) continue@outer
            return i - start
        }
        return -1
    }
}

/**
 * A plain TCP port for other programs: JSON lines both ways. What comes in
 * goes to [onLine]; [broadcast] sends a line to everyone connected.
 */
internal class Link(private val port: Int, private val onLine: (String) -> Unit) {
    private class Peer(val fd: Int) { val pending = Bytes() }
    private val peers = mutableListOf<Peer>()

    /** The listening socket, or null while the port is taken. */
    private fun open(): Int? {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        memScoped {
            val one = alloc<IntVar>()
            one.value = 1
            setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
            val a = alloc<sockaddr_in>()
            a.sin_family = AF_INET.convert()
            a.sin_port = swap16(port)
            a.sin_addr.s_addr = INADDR_ANY
            if (bind(fd, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0 || listen(fd, 4) != 0) {
                close(fd)
                return null
            }
        }
        nonBlocking(fd)
        return fd
    }

    suspend fun run() {
        var fd = open()
        if (fd == null) log("a2ui: port $port is busy; trying again every 2 s")
        while (fd == null) {
            delay(2000)
            fd = open()
        }
        log("a2ui: listening on port $port (JSON lines)")
        val buf = ByteArray(8192)
        while (true) {
            val c = accept(fd, null, null)
            if (c >= 0) { nonBlocking(c); peers += Peer(c) }
            for (p in peers.toList()) {
                val n = buf.usePinned { recv(p.fd, it.addressOf(0), buf.size.convert(), MSG_DONTWAIT) }.toInt()
                if (n == 0 || (n < 0 && !again())) { close(p.fd); peers.remove(p); continue }
                if (n > 0) {
                    p.pending.add(buf, n)
                    while (true) {
                        val eol = p.pending.indexOf("\n")
                        if (eol < 0) break
                        val line = p.pending.take(eol + 1).decodeToString().trim()
                        if (line.isNotEmpty()) onLine(line)
                    }
                }
            }
            delay(150)
        }
    }

    suspend fun broadcast(line: String) {
        for (p in peers.toList()) {
            try {
                sendAll(p.fd, (line + "\n").encodeToByteArray(), 1000)
            } catch (e: NetException) {
                close(p.fd)
                peers.remove(p)
            }
        }
    }
}
