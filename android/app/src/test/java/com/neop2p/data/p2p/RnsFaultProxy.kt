package com.neop2p.data.p2p

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TCP relay proxy for RNS fault injection (S05/S06/S07).
 *
 * Listens on [listenPort], relays bytes to [targetHost]:[targetPort] in both
 * directions. [kill] closes every live connection — the client's
 * TCPClientInterface auto-reconnects and the server's TCPServerInterface
 * spawns a fresh per-client interface, so the RNS path heals via the next
 * re-announce. [delayMs] (optional) sleeps before each relayed chunk to
 * simulate high latency.
 */
class RnsFaultProxy(
    private val listenPort: Int,
    private val targetHost: String,
    private val targetPort: Int,
    private val delayMs: Long = 0L,
    private val jitterMs: Long = 0L,
) {
    private val server = ServerSocket(listenPort, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val acceptThread = Thread { acceptLoop() }
    private val live = AtomicBoolean(true)
    private val connections = java.util.Collections.synchronizedList(mutableListOf<Socket>())

    /** Number of client connections accepted so far (for reconnect polling). */
    @Volatile
    var acceptedConnections: Int = 0
        private set

    fun start() {
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    private fun acceptLoop() {
        while (live.get()) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            acceptedConnections++
            connections.add(client)
            val upstream = try {
                Socket(targetHost, targetPort)
            } catch (e: Exception) {
                runCatching { client.close() }
                continue
            }
            connections.add(upstream)
            relay(client.getInputStream(), upstream.getOutputStream()).start()
            relay(upstream.getInputStream(), client.getOutputStream()).start()
        }
    }

    private fun relay(from: InputStream, to: OutputStream): Thread =
        Thread {
            val buf = ByteArray(4096)
            try {
                while (live.get()) {
                    val n = from.read(buf)
                    if (n < 0) break
                    if (delayMs > 0) {
                        val jitter = if (jitterMs > 0) (Math.random() * jitterMs).toLong() else 0L
                        Thread.sleep(delayMs + jitter)
                    }
                    to.write(buf, 0, n)
                    to.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { to.close() }
                runCatching { from.close() }
            }
        }.also { it.isDaemon = true }

    /** Close every live connection (both directions). */
    fun kill() {
        synchronized(connections) {
            connections.forEach { runCatching { it.close() } }
            connections.clear()
        }
    }

    fun stop() {
        live.set(false)
        kill()
        runCatching { server.close() }
    }
}
