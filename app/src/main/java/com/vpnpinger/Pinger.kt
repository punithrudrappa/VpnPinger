package com.vpnpinger

import java.net.HttpURLConnection
import java.net.URL

/**
 * The "curl" step: a minimal HTTP GET with sane timeouts.
 * Never throws - always returns a short, human-readable result.
 */
object Pinger {

    fun ping(url: String): String {
        val startedAt = System.currentTimeMillis()
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "VpnPinger/0.0.1")
            }
            val code = try {
                connection.responseCode
            } finally {
                connection.disconnect()
            }
            "HTTP $code in ${System.currentTimeMillis() - startedAt} ms"
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName} - ${e.message ?: "no details"}"
        }
    }
}
