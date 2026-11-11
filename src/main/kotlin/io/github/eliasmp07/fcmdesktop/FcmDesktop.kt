package io.github.eliasmp07.fcmdesktop

import io.github.eliasmp07.fcmdesktop.internal.B64
import io.github.eliasmp07.fcmdesktop.internal.DataMessage
import io.github.eliasmp07.fcmdesktop.internal.Endpoints
import io.github.eliasmp07.fcmdesktop.internal.McsClient
import io.github.eliasmp07.fcmdesktop.internal.McsLoginException
import io.github.eliasmp07.fcmdesktop.internal.McsTransport
import io.github.eliasmp07.fcmdesktop.internal.Registrar
import io.github.eliasmp07.fcmdesktop.internal.StoredState
import io.github.eliasmp07.fcmdesktop.internal.WebPushCrypto
import io.github.eliasmp07.fcmdesktop.internal.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random

/**
 * Firebase Cloud Messaging client for desktop (JVM) apps.
 *
 * It registers the app with FCM, gives you a regular FCM registration token (send it to your
 * backend exactly like the Android/iOS ones) and keeps a connection open to receive pushes while the
 * app is running.
 *
 * ```
 * val fcm = FcmDesktop(FirebaseDesktopOptions(projectId = "...", appId = "...", apiKey = "..."))
 * scope.launch { fcm.token.filterNotNull().collect { sendTokenToBackend(it) } }
 * scope.launch { fcm.messages.collect { message -> println(message.data) } }
 * fcm.start()
 * ```
 */
