package com.anthonyla.paperize.connections.analytics

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the phone says about itself, sent as the context of every message. Pure data, no Android types. */
data class DeviceContext(
    val appName: String,
    val appVersion: String,
    val appBuild: String,
    val namespace: String,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val deviceName: String,
    val osVersion: String,
    val sdk: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val density: Float,
    val locale: String,
    val timezone: String,
    val wifi: Boolean,
    val cellular: Boolean,
    val carrier: String?,
    val installedAt: Long
)

/**
 * Builds the wire format the Connections Analytics batch door reads (Segment-compatible). Pure Kotlin so the
 * protocol contract can be tested on the JVM.
 */
internal object AnalyticsPayload {
    enum class Kind(val wire: String) { IDENTIFY("identify"), TRACK("track"), SCREEN("screen"), ALIAS("alias") }

    private const val SHORTENED_TEXT_CHARS = 512
    private val BODY_KEYS = setOf("properties", "traits")

    /** One message. Callers only build these once the current notice is agreed; [decidedAt] is that moment. */
    fun message(
        kind: Kind,
        anonymousId: String,
        timestamp: Long,
        device: DeviceContext,
        decidedAt: Long,
        userId: String? = null,
        name: String? = null,
        properties: Map<String, Any?> = emptyMap(),
        traits: Map<String, Any?> = emptyMap(),
        previousId: String? = null,
        messageId: String = UUID.randomUUID().toString()
    ): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "type" to JsonPrimitive(kind.wire),
            "messageId" to JsonPrimitive(messageId),
            "timestamp" to JsonPrimitive(isoTime(timestamp)),
            "anonymousId" to JsonPrimitive(anonymousId)
        )
        if (userId != null) fields["userId"] = JsonPrimitive(userId)
        when (kind) {
            Kind.IDENTIFY -> fields["traits"] = traits.toJsonObject()
            Kind.TRACK -> {
                fields["event"] = JsonPrimitive(name.orEmpty())
                fields["properties"] = properties.toJsonObject()
            }
            Kind.SCREEN -> {
                fields["name"] = JsonPrimitive(name.orEmpty())
                fields["properties"] = properties.toJsonObject()
            }
            Kind.ALIAS -> fields["previousId"] = JsonPrimitive(previousId.orEmpty())
        }
        fields["context"] = context(device, decidedAt)
        return JsonObject(fields)
    }

    /**
     * The message as one JSON line that is at most [AnalyticsConfig.MAX_MESSAGE_BYTES]. Long text is shortened
     * first; if that is not enough the properties or traits are replaced by a marker, so a message is never sent too big.
     */
    fun encode(message: JsonObject): String {
        val full = message.toString()
        if (utf8Size(full) <= AnalyticsConfig.MAX_MESSAGE_BYTES) return full
        val shortened = JsonObject(message.mapValues { (key, value) ->
            if (key in BODY_KEYS) shortenText(value) else value
        }).toString()
        if (utf8Size(shortened) <= AnalyticsConfig.MAX_MESSAGE_BYTES) return shortened
        return JsonObject(message.mapValues { (key, value) ->
            if (key in BODY_KEYS) JsonObject(mapOf("_trimmed" to JsonPrimitive(true))) else value
        }).toString()
    }

    /** The request body: up to 100 encoded messages, the write key and when this batch was sent. */
    fun batch(encodedMessages: List<String>, sentAt: Long): String =
        "{\"batch\":[" + encodedMessages.joinToString(",") + "],\"writeKey\":" +
            JsonPrimitive(AnalyticsConfig.WRITE_KEY) + ",\"sentAt\":" + JsonPrimitive(isoTime(sentAt)) + "}"

    /** Starts with the library name and version, then the app, as the server expects for a device. */
    fun userAgent(device: DeviceContext): String = ascii(
        "${AnalyticsConfig.LIBRARY_NAME}/${AnalyticsConfig.LIBRARY_VERSION} " +
            "ConnectionsWallpaper/${device.appVersion} (Android ${device.osVersion}; ${device.manufacturer} ${device.model})"
    )

    fun isoTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun context(device: DeviceContext, decidedAt: Long): JsonObject = JsonObject(
        linkedMapOf(
            "app" to obj("name" to device.appName, "version" to device.appVersion, "build" to device.appBuild, "namespace" to device.namespace),
            "device" to obj(
                "manufacturer" to device.manufacturer,
                "model" to device.model,
                "brand" to device.brand,
                "name" to device.deviceName,
                "type" to "android"
            ),
            "os" to obj("name" to "Android", "version" to device.osVersion, "sdk" to device.sdk),
            "screen" to obj("width" to device.screenWidth, "height" to device.screenHeight, "density" to device.density),
            "locale" to JsonPrimitive(device.locale),
            "timezone" to JsonPrimitive(device.timezone),
            "network" to obj("wifi" to device.wifi, "cellular" to device.cellular, "carrier" to device.carrier),
            "library" to obj("name" to AnalyticsConfig.LIBRARY_NAME, "version" to AnalyticsConfig.LIBRARY_VERSION),
            "tracking" to tracking(decidedAt)
        )
    )

    /** The consent evidence. The server drops optional purposes that lack it. */
    private fun tracking(decidedAt: Long): JsonObject = obj(
        "version" to 1,
        "source" to "connections_consent",
        "policyVersion" to AnalyticsConfig.POLICY_VERSION,
        "decidedAt" to decidedAt,
        "purposes" to mapOf(
            "analytics" to "granted",
            "personalization" to "granted",
            "marketing" to "granted",
            "intelligence_graph" to "granted",
            "cross_context_advertising" to "denied"
        )
    )

    private fun obj(vararg fields: Pair<String, Any?>): JsonObject =
        JsonObject(fields.associate { (key, value) -> key to value.toJsonElement() })

    private fun Map<String, Any?>.toJsonObject(): JsonObject =
        JsonObject(mapValues { (_, value) -> value.toJsonElement() })

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Enum<*> -> JsonPrimitive(name)
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toJsonElement() })
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
    }

    private fun shortenText(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> shortenText(value) })
        is JsonArray -> JsonArray(element.map { shortenText(it) })
        is JsonPrimitive ->
            if (element.isString && element.content.length > SHORTENED_TEXT_CHARS) {
                JsonPrimitive(element.content.take(SHORTENED_TEXT_CHARS))
            } else {
                element
            }
        else -> element
    }

    private fun ascii(text: String): String = text.map { if (it in ' '..'~') it else '?' }.joinToString("")
}
