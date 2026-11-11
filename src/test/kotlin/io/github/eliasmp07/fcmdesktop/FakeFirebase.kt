package io.github.eliasmp07.fcmdesktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.eliasmp07.fcmdesktop.internal.B64
import io.github.eliasmp07.fcmdesktop.internal.Endpoints
import io.github.eliasmp07.fcmdesktop.internal.McsTransport
import io.github.eliasmp07.fcmdesktop.internal.ProtoField
import io.github.eliasmp07.fcmdesktop.internal.ProtoWriter
import io.github.eliasmp07.fcmdesktop.internal.parseProto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Local stand-in for Google's servers: the registration HTTP endpoints and an MCS server speaking the
 * real wire format over a loopback socket (without TLS).
 */
internal class FakeFirebase : AutoCloseable {
    private val loopback = InetAddress.getLoopbackAddress()
    private val http = HttpServer.create(InetSocketAddress(loopback, 0), 0)
    private val mcs = ServerSocket(0, 50, loopback)

    val checkinCalls = AtomicInteger()
    val gcmRegisterCalls = AtomicInteger()
    val installationCalls = AtomicInteger()
    val patchCalls = AtomicInteger()
    val deletedTokens = CopyOnWriteArrayList<String>()
    val registrations = CopyOnWriteArrayList<JsonObject>()
    val problems = CopyOnWriteArrayList<String>()

    /** Number of upcoming logins the MCS server rejects. */
    val rejectLogins = AtomicInteger()

    /** When set, the installations endpoint answers with this HTTP status. */
    @Volatile var installationStatus = 200

    val sessions = LinkedBlockingQueue<McsSession>()
    private val allSessions = CopyOnWriteArrayList<McsSession>()

    val endpoints: Endpoints
    val transport = McsTransport { Socket(loopback, mcs.localPort) }

    init {
        val base = "http://127.0.0.1:${http.address.port}"
        endpoints = Endpoints(
            checkin = "$base/checkin",
            register = "$base/c2dm/register3",
            installations = "$base/v1",
            registrations = "$base/v1",
        )
        http.createContext("/checkin") { handle(it, ::checkin) }
        http.createContext("/c2dm/register3") { handle(it, ::gcmRegister) }
        http.createContext("/v1/projects/$PROJECT_ID/installations") { handle(it, ::installations) }
        http.createContext("/v1/projects/$PROJECT_ID/registrations") { handle(it, ::fcmRegistrations) }
        http.start()
        thread(isDaemon = true, name = "fake-mcs") { acceptLoop() }
    }

    fun nextSession(timeoutMillis: Long = 5_000): McsSession =
        sessions.poll(timeoutMillis, TimeUnit.MILLISECONDS) ?: error("the client did not connect to MCS. Problems: $problems")

    private class Reply(val status: Int, val body: ByteArray, val contentType: String = "application/json")

    private fun handle(exchange: HttpExchange, handler: (HttpExchange, ByteArray) -> Reply) {
        val reply = try {
            handler(exchange, exchange.requestBody.readBytes())
        } catch (e: Throwable) {
            problems += "${exchange.requestURI}: $e"
            Reply(500, e.toString().toByteArray())
        }
        exchange.responseHeaders.add("Content-Type", reply.contentType)
        exchange.sendResponseHeaders(reply.status, reply.body.size.toLong().takeIf { it > 0 } ?: -1)
        if (reply.body.isNotEmpty()) exchange.responseBody.use { it.write(reply.body) }
        exchange.close()
    }

    private fun checkin(exchange: HttpExchange, body: ByteArray): Reply {
        check(exchange.requestHeaders.getFirst("Content-Type") == "application/x-protobuf") { "bad checkin content type" }
        val fields = parseProto(body)
        check(fields.any { it.number == 4 }) { "checkin without the checkin message" }
        val existingId = fields.firstOrNull { it.number == 2 }?.varint
        val call = checkinCalls.incrementAndGet()
        val response = ProtoWriter()
            .bool(1, true)
            .fixed64(7, existingId ?: (ANDROID_ID_BASE + call))
            .fixed64(8, SECURITY_TOKEN)
        return Reply(200, response.toByteArray(), "application/x-protobuf")
    }

