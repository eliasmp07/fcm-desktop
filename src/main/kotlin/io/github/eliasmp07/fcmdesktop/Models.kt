package io.github.eliasmp07.fcmdesktop

import kotlinx.serialization.json.JsonObject

/**
 * Firebase project configuration. Take the values from the Firebase console:
 * Project settings > General > Your apps (a **Web** app is recommended) > SDK setup and configuration.
 *
 * @param projectId `projectId` of the Firebase config, e.g. `my-app-1234`.
 * @param appId `appId` of the Firebase config, e.g. `1:1234567890:web:abc123`.
 * @param apiKey `apiKey` of the Firebase config. It must be allowed to call the
 *   "Firebase Installations API" and the "FCM Registration API" (an unrestricted key works).
 * @param vapidKey Optional Web Push certificate public key (Project settings > Cloud Messaging >
 *   Web configuration). Leave it `null` to use the FCM default key.
 */
public data class FirebaseDesktopOptions(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val vapidKey: String? = null,
) {
    init {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        require(appId.isNotBlank()) { "appId must not be blank" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }

    // The api key is a credential: keep it out of logs.
    override fun toString(): String = "FirebaseDesktopOptions(projectId=$projectId, appId=$appId)"
}

/** Tuning knobs. The defaults are fine for a normal desktop app. */
public data class FcmDesktopConfig(
    /** Name used for the credentials folder and as the title fallback of notifications. */
    val appName: String = "fcm-desktop",
    /** Show a system notification automatically when a message carries a `notification` payload. */
    val showNotifications: Boolean = true,
    /** Send a ping when the connection has been silent for this long. */
    val heartbeatIntervalMillis: Long = 60_000,
    /** Time to wait for any traffic after a ping before the connection is considered dead. */
    val heartbeatAckTimeoutMillis: Long = 15_000,
    /** First delay before reconnecting; doubles on every failure up to [maxReconnectDelayMillis]. */
    val initialReconnectDelayMillis: Long = 2_000,
    val maxReconnectDelayMillis: Long = 5 * 60_000,
    /** Timeout of each HTTP call made while registering. */
    val httpTimeoutMillis: Long = 15_000,
    /** The FCM token is refreshed against the backend when it is older than this (the web SDK uses 7 days). */
    val tokenRefreshIntervalMillis: Long = 7L * 24 * 60 * 60 * 1000,
) {
    init {
        require(appName.isNotBlank()) { "appName must not be blank" }
        require(heartbeatIntervalMillis >= 5_000) { "heartbeatIntervalMillis must be at least 5000" }
        require(heartbeatAckTimeoutMillis >= 1_000) { "heartbeatAckTimeoutMillis must be at least 1000" }
        require(initialReconnectDelayMillis in 1..maxReconnectDelayMillis) {
            "initialReconnectDelayMillis must be between 1 and maxReconnectDelayMillis"
        }
    }
}

/** The `notification` part of an FCM message. */
public data class RemoteNotification(
    val title: String?,
    val body: String?,
    val imageUrl: String?,
    val icon: String?,
    val clickAction: String?,
)

/** A push message received from FCM. Mirrors `RemoteMessage` of the Android SDK. */
public data class RemoteMessage(
    /** FCM message id (`fcmMessageId`), when the server sent one. */
    val messageId: String?,
    /** Sender id (project number) that sent the message. */
    val from: String?,
    val notification: RemoteNotification?,
    /** Custom key/value pairs of the `data` payload. */
    val data: Map<String, String>,
    val collapseKey: String?,
    /** `webpush.fcm_options.link`, when present. */
    val link: String?,
    /** Server timestamp in epoch millis, 0 when unknown. */
    val sentTime: Long,
    /** Time to live in seconds, 0 when unknown. */
    val ttl: Int,
    /** Id FCM uses to de-duplicate deliveries. */
    val persistentId: String?,
    /** The whole decrypted payload, for fields this class does not model. */
    val raw: JsonObject,
)

public enum class ConnectionState {
    /** [FcmDesktop.start] has not been called, or [FcmDesktop.stop] was called. */
    Stopped,

    /** Registering with FCM (first run, or after the credentials became invalid). */
    Registering,

    /** Opening the socket / logging in. */
    Connecting,

    /** Logged in: pushes are delivered in real time. */
    Connected,

    /** The connection dropped; a reconnect is scheduled. */
    WaitingToReconnect,
}

public enum class LogLevel { Debug, Info, Warn, Error }

/** Receives the library's log lines. Tokens and keys are never logged. */
public fun interface FcmLogger {
    public fun log(level: LogLevel, message: String, throwable: Throwable?)

    public companion object {
        /** Discards everything. */
        public val None: FcmLogger = FcmLogger { _, _, _ -> }

        /** Prints Info and above to stderr. */
        public val Stderr: FcmLogger = FcmLogger { level, message, throwable ->
            if (level != LogLevel.Debug) {
                System.err.println("[fcm-desktop] $level: $message" + (throwable?.let { " ($it)" } ?: ""))
            }
        }
    }
}

/** Thrown when registering with FCM fails (bad config, network down, API key restrictions...). */
public class FcmRegistrationException(
    message: String,
    cause: Throwable? = null,
    /** HTTP status returned by Firebase, when the failure was an HTTP error. */
    public val httpStatus: Int? = null,
) : Exception(message, cause)
