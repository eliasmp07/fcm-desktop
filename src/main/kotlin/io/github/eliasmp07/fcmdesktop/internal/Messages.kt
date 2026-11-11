package io.github.eliasmp07.fcmdesktop.internal

/* Protobuf messages of the check-in API and of MCS (the protocol spoken on mtalk.google.com). */

// MCS frame tags.
internal const val TAG_HEARTBEAT_PING = 0
internal const val TAG_HEARTBEAT_ACK = 1
internal const val TAG_LOGIN_REQUEST = 2
internal const val TAG_LOGIN_RESPONSE = 3
internal const val TAG_CLOSE = 4
internal const val TAG_IQ_STANZA = 7
internal const val TAG_DATA_MESSAGE_STANZA = 8

internal const val MCS_VERSION = 41
private const val SELECTIVE_ACK_EXTENSION_ID = 12

internal enum class ChromePlatform(val id: Int, val gcmApp: String) {
    Windows(1, "org.chromium.windows"),
    Mac(2, "org.chromium.macosx"),
    Linux(3, "org.chromium.linux");

    companion object {
        fun current(): ChromePlatform {
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.contains("win") -> Windows
                os.contains("mac") || os.contains("darwin") -> Mac
                else -> Linux
            }
        }
    }
}

/** AndroidCheckinRequest, identifying ourselves as a Chrome browser like the web SDK's host does. */
internal fun encodeCheckinRequest(
    androidId: Long?,
    securityToken: Long?,
    platform: ChromePlatform,
    chromeVersion: String,
): ByteArray {
    val chromeBuild = ProtoWriter()
        .int32(1, platform.id)
        .string(2, chromeVersion)
        .int32(3, 1) // CHANNEL_STABLE
    val checkin = ProtoWriter()
        .int32(12, 3) // DEVICE_CHROME_BROWSER
        .message(13, chromeBuild)
    val request = ProtoWriter()
    if (androidId != null && securityToken != null) request.int64(2, androidId)
    request.message(4, checkin)
    if (androidId != null && securityToken != null) request.fixed64(13, securityToken)
    request.int32(14, 3) // version
    request.int32(22, 0) // user_serial_number
    return request.toByteArray()
}

internal class CheckinResponse(val androidId: Long, val securityToken: Long)

internal fun decodeCheckinResponse(data: ByteArray): CheckinResponse? {
    var androidId: Long? = null
    var securityToken: Long? = null
    for (field in parseProto(data)) {
        when (field.number) {
            7 -> androidId = field.varint
            8 -> securityToken = field.varint
        }
    }
    if (androidId == null || securityToken == null || androidId == 0L) return null
    return CheckinResponse(androidId, securityToken)
}

internal fun encodeLoginRequest(
    androidId: Long,
    securityToken: Long,
    chromeVersion: String,
    receivedPersistentIds: Collection<String>,
): ByteArray {
    val id = java.lang.Long.toUnsignedString(androidId)
    val writer = ProtoWriter()
        .string(1, "chrome-$chromeVersion")
        .string(2, "mcs.android.com")
        .string(3, id)
        .string(4, id)
        .string(5, java.lang.Long.toUnsignedString(securityToken))
        .string(6, "android-" + java.lang.Long.toHexString(androidId))
        .message(8, ProtoWriter().string(1, "new_vc").string(2, "1"))
    for (persistentId in receivedPersistentIds) writer.string(10, persistentId)
    return writer
        .bool(12, false) // adaptive_heartbeat
        .bool(14, true) // use_rmq2
        .int32(16, 2) // auth_service = ANDROID_ID
        .int32(17, 1) // network_type = wifi
        .toByteArray()
}

internal class LoginResponse(val errorCode: Int?, val errorMessage: String?)

internal fun decodeLoginResponse(data: ByteArray): LoginResponse {
    for (field in parseProto(data)) {
        if (field.number == 3 && field.wireType == WIRE_LEN) {
            var code = 0
            var message: String? = null
            for (inner in parseProto(field.requireBytes())) {
                when (inner.number) {
                    1 -> code = inner.int
                    2 -> message = inner.string
                }
            }
            return LoginResponse(code, message)
        }
    }
    return LoginResponse(null, null)
}

/** HeartbeatPing and HeartbeatAck share the same layout. */
internal fun encodeHeartbeat(lastStreamIdReceived: Int?): ByteArray {
    val writer = ProtoWriter()
    if (lastStreamIdReceived != null) writer.int32(2, lastStreamIdReceived)
    return writer.toByteArray()
}

/** IqStanza carrying a SelectiveAck: tells the server which messages were delivered. */
internal fun encodeSelectiveAck(persistentIds: Collection<String>): ByteArray {
    val ack = ProtoWriter()
    for (persistentId in persistentIds) ack.string(1, persistentId)
    val extension = ProtoWriter()
        .int32(1, SELECTIVE_ACK_EXTENSION_ID)
        .bytes(2, ack.toByteArray())
    return ProtoWriter()
        .int32(2, 1) // type = SET
        .string(3, "")
        .message(7, extension)
        .toByteArray()
}

internal class DataMessage(
    val from: String?,
    val category: String?,
    val appData: Map<String, String>,
    val persistentId: String?,
    val ttl: Int,
    val sent: Long,
    val rawData: ByteArray?,
)

internal fun decodeDataMessage(data: ByteArray): DataMessage {
    var from: String? = null
    var category: String? = null
    val appData = LinkedHashMap<String, String>()
    var persistentId: String? = null
    var ttl = 0
    var sent = 0L
    var rawData: ByteArray? = null
    for (field in parseProto(data)) {
        when (field.number) {
            3 -> from = field.string
            5 -> category = field.string
            7 -> {
                var key: String? = null
                var value: String? = null
                for (inner in parseProto(field.requireBytes())) {
                    when (inner.number) {
                        1 -> key = inner.string
                        2 -> value = inner.string
                    }
                }
                if (key != null && value != null) appData[key] = value
            }
            9 -> persistentId = field.string
            17 -> ttl = field.int
            18 -> sent = field.varint
            21 -> rawData = field.requireBytes()
        }
    }
    return DataMessage(from, category, appData, persistentId, ttl, sent, rawData)
}