    private fun gcmRegister(exchange: HttpExchange, body: ByteArray): Reply {
        val form = String(body).split('&').associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        val device = form.getValue("device")
        check(exchange.requestHeaders.getFirst("Authorization") == "AidLogin $device:${java.lang.Long.toUnsignedString(SECURITY_TOKEN)}") {
            "bad AidLogin header"
        }
        check(form.getValue("X-subtype").startsWith("wp:")) { "bad subtype" }
        check(form.getValue("app").startsWith("org.chromium.")) { "bad app" }
        check(B64.decode(form.getValue("sender")).size == 65) { "sender is not a P-256 key" }
        return Reply(200, "token=GCM_TOKEN_${gcmRegisterCalls.incrementAndGet()}".toByteArray(), "text/plain")
    }

    private fun installations(exchange: HttpExchange, body: ByteArray): Reply {
        check(exchange.requestHeaders.getFirst("x-goog-api-key") == API_KEY) { "missing api key" }
        if (installationStatus != 200) {
            return Reply(installationStatus, """{"error":{"code":$installationStatus,"status":"PERMISSION_DENIED"}}""".toByteArray())
        }
        val json = Json.parseToJsonElement(String(body)).jsonObject
        if (exchange.requestURI.path.endsWith("/authTokens:generate")) {
            check(exchange.requestHeaders.getFirst("Authorization") == "FIS_v2 refresh-token") { "bad FIS refresh header" }
            return Reply(200, """{"token":"fis-auth-renewed","expiresIn":"604800s"}""".toByteArray())
        }
        check(json.getValue("appId").jsonPrimitive.content == APP_ID) { "bad appId" }
        check(json.getValue("authVersion").jsonPrimitive.content == "FIS_v2") { "bad authVersion" }
        val fid = json.getValue("fid").jsonPrimitive.content
        check(fid.length == 22 && fid[0] in "cdef") { "bad fid $fid" }
        installationCalls.incrementAndGet()
        return Reply(
            200,
            """{"name":"projects/1/installations/$fid","fid":"$fid","refreshToken":"refresh-token","authToken":{"token":"fis-auth","expiresIn":"604800s"}}"""
                .toByteArray(),
        )
    }

    private fun fcmRegistrations(exchange: HttpExchange, body: ByteArray): Reply {
        check(exchange.requestHeaders.getFirst("x-goog-api-key") == API_KEY) { "missing api key" }
        val auth = exchange.requestHeaders.getFirst("x-goog-firebase-installations-auth")
        check(auth == "FIS fis-auth" || auth == "FIS fis-auth-renewed") { "bad installations auth header: $auth" }
        val suffix = exchange.requestURI.rawPath.substringAfter("/registrations").removePrefix("/")
        return when (exchange.requestMethod) {
            "DELETE" -> {
                deletedTokens += URLDecoder.decode(suffix, Charsets.UTF_8)
                Reply(200, "{}".toByteArray())
            }
            "PATCH" -> {
                check(suffix.isNotEmpty()) { "PATCH without a token" }
                validateRegistration(body)
                Reply(200, """{"token":"FCM_TOKEN_REFRESHED_${patchCalls.incrementAndGet()}"}""".toByteArray())
            }
            else -> {
                check(suffix.isEmpty()) { "POST with a token" }
                registrations += validateRegistration(body)
                Reply(200, """{"name":"x","token":"FCM_TOKEN_${registrations.size}"}""".toByteArray())
            }
        }
    }

    private fun validateRegistration(body: ByteArray): JsonObject {
        val web = Json.parseToJsonElement(String(body)).jsonObject.getValue("web").jsonObject
        check(web.getValue("endpoint").jsonPrimitive.content.startsWith("https://fcm.googleapis.com/fcm/send/GCM_TOKEN_")) { "bad endpoint" }
        check(B64.decode(web.getValue("p256dh").jsonPrimitive.content).size == 65) { "bad p256dh" }
        check(B64.decode(web.getValue("auth").jsonPrimitive.content).size == 16) { "bad auth" }
        check("applicationPubKey" !in web) { "the default VAPID key must not be sent" }
        return web
    }