public class FcmDesktop internal constructor(
    private val options: FirebaseDesktopOptions,
    private val config: FcmDesktopConfig,
    private val store: CredentialStore,
    private val notifier: DesktopNotifier?,
    private val logger: FcmLogger,
    endpoints: Endpoints,
    transport: McsTransport,
) : AutoCloseable {

    /**
     * @param store Where the registration is kept between runs. Defaults to a per-user file.
     * @param notifier Shows pushes that carry a `notification` payload. Pass `null` to handle the UI
     *   yourself from [messages].
     */
    public constructor(
        options: FirebaseDesktopOptions,
        config: FcmDesktopConfig = FcmDesktopConfig(),
        store: CredentialStore = FileCredentialStore.default(config.appName),
        notifier: DesktopNotifier? = SystemDesktopNotifier(config.appName),
        logger: FcmLogger = FcmLogger.Stderr,
    ) : this(options, config, store, notifier, logger, Endpoints(), McsTransport.Default)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val registrar = Registrar(options, config, logger, endpoints)
    private val mcs = McsClient(config, logger, transport)
    private val stateMutex = Mutex()
    private val lifecycleLock = Any()
    private var job: Job? = null

    @Volatile
    private var current: StoredState? = null

    private val _token = MutableStateFlow<String?>(null)
    private val _connectionState = MutableStateFlow(ConnectionState.Stopped)
    private val _messages = MutableSharedFlow<RemoteMessage>(extraBufferCapacity = 64)

    /**
     * The current FCM registration token, `null` until the first registration finishes. It emits
     * again whenever FCM issues a new token: forward every value to your backend.
     */
    public val token: StateFlow<String?> = _token.asStateFlow()

    /** Pushes received while connected. Start collecting before calling [start]: there is no replay. */
    public val messages: SharedFlow<RemoteMessage> = _messages.asSharedFlow()

    public val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Returns the FCM token, registering first when needed (network call on the first run).
     * @throws FcmRegistrationException when Firebase rejects the configuration or is unreachable.
     */
    public suspend fun getToken(): String = ensureRegistered().fcmToken

    /** Starts (or keeps) the background connection. Safe to call more than once. */
    public fun start() {
        synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            job = scope.launch { runLoop() }
        }
    }

    /** Closes the connection. Pushes sent meanwhile are delivered on the next [start] (within their TTL). */
    public fun stop() {
        synchronized(lifecycleLock) {
            job?.cancel()
            job = null
        }
        _connectionState.value = ConnectionState.Stopped
    }

    /**
     * Unregisters the token from FCM and forgets the local registration (call it on sign-out).
     * The next [getToken] or [start] creates a brand new token.
     */
    public suspend fun deleteToken() {
        val wasRunning = synchronized(lifecycleLock) { job?.isActive == true }
        stop()
        stateMutex.withLock {
            val state = current ?: StoredState.fromJson(loadBlob())
            try {
                if (state != null && state.matches(options)) registrar.delete(state)
            } finally {
                withContext(Dispatchers.IO) { store.clear() }
                current = null
                _token.value = null
            }
        }
        if (wasRunning) start()
    }

    /** Stops everything for good. The instance cannot be reused afterwards. */
    override fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun runLoop() {
        var reconnectDelay = config.initialReconnectDelayMillis
        var loginFailures = 0
        while (true) {
            try {
                if (current == null) _connectionState.value = ConnectionState.Registering
                var state = ensureRegistered()
                state = refreshIfStale(state)
                _connectionState.value = ConnectionState.Connecting
                // Keeps the device record alive on Google's side; not fatal when it fails.
                try {
                    registrar.checkin(state.androidId, state.securityToken)
                } catch (e: FcmRegistrationException) {
                    logger.log(LogLevel.Debug, "Device check-in failed", e)
                }
                mcs.run(
                    androidId = state.androidId,
                    securityToken = state.securityToken,
                    receivedPersistentIds = state.persistentIds,
                    onLoggedIn = {
                        reconnectDelay = config.initialReconnectDelayMillis
                        loginFailures = 0
                        _connectionState.value = ConnectionState.Connected
                    },
                    onMessage = ::handle,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: McsLoginException) {
                loginFailures++
                logger.log(LogLevel.Warn, e.message ?: "MCS login rejected", null)
                if (loginFailures >= 2) {
                    // The device credentials are no longer accepted: start over with a new registration.
                    logger.log(LogLevel.Warn, "Discarding the FCM registration and registering again", null)
                    forget()
                    loginFailures = 0
                }
            } catch (e: Exception) {
                logger.log(LogLevel.Warn, "FCM connection lost: ${e.message ?: e.javaClass.simpleName}", null)
            }
            _connectionState.value = ConnectionState.WaitingToReconnect
            delay(reconnectDelay / 2 + Random.nextLong(reconnectDelay / 2 + 1))
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(config.maxReconnectDelayMillis)
        }
    }

    private suspend fun ensureRegistered(): StoredState = stateMutex.withLock {
        current?.let { return it }
        val saved = StoredState.fromJson(loadBlob())
        val state = if (saved != null && saved.matches(options)) {
            saved
        } else {
            registrar.register().also { save(it) }
        }
        current = state
        _token.value = state.fcmToken
        state
    }

    private suspend fun refreshIfStale(state: StoredState): StoredState {
        if (System.currentTimeMillis() - state.registeredAt < config.tokenRefreshIntervalMillis) return state
        return try {
            val refreshed = registrar.refresh(state)
            if (refreshed == null) {
                logger.log(LogLevel.Info, "The FCM registration expired; registering again", null)
                forget()
                ensureRegistered()
            } else {
                stateMutex.withLock {
                    val merged = refreshed.copy(persistentIds = current?.persistentIds ?: refreshed.persistentIds)
                    save(merged)
                    current = merged
                    _token.value = merged.fcmToken
                    merged
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or a server hiccup: keep using the token we have and try again on the next connect.
            logger.log(LogLevel.Debug, "Could not refresh the FCM token", e)
            state
        }
    }

    private suspend fun forget() = stateMutex.withLock {
        withContext(Dispatchers.IO) { store.clear() }
        current = null
    }

    private suspend fun handle(stanza: DataMessage) {
        val state = current ?: return
        val persistentId = stanza.persistentId
        if (persistentId != null && persistentId in state.persistentIds) return // already delivered
        if (persistentId != null) remember(persistentId)

        if (stanza.appData["message_type"] == "deleted_messages") {
            logger.log(LogLevel.Info, "FCM dropped pending messages for this device (too many were queued)", null)
            return
        }
        val message = try {
            decode(stanza, state)
        } catch (e: Exception) {
            logger.log(LogLevel.Warn, "Could not decrypt a push message", e)
            return
        }
        logger.log(
            LogLevel.Info,
            "Push received (${if (message.notification != null) "notification" else "data only"}, ${message.data.size} data keys)",
            null,
        )
        _messages.emit(message)

        val notification = message.notification
        if (config.showNotifications && notifier != null && notification != null &&
            (!notification.title.isNullOrBlank() || !notification.body.isNullOrBlank())
        ) {
            val display = DesktopNotification(
                title = notification.title?.takeIf { it.isNotBlank() } ?: config.appName,
                body = notification.body.orEmpty(),
                message = message,
            )
            try {
                withContext(Dispatchers.IO) { notifier.show(display) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.Warn, "The notifier failed", e)
            }
        }
    }

    private fun decode(stanza: DataMessage, state: StoredState): RemoteMessage {
        val encrypted = stanza.rawData ?: throw IllegalArgumentException("push without payload")
        val senderKey = headerParameter(stanza.appData["crypto-key"], "dh")
        val plain = if (senderKey != null) {
            val encryption = stanza.appData["encryption"]
            val salt = headerParameter(encryption, "salt") ?: throw IllegalArgumentException("push without salt")
            val recordSize = headerParameter(encryption, "rs")?.toIntOrNull() ?: 4096
            WebPushCrypto.decryptAesGcm(state.keys, B64.decode(senderKey), B64.decode(salt), encrypted, recordSize)
        } else {
            WebPushCrypto.decryptAes128Gcm(state.keys, encrypted)
        }
        val json = Json.parseToJsonElement(String(plain, Charsets.UTF_8)).jsonObject
        return toRemoteMessage(json, stanza)
    }

    private suspend fun remember(persistentId: String) = stateMutex.withLock {
        val state = current ?: return@withLock
        val updated = state.copy(persistentIds = (state.persistentIds + persistentId).takeLast(MAX_PERSISTENT_IDS))
        current = updated
        try {
            save(updated)
        } catch (e: Exception) {
            logger.log(LogLevel.Debug, "Could not persist the delivery state", e)
        }
    }

    private suspend fun loadBlob(): String? = withContext(Dispatchers.IO) { store.load() }

    private suspend fun save(state: StoredState) = withContext(Dispatchers.IO) { store.save(state.toJson()) }

    internal companion object {
        private const val MAX_PERSISTENT_IDS = 100

        /** Reads `name=value` out of a header such as `dh=...;p256ecdsa=...` or `salt=...;rs=4096`. */
        internal fun headerParameter(header: String?, name: String): String? =
            header?.split(';', ',')
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("$name=") }
                ?.substring(name.length + 1)
                ?.trim('"')
                ?.takeIf { it.isNotEmpty() }

        internal fun toRemoteMessage(json: JsonObject, stanza: DataMessage): RemoteMessage {
            val notification = (json["notification"] as? JsonObject)?.let {
                RemoteNotification(
                    title = it.string("title"),
                    body = it.string("body"),
                    imageUrl = it.string("image"),
                    icon = it.string("icon"),
                    clickAction = it.string("click_action"),
                )
            }
            val data = (json["data"] as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
                ?.toMap()
                .orEmpty()
            return RemoteMessage(
                messageId = json.string("fcmMessageId") ?: json.string("messageId"),
                from = json.string("from") ?: stanza.from,
                notification = notification,
                data = data,
                collapseKey = json.string("collapse_key") ?: json.string("collapseKey"),
                link = (json["fcmOptions"] as? JsonObject)?.string("link"),
                sentTime = stanza.sent,
                ttl = stanza.ttl,
                persistentId = stanza.persistentId,
                raw = json,
            )
        }
    }
}
