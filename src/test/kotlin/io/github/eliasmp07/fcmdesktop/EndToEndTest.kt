package io.github.eliasmp07.fcmdesktop

import io.github.eliasmp07.fcmdesktop.internal.B64
import io.github.eliasmp07.fcmdesktop.internal.parseProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Drives the whole client against [FakeFirebase]: registration, login, delivery, acks, recovery. */
class EndToEndTest {
    private val fake = FakeFirebase()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clients = ArrayList<FcmDesktop>()
    private val shown = CopyOnWriteArrayList<DesktopNotification>()

    private val payload = """{"notification":{"title":"Pedido listo","body":"Ya salió 🚚"},"data":{"orderId":"42"},""" +
        """"from":"1234567890","fcmMessageId":"m-1","fcmOptions":{"link":"https://example.com/o/42"}}"""

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        scope.cancel()
        fake.close()
    }

    private fun client(
        store: CredentialStore = InMemoryCredentialStore(),
        config: FcmDesktopConfig = FcmDesktopConfig(appName = "Test App", initialReconnectDelayMillis = 50, maxReconnectDelayMillis = 200),
    ): FcmDesktop = FcmDesktop(
        options = FakeFirebase.options,
        config = config,
        store = store,
        notifier = { shown += it },
        logger = FcmLogger.None,
        endpoints = fake.endpoints,
        transport = fake.transport,
    ).also { clients += it }

    private fun FcmDesktop.collectMessages(): Channel<RemoteMessage> {
        val channel = Channel<RemoteMessage>(Channel.UNLIMITED)
        // UNDISPATCHED: the subscription exists before start() is called.
        scope.launch(start = CoroutineStart.UNDISPATCHED) { messages.collect { channel.send(it) } }
        return channel
    }

    private suspend fun FcmDesktop.awaitState(state: ConnectionState) = withTimeout(5_000) { connectionState.first { it == state } }

    /** Encrypts for the keys the client sent in its latest registration, as FCM would. */
    private fun McsSession.push(persistentId: String, json: String, modern: Boolean = false) {
        val web = fake.registrations.last()
        val publicKey = B64.decode(web.getValue("p256dh").jsonPrimitive.content)
        val auth = B64.decode(web.getValue("auth").jsonPrimitive.content)
        if (modern) {
            sendData(persistentId, mapOf("content-encoding" to "aes128gcm"), TestEncryptor.aes128Gcm(publicKey, auth, json.toByteArray()))
        } else {
            val encrypted = TestEncryptor.aesGcm(publicKey, auth, json.toByteArray())
            val headers = mapOf(
                "subtype" to "wp:test",
                "crypto-key" to "dh=${B64.urlEncode(encrypted.senderPublicKey)};p256ecdsa=ignored",
                "encryption" to "salt=${B64.urlEncode(encrypted.salt)}",
            )
            sendData(persistentId, headers, encrypted.ciphertext)
        }
    }

    @Test
    fun registersReceivesAndAcknowledgesPushes() = runBlocking<Unit> {
        val fcm = client()
        val received = fcm.collectMessages()
        assertEquals(ConnectionState.Stopped, fcm.connectionState.value)
        fcm.start()
        fcm.start() // idempotent

        val session = fake.nextSession()
        assertEquals("FCM_TOKEN_1", withTimeout(5_000) { fcm.token.first { it != null } })
        assertEquals("FCM_TOKEN_1", fcm.getToken())
        fcm.awaitState(ConnectionState.Connected)

        val androidId = java.lang.Long.toUnsignedString(FakeFirebase.ANDROID_ID_BASE + 1)
        assertEquals(androidId, session.loginString(3))
        assertEquals(androidId, session.loginString(4))
        assertEquals(java.lang.Long.toUnsignedString(FakeFirebase.SECURITY_TOKEN), session.loginString(5))
        assertEquals("android-" + java.lang.Long.toHexString(FakeFirebase.ANDROID_ID_BASE + 1), session.loginString(6))
        assertEquals("mcs.android.com", session.loginString(2))
        assertEquals(emptyList(), session.receivedPersistentIds)

        // Legacy aesgcm encoding, with a notification payload.
        session.push("pid-1", payload)
        val first = withTimeout(5_000) { received.receive() }
        assertEquals("Pedido listo", first.notification?.title)
        assertEquals("Ya salió 🚚", first.notification?.body)
        assertEquals(mapOf("orderId" to "42"), first.data)
        assertEquals("m-1", first.messageId)
        assertEquals("1234567890", first.from)
        assertEquals("https://example.com/o/42", first.link)
        assertEquals("pid-1", first.persistentId)
        assertEquals(3600, first.ttl)
        assertEquals(1_700_000_000_123, first.sentTime)
        session.expectAck("pid-1")
        assertEquals(listOf("Pedido listo" to "Ya salió 🚚"), shown.map { it.title to it.body })

        // aes128gcm encoding, data-only: delivered but nothing is shown.
        session.push("pid-2", """{"data":{"k":"v"}}""", modern = true)
        val second = withTimeout(5_000) { received.receive() }
        assertNull(second.notification)
        assertEquals(mapOf("k" to "v"), second.data)
        session.expectAck("pid-2")
        assertEquals(1, shown.size)

        // A redelivery and an undecryptable push are acknowledged but not delivered.
        session.push("pid-1", payload)
        session.expectAck("pid-1")
        session.sendData("pid-3", mapOf("content-encoding" to "aes128gcm"), ByteArray(120) { 9 })
        session.expectAck("pid-3")
        session.sendData("pid-4", mapOf("message_type" to "deleted_messages"), null)
        session.expectAck("pid-4")
        session.push("pid-5", """{"data":{"last":"1"}}""")
        assertEquals(mapOf("last" to "1"), withTimeout(5_000) { received.receive() }.data)
        session.expectAck("pid-5")
        assertEquals(1, shown.size)

        // Server ping -> ack carrying how many frames we have received (login response + 6 stanzas + ping).
        session.send(0, ByteArray(0))
        val (tag, ack) = session.readFrame()
        assertEquals(1, tag)
        assertEquals(8, parseProto(ack).single { it.number == 2 }.int)

        // stop() must close the socket even though the reader is blocked on it.
        fcm.stop()
        assertTrue(session.awaitClosed())
        assertEquals(ConnectionState.Stopped, fcm.connectionState.value)
        assertEquals(emptyList(), fake.problems)
    }

    @Test
    fun keepsTheTokenAcrossRestartsAndReconnects() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        val first = client(store)
        val firstMessages = first.collectMessages()
        first.start()
        val session = fake.nextSession()
        first.awaitState(ConnectionState.Connected)
        session.push("pid-1", payload)
        withTimeout(5_000) { firstMessages.receive() }
        session.expectAck("pid-1")
        val token = first.getToken()
        first.close()
        assertTrue(session.awaitClosed())

        // "Restart": a new instance over the same store reuses everything.
        val second = client(store)
        val secondMessages = second.collectMessages()
        assertEquals(token, second.getToken())
        second.start()
        val resumed = fake.nextSession()
        second.awaitState(ConnectionState.Connected)
        assertEquals(1, fake.gcmRegisterCalls.get())
        assertEquals(1, fake.installationCalls.get())
        assertEquals(1, fake.registrations.size)
        assertEquals(session.loginString(3), resumed.loginString(3))
        assertEquals(listOf("pid-1"), resumed.receivedPersistentIds)

        // A push the previous run already handled is not delivered twice.
        resumed.push("pid-1", payload)
        resumed.expectAck("pid-1")

        // The connection drops: the client comes back by itself and keeps receiving.
        resumed.close()
        val reconnected = fake.nextSession()
        second.awaitState(ConnectionState.Connected)
        reconnected.push("pid-2", """{"data":{"after":"reconnect"}}""")
        assertEquals(mapOf("after" to "reconnect"), withTimeout(5_000) { secondMessages.receive() }.data)
        assertEquals(token, second.token.value)
        assertEquals(emptyList(), fake.problems)
    }

    @Test
    fun registersAgainWhenTheServerRejectsTheDevice() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        val fcm = client(store)
        assertEquals("FCM_TOKEN_1", fcm.getToken())

        fake.rejectLogins.set(2)
        fcm.start()
        assertTrue(fake.nextSession().rejected)
        assertTrue(fake.nextSession().rejected)
        val session = fake.nextSession()
        fcm.awaitState(ConnectionState.Connected)

        assertEquals("FCM_TOKEN_2", fcm.token.value)
        // A brand new device was checked in for the new registration.
        assertNotEquals(java.lang.Long.toUnsignedString(FakeFirebase.ANDROID_ID_BASE + 1), session.loginString(3))
        assertEquals(2, fake.gcmRegisterCalls.get())
        assertNotEquals(null, store.load())
        assertEquals(emptyList(), fake.problems)
    }

    @Test
    fun refreshesAStaleToken() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        assertEquals("FCM_TOKEN_1", client(store).getToken())

        val fcm = client(store, FcmDesktopConfig(appName = "Test App", tokenRefreshIntervalMillis = 0))
        fcm.start()
        fake.nextSession()
        fcm.awaitState(ConnectionState.Connected)
        assertEquals("FCM_TOKEN_REFRESHED_1", fcm.token.value)
        assertEquals(1, fake.registrations.size)
        assertEquals(emptyList(), fake.problems)
    }

    @Test
    fun deleteTokenUnregistersAndForgets() = runBlocking<Unit> {
        val store = InMemoryCredentialStore()
        val fcm = client(store)
        assertEquals("FCM_TOKEN_1", fcm.getToken())

        fcm.deleteToken()
        assertEquals(listOf("FCM_TOKEN_1"), fake.deletedTokens)
        assertNull(store.load())
        assertNull(fcm.token.value)

        assertEquals("FCM_TOKEN_2", fcm.getToken())
        assertEquals(emptyList(), fake.problems)
    }

    @Test
    fun reportsConfigurationErrors() = runBlocking<Unit> {
        fake.installationStatus = 403
        val error = assertFailsWith<FcmRegistrationException> { client().getToken() }
        assertEquals(403, error.httpStatus)
        assertTrue("API key" in error.message.orEmpty(), error.message)
        assertTrue(FakeFirebase.API_KEY !in error.message.orEmpty())
    }

    @Test
    fun reconnectsWhenTheConnectionGoesSilent() = runBlocking<Unit> {
        val config = FcmDesktopConfig(
            appName = "Test App",
            heartbeatIntervalMillis = 5_000,
            heartbeatAckTimeoutMillis = 1_000,
            initialReconnectDelayMillis = 50,
            maxReconnectDelayMillis = 200,
        )
        val fcm = client(config = config)
        fcm.start()
        val silent = fake.nextSession()
        fcm.awaitState(ConnectionState.Connected)

        // The server never answers: the client pings, gives up and opens a new connection.
        assertEquals(0, silent.readFrame().first)
        assertTrue(silent.awaitClosed())
        val next = withTimeoutOrNull(5_000) { fake.nextSession() }
        assertNotEquals(null, next)
        fcm.awaitState(ConnectionState.Connected)
    }

    @Test
    fun parsesPushHeaders() {
        assertEquals("abc", FcmDesktop.headerParameter("dh=abc;p256ecdsa=zzz", "dh"))
        assertEquals("abc", FcmDesktop.headerParameter("p256ecdsa=zzz; dh=abc", "dh"))
        assertEquals("4096", FcmDesktop.headerParameter("salt=xyz;rs=4096", "rs"))
        assertNull(FcmDesktop.headerParameter("salt=xyz", "rs"))
        assertNull(FcmDesktop.headerParameter(null, "dh"))
    }
}