    private fun acceptLoop() {
        while (!mcs.isClosed) {
            val socket = try {
                mcs.accept()
            } catch (_: IOException) {
                return
            }
            try {
                val session = McsSession(socket)
                allSessions += session
                if (rejectLogins.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    session.sendLoginResponse(errorCode = 401)
                    session.rejected = true
                } else {
                    session.sendLoginResponse(errorCode = null)
                }
                sessions += session
            } catch (e: Exception) {
                problems += "mcs accept: $e"
                socket.close()
            }
        }
    }

    override fun close() {
        http.stop(0)
        mcs.close()
        allSessions.forEach { it.close() }
    }

    companion object {
        const val PROJECT_ID = "demo-project"
        const val APP_ID = "1:1234567890:web:abcdef"
        const val API_KEY = "test-api-key"
        const val ANDROID_ID_BASE = 4_000_000_000_000_000_000L
        const val SECURITY_TOKEN = -7_000_000_000_000_000_001L // exercises the unsigned handling
        val options = FirebaseDesktopOptions(PROJECT_ID, APP_ID, API_KEY)
    }
}

/** Server side of one MCS connection. */
internal class McsSession(private val socket: Socket) {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream()
    private var versionSent = false
    var rejected = false

    /** Fields of the LoginRequest the client opened the connection with. */
    val login: List<ProtoField>

    init {
        socket.soTimeout = 15_000
        check(input.readUnsignedByte() == 41) { "the client must announce MCS version 41" }
        val (tag, payload) = readFrame()
        check(tag == 2) { "the first frame must be a LoginRequest, got tag $tag" }
        login = parseProto(payload)
    }

    fun loginString(field: Int): String? = login.firstOrNull { it.number == field }?.string
    val receivedPersistentIds: List<String> get() = login.filter { it.number == 10 }.map { it.string }

    fun readFrame(): Pair<Int, ByteArray> {
        val tag = input.readUnsignedByte()
        var size = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            size = size or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return tag to ByteArray(size).also(input::readFully)
    }

    /** True when the client closed the connection before sending another frame. */
    fun awaitClosed(): Boolean = try {
        readFrame()
        false
    } catch (_: EOFException) {
        true
    } catch (_: IOException) {
        true
    }

    @Synchronized
    fun send(tag: Int, payload: ByteArray) {
        val frame = java.io.ByteArrayOutputStream()
        if (!versionSent) {
            frame.write(41)
            versionSent = true
        }
        frame.write(tag)
        var size = payload.size
        while (true) {
            val b = size and 0x7F
            size = size ushr 7
            if (size == 0) {
                frame.write(b)
                break
            }
            frame.write(b or 0x80)
        }
        frame.write(payload)
        output.write(frame.toByteArray())
        output.flush()
    }

    fun sendLoginResponse(errorCode: Int?) {
        val response = ProtoWriter().string(1, "chrome")
        if (errorCode != null) response.message(3, ProtoWriter().int32(1, errorCode).string(2, "Authentication failed"))
        send(3, response.toByteArray())
    }

    fun sendData(persistentId: String, appData: Map<String, String>, rawData: ByteArray?) {
        val stanza = ProtoWriter().string(3, "1234567890").string(5, "org.chromium.linux")
        for ((key, value) in appData) stanza.message(7, ProtoWriter().string(1, key).string(2, value))
        stanza.string(9, persistentId).int32(17, 3600).int64(18, 1_700_000_000_123)
        if (rawData != null) stanza.bytes(21, rawData)
        send(8, stanza.toByteArray())
    }

    /** Reads the next frame and checks it is the selective ack of [persistentId]. */
    fun expectAck(persistentId: String) {
        val (tag, payload) = readFrame()
        check(tag == 7) { "expected an IqStanza, got tag $tag" }
        val extension = parseProto(parseProto(payload).first { it.number == 7 }.requireBytes())
        check(extension.first { it.number == 1 }.int == 12) { "expected a selective ack" }
        val ids = parseProto(extension.first { it.number == 2 }.requireBytes()).map { it.string }
        check(ids == listOf(persistentId)) { "acknowledged $ids instead of $persistentId" }
    }

    fun close() {
        runCatching { socket.close() }
    }
}
