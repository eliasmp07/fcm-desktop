package io.github.eliasmp07.fcmdesktop

import io.github.eliasmp07.fcmdesktop.internal.ChromePlatform
import io.github.eliasmp07.fcmdesktop.internal.ProtoWriter
import io.github.eliasmp07.fcmdesktop.internal.decodeCheckinResponse
import io.github.eliasmp07.fcmdesktop.internal.decodeDataMessage
import io.github.eliasmp07.fcmdesktop.internal.decodeLoginResponse
import io.github.eliasmp07.fcmdesktop.internal.encodeCheckinRequest
import io.github.eliasmp07.fcmdesktop.internal.encodeHeartbeat
import io.github.eliasmp07.fcmdesktop.internal.encodeLoginRequest
import io.github.eliasmp07.fcmdesktop.internal.encodeSelectiveAck
import io.github.eliasmp07.fcmdesktop.internal.parseProto
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The hand-written codec must produce exactly the bytes protobuf produces for the reference protos. */
class ProtoCodecTest {

    @Test
    fun checkinRequestMatchesReference() {
        assertContentEquals(
            Fixtures.proto("checkinRequestNew"),
            encodeCheckinRequest(null, null, ChromePlatform.Linux, Fixtures.chromeVersion),
        )
        assertContentEquals(
            Fixtures.proto("checkinRequestExisting"),
            encodeCheckinRequest(Fixtures.androidId, Fixtures.securityToken, ChromePlatform.Linux, Fixtures.chromeVersion),
        )
    }

    @Test
    fun loginRequestMatchesReference() {
        assertContentEquals(
            Fixtures.proto("loginRequest"),
            encodeLoginRequest(Fixtures.androidId, Fixtures.securityToken, Fixtures.chromeVersion, listOf("0:111%abc", "0:222%def")),
        )
    }

    @Test
    fun acknowledgementsMatchReference() {
        assertContentEquals(Fixtures.proto("selectiveAck"), encodeSelectiveAck(listOf("0:111%abc")))
        assertContentEquals(Fixtures.proto("heartbeatEmpty"), encodeHeartbeat(null))
        assertContentEquals(Fixtures.proto("heartbeatWithStreamId"), encodeHeartbeat(7))
    }

    @Test
    fun decodesCheckinResponseWithUnsignedIds() {
        val response = assertNotNull(decodeCheckinResponse(Fixtures.proto("checkinResponse")))
        assertEquals(Fixtures.androidId, response.androidId)
        // The token does not fit in a signed long: it must survive as the same 64 bits.
        assertEquals("17654321098765432109", java.lang.Long.toUnsignedString(response.securityToken))
        assertNull(decodeCheckinResponse(ByteArray(0)))
    }

    @Test
    fun decodesLoginResponses() {
        assertNull(decodeLoginResponse(Fixtures.proto("loginResponseOk")).errorCode)
        val rejected = decodeLoginResponse(Fixtures.proto("loginResponseError"))
        assertEquals(401, rejected.errorCode)
        assertEquals("Authentication failed", rejected.errorMessage)
    }

    @Test
    fun decodesDataMessage() {
        val message = decodeDataMessage(Fixtures.proto("dataMessage"))
        assertEquals("1234567890", message.from)
        assertEquals("org.chromium.linux", message.category)
        assertEquals(mapOf("subtype" to "wp:app#uuid", "crypto-key" to "dh=AAAA", "encryption" to "salt=BBBB"), message.appData)
        assertEquals("0:1700000000000000%abcdef", message.persistentId)
        assertEquals(2419200, message.ttl)
        assertEquals(1700000000123, message.sent)
        assertContentEquals(byteArrayOf(0, 1, 2, -1), message.rawData)
    }

    @Test
    fun negativeInt32IsSignExtended() {
        val encoded = ProtoWriter().int32(1, -1).toByteArray()
        assertEquals(11, encoded.size) // tag + 10 byte varint
        assertEquals(-1, parseProto(encoded).single().int)
    }

    @Test
    fun rejectsTruncatedInput() {
        val message = Fixtures.proto("dataMessage")
        assertFailsWith<IOException> { parseProto(message.copyOf(message.size - 4)) }
        assertFailsWith<IOException> { parseProto(byteArrayOf(0x0A, 0x7F)) } // announces 127 bytes, has none
        assertFailsWith<IOException> { parseProto(byteArrayOf(0x0B)) } // wire type 3 (groups) is not supported
    }
}
