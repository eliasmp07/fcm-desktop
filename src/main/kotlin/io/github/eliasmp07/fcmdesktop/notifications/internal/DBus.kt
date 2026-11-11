package io.github.eliasmp07.fcmdesktop.notifications.internal

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal D-Bus client: just enough of the wire protocol to call methods with simple arguments
 * and receive signals on the session bus. It needs Unix domain sockets, available since JDK 16.
 *
 * Supported argument types: String (s), Int (i), UInt via [U32] (u), Boolean (b),
 * List<String> (as) and Map<String, Any> with String/Boolean values (a{sv}).
 */
internal class DBusConnection private constructor(private val channel: SocketChannel) : AutoCloseable {

    /** An unsigned 32-bit argument. */
    class U32(val value: Int)

    class Message(
        val type: Int,
        val serial: Int,
        val replySerial: Int?,
        val interfaceName: String?,
        val member: String?,
        val errorName: String?,
        val signature: String,
        val body: List<Any>,
    )

    class DBusException(message: String) : IOException(message)

    private val serials = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Message>>()
    private val writeLock = Any()

    @Volatile
    private var signalHandler: (Message) -> Unit = {}

    @Volatile
    var isOpen: Boolean = true
        private set

    private fun start() {
        Thread({ readLoop() }, "fcm-desktop-dbus").apply { isDaemon = true }.start()
        call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello")
    }

    fun onSignal(handler: (Message) -> Unit) {
        signalHandler = handler
    }

