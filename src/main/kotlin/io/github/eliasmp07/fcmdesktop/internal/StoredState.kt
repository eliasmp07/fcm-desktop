package io.github.eliasmp07.fcmdesktop.internal

import io.github.eliasmp07.fcmdesktop.FirebaseDesktopOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Everything that has to survive a restart to keep the same FCM token. */
internal data class StoredState(
    val projectId: String,
    val appId: String,
    val vapidKey: String?,
    val androidId: Long,
    val securityToken: Long,
    val gcmToken: String,
    val gcmAppId: String,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val authSecret: ByteArray,
    val fid: String,
    val fisRefreshToken: String,
    val fisAuthToken: String,
    val fisAuthExpiresAt: Long,
    val fcmToken: String,
    val registeredAt: Long,
    val persistentIds: List<String> = emptyList(),
) {
    val keys: PushKeys get() = PushKeys(privateKey, publicKey, authSecret)

    fun matches(options: FirebaseDesktopOptions): Boolean =
        projectId == options.projectId && appId == options.appId && vapidKey == options.vapidKey

    fun toJson(): String = buildJsonObject {
        put("version", 1)
        put("projectId", projectId)
        put("appId", appId)
        vapidKey?.let { put("vapidKey", it) }
        put("androidId", java.lang.Long.toUnsignedString(androidId))
        put("securityToken", java.lang.Long.toUnsignedString(securityToken))
        put("gcmToken", gcmToken)
        put("gcmAppId", gcmAppId)
        put("privateKey", B64.urlEncode(privateKey))
        put("publicKey", B64.urlEncode(publicKey))
        put("authSecret", B64.urlEncode(authSecret))
        put("fid", fid)
        put("fisRefreshToken", fisRefreshToken)
        put("fisAuthToken", fisAuthToken)
        put("fisAuthExpiresAt", fisAuthExpiresAt)
        put("fcmToken", fcmToken)
        put("registeredAt", registeredAt)
        put("persistentIds", JsonArray(persistentIds.map(::JsonPrimitive)))
    }.toString()

    // Never print secrets.
    override fun toString(): String = "StoredState(projectId=$projectId, appId=$appId)"

    companion object {
        /** Returns null when the blob is missing, corrupt or from an incompatible version. */
        fun fromJson(text: String?): StoredState? {
            if (text.isNullOrBlank()) return null
            return try {
                val json = Json.parseToJsonElement(text).jsonObject
                fun string(name: String): String = json.string(name) ?: error("missing $name")
                StoredState(
                    projectId = string("projectId"),
                    appId = string("appId"),
                    vapidKey = json.string("vapidKey"),
                    androidId = java.lang.Long.parseUnsignedLong(string("androidId")),
                    securityToken = java.lang.Long.parseUnsignedLong(string("securityToken")),
                    gcmToken = string("gcmToken"),
                    gcmAppId = string("gcmAppId"),
                    privateKey = B64.decode(string("privateKey")),
                    publicKey = B64.decode(string("publicKey")),
                    authSecret = B64.decode(string("authSecret")),
                    fid = string("fid"),
                    fisRefreshToken = string("fisRefreshToken"),
                    fisAuthToken = string("fisAuthToken"),
                    fisAuthExpiresAt = json["fisAuthExpiresAt"]?.jsonPrimitive?.longOrNull ?: 0,
                    fcmToken = string("fcmToken"),
                    registeredAt = json["registeredAt"]?.jsonPrimitive?.longOrNull ?: 0,
                    persistentIds = (json["persistentIds"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .orEmpty(),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
