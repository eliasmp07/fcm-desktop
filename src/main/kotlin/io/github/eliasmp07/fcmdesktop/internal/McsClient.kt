package io.github.eliasmp07.fcmdesktop.internal

import io.github.eliasmp07.fcmdesktop.FcmDesktopConfig
import io.github.eliasmp07.fcmdesktop.FcmLogger
import io.github.eliasmp07.fcmdesktop.LogLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opens the socket to the MCS endpoint. Replaced by a loopback socket in tests. */
internal fun interface McsTransport {
    @Throws(IOException::class)
    fun connect(): Socket

    companion object {
        private const val HOST = "mtalk.google.com"

        /** TLS to mtalk.google.com, on 5228 and, when a firewall blocks it, on 443 like Chrome does. */
        val Default = McsTransport {
            var failure: IOException? = null
            for (port in intArrayOf(5228, 443)) {
                try {
                    return@McsTransport open(port)
                } catch (e: IOException) {
                    failure?.let(e::addSuppressed)
                    failure = e
                }
            }
            throw failure ?: IOException("could not connect to $HOST")
        }

        private fun open(port: Int): Socket {
            val plain = Socket()
            try {
                plain.connect(InetSocketAddress(HOST, port), 15_000)
                plain.tcpNoDelay = true
                plain.keepAlive = true
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val socket = factory.createSocket(plain, HOST, port, true) as SSLSocket
                // SSLSocket does not verify the host name unless asked to.
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.soTimeout = 30_000
                socket.startHandshake()
                socket.soTimeout = 0
                return socket
            } catch (e: IOException) {
                runCatching { plain.close() }
                throw e
            }
        }
    }
}

/** The server refused the device credentials: a new registration is needed. */
internal class McsLoginException(message: String) : IOException(message)

/**
 * One MCS session: connect, log in, then deliver pushes until the connection drops.
 * [run] never returns normally; it ends with an [IOException] (reconnect) or by cancellation.
 */
