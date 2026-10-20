package com.anthonyla.paperize.connections.analytics

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire contract the Connections Analytics batch door reads. */
class AnalyticsPayloadTest {

    private val device = DeviceContext(
        appName = "Connections Wallpaper",
        appVersion = "1.2.3",
        appBuild = "123",
        namespace = "icu.connections.wallpaper",
        manufacturer = "Google",
        model = "Pixel 8",
        brand = "google",
        deviceName = "shiba",
        osVersion = "15",
        sdk = 35,
        screenWidth = 1080,
        screenHeight = 2400,
        density = 2.625f,
        locale = "en-US",
        timezone = "Europe/London",
        wifi = true,
        cellular = false,
        carrier = null,
        installedAt = 1_700_000_000_000L
    )
    private val decidedAt = 1_799_000_000_000L
    private val sentAt = 1_800_000_000_000L

    @Test
    fun trackBatchAndTrimmingFollowTheServerContract() {
        val message = AnalyticsPayload.message(
            kind = AnalyticsPayload.Kind.TRACK,
            anonymousId = "anon-1",
            timestamp = sentAt,
            device = device,
            decidedAt = decidedAt,
            userId = "member-sub",
            name = "Settings Changed",
            properties = mapOf("keys" to listOf("theme"), "values" to mapOf("theme" to "dark"))
        )
        val body = Json.parseToJsonElement(AnalyticsPayload.batch(listOf(AnalyticsPayload.encode(message)), sentAt)).jsonObject

        assertEquals(AnalyticsConfig.WRITE_KEY, body.getValue("writeKey").jsonPrimitive.content)
        val sent = body.getValue("batch").jsonArray.single().jsonObject
        assertEquals("track", sent.getValue("type").jsonPrimitive.content)
        assertEquals("Settings Changed", sent.getValue("event").jsonPrimitive.content)
        assertEquals("member-sub", sent.getValue("userId").jsonPrimitive.content)
        assertNotNull(sent["messageId"])
        assertEquals(sentAt, Instant.parse(sent.getValue("timestamp").jsonPrimitive.content).toEpochMilli())

        val context = sent.getValue("context").jsonObject
        assertEquals("analytics-android", context.getValue("library").jsonObject.getValue("name").jsonPrimitive.content)
        val tracking = context.getValue("tracking").jsonObject
        assertEquals(1, tracking.getValue("version").jsonPrimitive.int)
        assertEquals("connections_consent", tracking.getValue("source").jsonPrimitive.content)
        assertEquals(AnalyticsConfig.POLICY_VERSION, tracking.getValue("policyVersion").jsonPrimitive.content)
        assertEquals(decidedAt, tracking.getValue("decidedAt").jsonPrimitive.long)
        val purposes = tracking.getValue("purposes").jsonObject
        assertTrue(purposes.values.all { it.jsonPrimitive.content == "granted" || it.jsonPrimitive.content == "denied" })
        assertEquals("denied", purposes.getValue("cross_context_advertising").jsonPrimitive.content)

        // A message over 32 KB keeps its event and has its long text shortened instead of being sent too big.
        val oversized = AnalyticsPayload.message(
            kind = AnalyticsPayload.Kind.TRACK,
            anonymousId = "anon-1",
            timestamp = sentAt,
            device = device,
            decidedAt = decidedAt,
            name = "Big Event",
            properties = mapOf("blob" to "x".repeat(40_000))
        )
        val encoded = AnalyticsPayload.encode(oversized)
        assertTrue(AnalyticsPayload.utf8Size(encoded) <= AnalyticsConfig.MAX_MESSAGE_BYTES)
        val trimmed = Json.parseToJsonElement(encoded).jsonObject
        assertEquals("Big Event", trimmed.getValue("event").jsonPrimitive.content)
        assertEquals(512, trimmed.getValue("properties").jsonObject.getValue("blob").jsonPrimitive.content.length)
    }
}
