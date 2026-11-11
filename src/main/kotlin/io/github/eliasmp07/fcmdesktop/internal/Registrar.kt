package io.github.eliasmp07.fcmdesktop.internal

import io.github.eliasmp07.fcmdesktop.FcmDesktopConfig
import io.github.eliasmp07.fcmdesktop.FcmLogger
import io.github.eliasmp07.fcmdesktop.FcmRegistrationException
import io.github.eliasmp07.fcmdesktop.FirebaseDesktopOptions
import io.github.eliasmp07.fcmdesktop.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID

internal class Endpoints(
    val checkin: String = "https://android.clients.google.com/checkin",
    val register: String = "https://android.clients.google.com/c2dm/register3",
    val installations: String = "https://firebaseinstallations.googleapis.com/v1",
    val registrations: String = "https://fcmregistrations.googleapis.com/v1",
    val pushEndpoint: String = "https://fcm.googleapis.com/fcm/send/",
)

/** The HTTP half of the protocol: turns a Firebase config into an FCM token. */
internal class Registrar(
    private val options: FirebaseDesktopOptions,
    private val config: FcmDesktopConfig,
    private val logger: FcmLogger,
    private val endpoints: Endpoints = Endpoints(),
    private val retryDelayMillis: Long = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val random = SecureRandom()
    private val platform = ChromePlatform.current()
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(config.httpTimeoutMillis)).build()
    }

    /** Full registration: check-in, GCM registration, Firebase installation, FCM registration. */
    suspend fun register(): StoredState {
        val keys = WebPushCrypto.generateKeys(random)
        val device = checkin(null, null)
        val gcmAppId = "wp:${bundleId()}#${UUID.randomUUID()}"
        val gcmToken = gcmRegister(device, gcmAppId)
        val installation = createInstallation()
        val fcmToken = fcmRegister(method = "POST", path = "", gcmToken, keys, installation.authToken)
        logger.log(LogLevel.Info, "Registered with FCM", null)
        return StoredState(
            projectId = options.projectId,
            appId = options.appId,
            vapidKey = options.vapidKey,
            androidId = device.androidId,
            securityToken = device.securityToken,
            gcmToken = gcmToken,
            gcmAppId = gcmAppId,
            privateKey = keys.privateKey,
            publicKey = keys.publicKey,
            authSecret = keys.authSecret,
            fid = installation.fid,
            fisRefreshToken = installation.refreshToken,
            fisAuthToken = installation.authToken,
            fisAuthExpiresAt = installation.authExpiresAt,
            fcmToken = fcmToken,
            registeredAt = now(),
        )
    }

    /** Device check-in. With ids it refreshes an existing device, without it creates a new one. */
    suspend fun checkin(androidId: Long?, securityToken: Long?): CheckinResponse {
        val body = encodeCheckinRequest(androidId, securityToken, platform, CHROME_VERSION)
        var lastError = "no response"
        repeat(3) { attempt ->
            try {
                val response = send("POST", endpoints.checkin, mapOf("Content-Type" to "application/x-protobuf"), body)
                if (response.statusCode() == 200) {
                    decodeCheckinResponse(response.body())?.let { return it }
                    lastError = "response without device id"
                } else {
                    lastError = "HTTP ${response.statusCode()}"
                }
            } catch (e: IOException) {
                lastError = e.toString()
            }
            if (attempt < 2) delay(retryDelayMillis)
        }
        throw FcmRegistrationException("FCM device check-in failed: $lastError")
    }

    /**
     * Re-validates the token against the backend, as the web SDK does weekly.
     * Returns the updated state, or null when the registration is gone and a full [register] is needed.
     */
    suspend fun refresh(state: StoredState): StoredState? {
        val withAuth = ensureInstallationAuth(state) ?: return null
        return try {
            val token = fcmRegister("PATCH", "/" + urlEncode(withAuth.fcmToken), withAuth.gcmToken, withAuth.keys, withAuth.fisAuthToken)
            withAuth.copy(fcmToken = token, registeredAt = now())
        } catch (e: FcmRegistrationException) {
            if (e.httpStatus in 400..499) null else throw e
        }
    }

    /** Unregisters the token server-side. */
    suspend fun delete(state: StoredState) {
        val withAuth = ensureInstallationAuth(state) ?: return
        val url = "${endpoints.registrations}/projects/${urlEncode(options.projectId)}/registrations/${urlEncode(withAuth.fcmToken)}"
        val response = send("DELETE", url, fcmHeaders(withAuth.fisAuthToken), null)
        if (response.statusCode() !in 200..299 && response.statusCode() != 404) {
            throw httpError("deleting the FCM token", response)
        }
    }

    private suspend fun gcmRegister(device: CheckinResponse, gcmAppId: String): String {
        val androidId = java.lang.Long.toUnsignedString(device.androidId)
        val form = mapOf(
            "app" to platform.gcmApp,
            "X-subtype" to gcmAppId,
            "device" to androidId,
            "sender" to (options.vapidKey ?: DEFAULT_VAPID_KEY),
        ).entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }
        val headers = mapOf(
            "Authorization" to "AidLogin $androidId:${java.lang.Long.toUnsignedString(device.securityToken)}",
            "Content-Type" to "application/x-www-form-urlencoded",
        )
        var lastError = "no response"
        // The endpoint regularly answers a transient error right after a fresh check-in.
        repeat(5) { attempt ->
            try {
                val text = String(send("POST", endpoints.register, headers, form.toByteArray()).body(), Charsets.UTF_8).trim()
                if (text.startsWith("token=")) return text.substringAfter('=')
                lastError = text.take(200)
            } catch (e: IOException) {
                lastError = e.toString()
            }
            if (attempt < 4) delay(retryDelayMillis)
        }
        throw FcmRegistrationException("GCM registration failed: $lastError")
    }

    private class Installation(val fid: String, val refreshToken: String, val authToken: String, val authExpiresAt: Long)

    private suspend fun createInstallation(): Installation {
        val body = buildJsonObject {
            put("appId", options.appId)
            put("authVersion", "FIS_v2")
            put("fid", newFid())
            put("sdkVersion", SDK_VERSION)
        }
        val url = "${endpoints.installations}/projects/${urlEncode(options.projectId)}/installations"
        val response = send("POST", url, jsonHeaders(), body.toString().toByteArray())
        if (response.statusCode() !in 200..299) throw httpError("creating the Firebase installation", response)
        val json = parse(response)
        val auth = json["authToken"] as? JsonObject
        return Installation(
            fid = json.string("fid") ?: throw FcmRegistrationException("Firebase installation response has no fid"),
            refreshToken = json.string("refreshToken")
                ?: throw FcmRegistrationException("Firebase installation response has no refresh token"),
            authToken = auth?.string("token") ?: throw FcmRegistrationException("Firebase installation response has no auth token"),
            authExpiresAt = expiry(auth.string("expiresIn")),
        )
    }

    /** Returns the state with a valid installation auth token, or null when the installation is gone. */
    private suspend fun ensureInstallationAuth(state: StoredState): StoredState? {
        if (state.fisAuthExpiresAt - now() > 60 * 60 * 1000) return state
        val body = buildJsonObject {
            putJsonObject("installation") {
                put("sdkVersion", SDK_VERSION)
                put("appId", options.appId)
            }
        }
        val url = "${endpoints.installations}/projects/${urlEncode(options.projectId)}/installations/${urlEncode(state.fid)}/authTokens:generate"
        val headers = jsonHeaders() + ("Authorization" to "FIS_v2 ${state.fisRefreshToken}")
        val response = send("POST", url, headers, body.toString().toByteArray())
        if (response.statusCode() in 400..499) return null
        if (response.statusCode() !in 200..299) throw httpError("refreshing the Firebase installation token", response)
        val json = parse(response)
        val token = json.string("token") ?: return null
        return state.copy(fisAuthToken = token, fisAuthExpiresAt = expiry(json.string("expiresIn")))
    }

    private suspend fun fcmRegister(method: String, path: String, gcmToken: String, keys: PushKeys, installationAuth: String): String {
        val body = buildJsonObject {
            putJsonObject("web") {
                put("endpoint", endpoints.pushEndpoint + gcmToken)
                put("auth", B64.urlEncode(keys.authSecret))
                put("p256dh", B64.urlEncode(keys.publicKey))
                // The backend rejects the default key when it is sent explicitly.
                options.vapidKey?.takeIf { it != DEFAULT_VAPID_KEY }?.let { put("applicationPubKey", it) }
            }
        }
        val url = "${endpoints.registrations}/projects/${urlEncode(options.projectId)}/registrations$path"
        var failure: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = send(method, url, fcmHeaders(installationAuth), body.toString().toByteArray())
                if (response.statusCode() in 200..299) {
                    return parse(response).string("token")
                        ?: throw FcmRegistrationException("FCM registration response has no token")
                }
                val error = httpError("registering with FCM", response)
                if (response.statusCode() in 400..499) throw error
                failure = error
            } catch (e: IOException) {
                failure = e
            }
            if (attempt < 1) delay(retryDelayMillis)
        }
        throw (failure as? FcmRegistrationException) ?: FcmRegistrationException("FCM registration failed", failure)
    }

    private fun jsonHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Accept" to "application/json",
        "x-goog-api-key" to options.apiKey,
    )

    private fun fcmHeaders(installationAuth: String): Map<String, String> =
        jsonHeaders() + ("x-goog-firebase-installations-auth" to "FIS $installationAuth")

    private suspend fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse<ByteArray> {
        val publisher = body?.let(HttpRequest.BodyPublishers::ofByteArray) ?: HttpRequest.BodyPublishers.noBody()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMillis(config.httpTimeoutMillis))
            .method(method, publisher)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return runInterruptible(Dispatchers.IO) {
            try {
                http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: InterruptedException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException(e)
            }
        }
    }

    private fun parse(response: HttpResponse<ByteArray>): JsonObject = try {
        Json.parseToJsonElement(String(response.body(), Charsets.UTF_8)).jsonObject
    } catch (e: Exception) {
        throw FcmRegistrationException("Unexpected response from Firebase (HTTP ${response.statusCode()})", e)
    }

    private fun httpError(action: String, response: HttpResponse<ByteArray>): FcmRegistrationException {
        val detail = String(response.body(), Charsets.UTF_8).replace(Regex("\\s+"), " ").take(400)
        val hint = when (response.statusCode()) {
            400 -> " Check projectId, appId and apiKey."
            401, 403 -> " Check that the API key allows the Firebase Installations API and the FCM Registration API, " +
                "and that it has no Android/iOS/HTTP-referrer restriction."
            else -> ""
        }
        return FcmRegistrationException("Error ${response.statusCode()} while $action.$hint $detail", null, response.statusCode())
    }

    private fun expiry(expiresIn: String?): Long {
        val seconds = expiresIn?.trim()?.removeSuffix("s")?.toLongOrNull() ?: 0
        return now() + seconds * 1000
    }

    /** Firebase installation id: 17 random bytes whose first 4 bits are 0111, base64url, 22 characters. */
    private fun newFid(): String {
        val bytes = ByteArray(17).also(random::nextBytes)
        bytes[0] = (0x70 or (bytes[0].toInt() and 0x0F)).toByte()
        return B64.urlEncode(bytes).substring(0, 22)
    }

    private fun bundleId(): String =
        config.appName.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.').ifEmpty { "app" } + ".fcm.desktop"

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        const val CHROME_VERSION = "94.0.4606.51"
        const val SDK_VERSION = "w:0.6.6"

        /** Public key of the FCM default sender, used when the app sets no VAPID key of its own. */
        const val DEFAULT_VAPID_KEY =
            "BDOU99-h67HcA6JeFXHbSNMu7e2yNNu3RzoMj8TM4W88jITfq7ZmPvIM1Iv-4_l2LxQcYwhqby2xGpWwzjfAnG4"
    }
}