internal class McsClient(
    private val config: FcmDesktopConfig,
    private val logger: FcmLogger,
    private val transport: McsTransport = McsTransport.Default,
    private val loginTimeoutMillis: Long = 30_000,
) {
    suspend fun run(
        androidId: Long,
        securityToken: Long,
        receivedPersistentIds: Collection<String>,
        onLoggedIn: () -> Unit,
        onMessage: suspend (DataMessage) -> Unit,
    ): Nothing = coroutineScope {
        val socket = runInterruptible(Dispatchers.IO) { transport.connect() }
        // A blocked socket read ignores coroutine cancellation: closing the socket is what unblocks it.
        launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                runCatching { socket.close() }
            }
        }
        val session = Session(socket, onLoggedIn, onMessage)
        try {
            session.login(androidId, securityToken, receivedPersistentIds)
            // These live in the same scope as the socket watcher above: when one of them fails, the
            // scope is cancelled, the watcher closes the socket and the blocked read below returns.
            launch { session.loginTimeout() }
            launch { session.heartbeats() }
            session.readLoop()
        } finally {
            runCatching { socket.close() }
            coroutineContext.cancelChildren()
        }
    }

    private inner class Session(
        socket: Socket,
        private val onLoggedIn: () -> Unit,
        private val onMessage: suspend (DataMessage) -> Unit,
    ) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output: OutputStream = socket.getOutputStream()
        private val writeLock = Any()

        @Volatile private var versionSent = false
        @Volatile private var loggedIn = false
        @Volatile private var lastReceivedNanos = System.nanoTime()
        @Volatile private var inputStreamId = 0
        @Volatile private var reportedStreamId = 0

        suspend fun login(androidId: Long, securityToken: Long, receivedPersistentIds: Collection<String>) {
            withContext(Dispatchers.IO) {
                write(TAG_LOGIN_REQUEST, encodeLoginRequest(androidId, securityToken, Registrar.CHROME_VERSION, receivedPersistentIds))
            }
        }

        suspend fun loginTimeout() {
            delay(loginTimeoutMillis)
            if (!loggedIn) throw IOException("MCS login timed out")
        }

        suspend fun readLoop(): Nothing = withContext(Dispatchers.IO) {
            val version = input.readUnsignedByte()
            if (version < MCS_VERSION && version != 38) throw IOException("unsupported MCS version $version")
            while (true) {
                val tag = input.readUnsignedByte()
                val size = readVarint32()
                if (size < 0 || size > MAX_FRAME_BYTES) throw IOException("MCS frame of $size bytes is too large")
                val payload = ByteArray(size).also(input::readFully)
                lastReceivedNanos = System.nanoTime()
                inputStreamId++
                handle(tag, payload)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("unreachable")
        }

        private suspend fun handle(tag: Int, payload: ByteArray) {
            when (tag) {
                TAG_LOGIN_RESPONSE -> {
                    val response = decodeLoginResponse(payload)
                    if (response.errorCode != null) {
                        throw McsLoginException("MCS login rejected (${response.errorCode}): ${response.errorMessage.orEmpty()}")
                    }
                    loggedIn = true
                    logger.log(LogLevel.Info, "Connected to FCM", null)
                    onLoggedIn()
                }
                TAG_DATA_MESSAGE_STANZA -> {
                    val message = decodeDataMessage(payload)
                    logger.log(LogLevel.Debug, "MCS data message from ${message.from} (${message.rawData?.size ?: 0} bytes, keys=${message.appData.keys})", null)
                    try {
                        onMessage(message)
                    } finally {
                        // Always acknowledge: a message we cannot process must not be redelivered forever.
                        message.persistentId?.let { write(TAG_IQ_STANZA, encodeSelectiveAck(listOf(it))) }
                    }
                }
                TAG_HEARTBEAT_PING -> write(TAG_HEARTBEAT_ACK, encodeHeartbeat(pendingStreamId()))
                TAG_CLOSE -> throw IOException("MCS server closed the connection")
                TAG_HEARTBEAT_ACK, TAG_IQ_STANZA -> logger.log(LogLevel.Debug, "MCS frame tag $tag", null)
                else -> logger.log(LogLevel.Debug, "Ignoring MCS frame with tag $tag", null)
            }
        }

        suspend fun heartbeats() {
            val interval = config.heartbeatIntervalMillis
            while (true) {
                delay(interval)
                if (idleMillis() < interval) continue
                withContext(Dispatchers.IO) { write(TAG_HEARTBEAT_PING, encodeHeartbeat(pendingStreamId())) }
                delay(config.heartbeatAckTimeoutMillis)
                if (idleMillis() >= interval + config.heartbeatAckTimeoutMillis) {
                    throw IOException("MCS heartbeat timed out")
                }
            }
        }

        private fun idleMillis(): Long = (System.nanoTime() - lastReceivedNanos) / 1_000_000

        /** Number of frames received, reported to the server once per change. */
        private fun pendingStreamId(): Int? = synchronized(writeLock) {
            val current = inputStreamId
            if (current == reportedStreamId) null else current.also { reportedStreamId = it }
        }

        private fun write(tag: Int, payload: ByteArray) {
            synchronized(writeLock) {
                val header = java.io.ByteArrayOutputStream(payload.size + 8)
                if (!versionSent) {
                    header.write(MCS_VERSION)
                    versionSent = true
                }
                header.write(tag)
                var size = payload.size
                while (true) {
                    val b = size and 0x7F
                    size = size ushr 7
                    if (size == 0) {
                        header.write(b)
                        break
                    }
                    header.write(b or 0x80)
                }
                header.write(payload)
                output.write(header.toByteArray())
                output.flush()
            }
        }

        private fun readVarint32(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = input.readUnsignedByte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 28) throw IOException("malformed MCS frame length")
            }
        }
    }

    private companion object {
        const val MAX_FRAME_BYTES = 1 shl 20
    }
}
