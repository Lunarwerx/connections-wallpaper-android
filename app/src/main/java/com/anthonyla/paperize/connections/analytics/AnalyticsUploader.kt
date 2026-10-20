package com.anthonyla.paperize.connections.analytics

import java.net.HttpURLConnection
import java.net.URL

/** What one POST to the batch door came to. */
internal enum class Delivery {
    /** Connections has the batch. */
    ACCEPTED,

    /** Connections refused this exact batch; sending it again cannot help, so it is dropped. */
    REJECTED,

    /** Network or server trouble: keep the batch and try again later. */
    FAILED
}

/** Blocking HTTP call; run it on a background thread. Never throws. */
internal object AnalyticsUploader {
    fun post(body: ByteArray, userAgent: String): Delivery = try {
        val connection = URL(AnalyticsConfig.ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = AnalyticsConfig.TIMEOUT_MS
            connection.readTimeout = AnalyticsConfig.TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", userAgent)
            connection.outputStream.use { it.write(body) }
            when (connection.responseCode) {
                in 200..299 -> Delivery.ACCEPTED
                400, 413 -> Delivery.REJECTED
                else -> Delivery.FAILED
            }
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        Delivery.FAILED
    }
}
