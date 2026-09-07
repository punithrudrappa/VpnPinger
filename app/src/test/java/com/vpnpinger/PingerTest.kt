package com.vpnpinger

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Host-side tests for [Pinger] against a minimal real loopback HTTP server.
 *
 * A raw [ServerSocket] is used instead of `com.sun.net.httpserver` because that
 * class lives in a JDK module that is not visible to Android's unit-test
 * compilation. The server only needs to answer one request per test.
 */
class PingerTest {

    private val servers = mutableListOf<ServerSocket>()
    private val responderThreads = mutableListOf<Thread>()

    @After
    fun tearDown() {
        responderThreads.forEach { it.interrupt() }
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        responderThreads.clear()
    }

    /**
     * Starts a one-shot HTTP server on loopback answering the first request with the
     * given status code. Returns its port.
     */
    private fun startServer(statusCode: Int): Int {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += server
        responderThreads += Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = socket.getInputStream()
                    // Read the request head until the blank line that ends the headers.
                    val buffer = ByteArray(4096)
                    var total = 0
                    while (total < buffer.size) {
                        val read = input.read(buffer, total, buffer.size - total)
                        if (read < 0) break
                        total += read
                        if (String(buffer, 0, total).contains("\r\n\r\n")) break
                    }
                    val response = (
                        "HTTP/1.1 $statusCode Status\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n" +
                            "\r\n"
                        ).toByteArray(Charsets.ISO_8859_1)
                    socket.getOutputStream().write(response)
                    socket.getOutputStream().flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
        return server.localPort
    }

    @Test
    fun `ping returns HTTP 200 for a reachable endpoint`() {
        val result = Pinger.ping("http://127.0.0.1:${startServer(200)}/ok")
        assertTrue("expected 'HTTP 200...', was: $result", result.startsWith("HTTP 200"))
    }

    @Test
    fun `ping reports non-200 status codes`() {
        val result = Pinger.ping("http://127.0.0.1:${startServer(404)}/missing")
        assertTrue("expected 'HTTP 404...', was: $result", result.startsWith("HTTP 404"))
    }

    @Test
    fun `ping reports an unreachable server as an error instead of throwing`() {
        // Bind and release a port so nothing is listening there.
        val deadPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val result = Pinger.ping("http://127.0.0.1:$deadPort/")
        assertTrue("expected 'ERROR...', was: $result", result.startsWith("ERROR"))
    }

    @Test
    fun `ping reports a malformed url as an error instead of throwing`() {
        val result = Pinger.ping("not a url")
        assertTrue("expected 'ERROR...', was: $result", result.startsWith("ERROR"))
    }
}