    /** Subscribes to broadcast signals matching [rule]. */
    fun addMatch(rule: String) {
        call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "AddMatch", rule)
    }

    /** Calls a method and returns the reply body. Throws [DBusException] on a D-Bus error or timeout. */
    fun call(destination: String, path: String, interfaceName: String, member: String, vararg args: Any): List<Any> {
        val serial = serials.incrementAndGet()
        val future = CompletableFuture<Message>()
        pending[serial] = future
        try {
            val bytes = encodeCall(serial, destination, path, interfaceName, member, args.toList())
            synchronized(writeLock) {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
            }
            val reply = try {
                future.get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                throw DBusException("$member timed out")
            } catch (e: java.util.concurrent.ExecutionException) {
                throw DBusException("$member failed: ${e.cause?.message}")
            }
            if (reply.type == TYPE_ERROR) {
                throw DBusException("${reply.errorName}: ${reply.body.firstOrNull() ?: ""}")
            }
            return reply.body
        } finally {
            pending.remove(serial)
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val message = readMessage()
                when (message.type) {
                    TYPE_RETURN, TYPE_ERROR -> message.replySerial?.let { pending[it]?.complete(message) }
                    TYPE_SIGNAL -> try {
                        signalHandler(message)
                    } catch (_: Exception) {
                        // a failing handler must not kill the connection
                    }
                }
            }
        } catch (e: Exception) {
            isOpen = false
            pending.values.forEach { it.completeExceptionally(e) }
            runCatching { channel.close() }
        }
    }

    private fun readFully(size: Int): ByteArray {
        if (size < 0 || size > MAX_MESSAGE_BYTES) throw IOException("D-Bus message of $size bytes is too large")
        val buffer = ByteBuffer.allocate(size)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) throw EOFException("D-Bus connection closed")
        }
        return buffer.array()
    }

    private fun readMessage(): Message {
        val fixed = readFully(16)
        val order = if (fixed[0] == 'B'.code.toByte()) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val head = ByteBuffer.wrap(fixed).order(order)
        val type = fixed[1].toInt()
        val bodyLength = head.getInt(4)
        val serial = head.getInt(8)
        val fieldsLength = head.getInt(12)
        val fieldsPadded = (fieldsLength + 7) / 8 * 8
        val fieldBytes = readFully(fieldsPadded)
        val body = readFully(bodyLength)

        var replySerial: Int? = null
        var interfaceName: String? = null
        var member: String? = null
        var errorName: String? = null
        var signature = ""
        // Offsets inside the field array keep the alignment of the whole message, because it starts at byte 16.
        val fields = Reader(ByteBuffer.wrap(fieldBytes, 0, fieldsLength).order(order))
        while (fields.hasRemaining()) {
            fields.align(8)
            if (!fields.hasRemaining()) break
            val code = fields.byte()
            when (val value = fields.variant()) {
                is String -> when (code) {
                    2 -> interfaceName = value
                    3 -> member = value
                    4 -> errorName = value
                    8 -> signature = value
                }
                is Int -> if (code == 5) replySerial = value
            }
        }
        val reader = Reader(ByteBuffer.wrap(body).order(order))
        val values = try {
            reader.values(signature)
        } catch (_: Exception) {
            emptyList() // a body with types this client does not model
        }
        return Message(type, serial, replySerial, interfaceName, member, errorName, signature, values)
    }

    override fun close() {
        isOpen = false
        runCatching { channel.close() }
    }

    /** Reads the subset of types this client understands. */
    private class Reader(private val buffer: ByteBuffer) {
        fun hasRemaining() = buffer.hasRemaining()

        fun align(to: Int) {
            val padding = (to - buffer.position() % to) % to
            buffer.position(minOf(buffer.limit(), buffer.position() + padding))
        }

        fun byte(): Int = buffer.get().toInt() and 0xFF

        fun int(): Int {
            align(4)
            return buffer.getInt()
        }

        fun string(): String {
            val length = int()
            val bytes = ByteArray(length).also(buffer::get)
            buffer.get() // NUL
            return String(bytes, Charsets.UTF_8)
        }

        fun signature(): String {
            val bytes = ByteArray(byte()).also(buffer::get)
            buffer.get() // NUL
            return String(bytes, Charsets.US_ASCII)
        }

        fun variant(): Any? {
            val signature = signature()
            return values(signature).firstOrNull()
        }

        fun values(signature: String): List<Any> {
            val result = ArrayList<Any>()
            var index = 0
            while (index < signature.length) {
                when (signature[index]) {
                    's', 'o' -> result += string()
                    'g' -> result += signature()
                    'u', 'i' -> result += int()
                    'b' -> result += (int() != 0)
                    'y' -> result += byte()
                    'a' -> {
                        if (signature.getOrNull(index + 1) != 's') throw IOException("unsupported array type")
                        index++
                        val length = int()
                        val end = buffer.position() + length
                        val items = ArrayList<String>()
                        while (buffer.position() < end) items += string()
                        result.add(items)
                    }
                    else -> throw IOException("unsupported type ${signature[index]}")
                }
                index++
            }
            return result
        }
    }

    /** Writes values with the alignment rules of the D-Bus wire format (little endian). */
    private class Writer {
        val out = ByteArrayOutputStream()

        fun align(to: Int) {
            while (out.size() % to != 0) out.write(0)
        }

        fun byte(value: Int) = out.write(value)

        fun int(value: Int) {
            align(4)
            for (i in 0 until 4) out.write(value ushr (8 * i))
        }

        fun string(value: String) {
            val bytes = value.replace("\u0000", "").toByteArray(Charsets.UTF_8)
            int(bytes.size)
            out.write(bytes)
            out.write(0)
        }

        fun signature(value: String) {
            val bytes = value.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size)
            out.write(bytes)
            out.write(0)
        }

        /** Writes an array: length placeholder, padding to [alignment], then the elements. */
        inline fun array(alignment: Int, elements: () -> Unit) {
            int(0)
            val lengthAt = out.size() - 4
            align(alignment)
            val start = out.size()
            elements()
            val length = out.size() - start
            val bytes = out.toByteArray()
            for (i in 0 until 4) bytes[lengthAt + i] = (length ushr (8 * i)).toByte()
            out.reset()
            out.write(bytes)
        }

        fun value(value: Any) {
            when (value) {
                is String -> string(value)
                is Int -> int(value)
                is U32 -> int(value.value)
                is Boolean -> int(if (value) 1 else 0)
                is List<*> -> array(4) { value.forEach { string(it as String) } }
                is Map<*, *> -> array(8) {
                    for ((key, item) in value) {
                        align(8)
                        string(key as String)
                        signature(signatureOf(item!!))
                        value(item)
                    }
                }
                else -> throw IllegalArgumentException("unsupported D-Bus argument ${value.javaClass}")
            }
        }
    }

    companion object {
        private const val TYPE_CALL = 1
        const val TYPE_RETURN = 2
        const val TYPE_ERROR = 3
        const val TYPE_SIGNAL = 4
        private const val CALL_TIMEOUT_SECONDS = 5L
        private const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024

        private fun signatureOf(value: Any): String = when (value) {
            is String -> "s"
            is Int -> "i"
            is U32 -> "u"
            is Boolean -> "b"
            is List<*> -> "as"
            is Map<*, *> -> "a{sv}"
            else -> throw IllegalArgumentException("unsupported D-Bus argument ${value.javaClass}")
        }

        internal fun encodeCall(serial: Int, destination: String, path: String, interfaceName: String, member: String, args: List<Any>): ByteArray {
            val body = Writer()
            args.forEach(body::value)
            val bodyBytes = body.out.toByteArray()

            val message = Writer()
            message.byte('l'.code)
            message.byte(TYPE_CALL)
            message.byte(0) // flags
            message.byte(1) // protocol version
            message.int(bodyBytes.size)
            message.int(serial)
            message.array(8) {
                fun field(code: Int, signature: String, value: String, isSignature: Boolean = false) {
                    message.align(8)
                    message.byte(code)
                    message.signature(signature)
                    if (isSignature) message.signature(value) else message.string(value)
                }
                field(1, "o", path)
                field(2, "s", interfaceName)
                field(3, "s", member)
                field(6, "s", destination)
                if (args.isNotEmpty()) field(8, "g", args.joinToString("") { signatureOf(it) }, isSignature = true)
            }
            message.align(8)
            message.out.write(bodyBytes)
            return message.out.toByteArray()
        }

        /** Connects to the session bus, or throws when there is none or the JVM cannot open Unix sockets. */
        fun connectToSessionBus(): DBusConnection {
            val path = sessionBusPath() ?: throw IOException("no D-Bus session bus found")
            val channel = openUnixSocket(path)
            try {
                authenticate(channel)
                return DBusConnection(channel).also { it.start() }
            } catch (e: Exception) {
                runCatching { channel.close() }
                throw e
            }
        }

        private fun sessionBusPath(): String? {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS").orEmpty()
            for (candidate in address.split(';')) {
                if (!candidate.startsWith("unix:")) continue
                val path = candidate.removePrefix("unix:").split(',')
                    .firstOrNull { it.startsWith("path=") }?.removePrefix("path=")
                if (path != null) return path
            }
            val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }
            val fallback = runtimeDir?.let { Path.of(it, "bus") }
            return fallback?.takeIf { Files.exists(it) }?.toString()
        }

        /** Unix domain sockets arrived in JDK 16; reflection keeps the library loadable on JDK 11. */
        private fun openUnixSocket(path: String): SocketChannel {
            try {
                val address = Class.forName("java.net.UnixDomainSocketAddress")
                    .getMethod("of", String::class.java).invoke(null, path) as SocketAddress
                val family = java.net.StandardProtocolFamily.valueOf("UNIX")
                val channel = SocketChannel::class.java.getMethod("open", java.net.ProtocolFamily::class.java)
                    .invoke(null, family) as SocketChannel
                channel.connect(address)
                return channel
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw IOException("could not connect to the D-Bus session bus", e.cause)
            } catch (e: ReflectiveOperationException) {
                throw IOException("Unix domain sockets need JDK 16 or newer", e)
            } catch (e: IllegalArgumentException) {
                throw IOException("Unix domain sockets need JDK 16 or newer", e)
            }
        }

        /** SASL EXTERNAL: the bus checks the uid of the socket peer against the one we claim. */
        private fun authenticate(channel: SocketChannel) {
            val uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid").toString()
            val hexUid = uid.toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            write(channel, byteArrayOf(0))
            write(channel, "AUTH EXTERNAL $hexUid\r\n".toByteArray(Charsets.US_ASCII))
            val reply = readLine(channel)
            if (!reply.startsWith("OK")) throw IOException("D-Bus authentication was rejected")
            write(channel, "BEGIN\r\n".toByteArray(Charsets.US_ASCII))
        }

        private fun write(channel: SocketChannel, bytes: ByteArray) {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }

        private fun readLine(channel: SocketChannel): String {
            val line = StringBuilder()
            val one = ByteBuffer.allocate(1)
            while (line.length < 512) {
                one.clear()
                if (channel.read(one) < 0) throw EOFException("D-Bus closed the connection during authentication")
                val c = one.get(0).toInt().toChar()
                if (c == '\n') return line.toString().trimEnd('\r')
                line.append(c)
            }
            throw IOException("D-Bus authentication reply is too long")
        }
    }
}
