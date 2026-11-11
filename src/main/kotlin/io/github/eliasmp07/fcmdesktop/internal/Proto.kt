package io.github.eliasmp07.fcmdesktop.internal

import java.io.ByteArrayOutputStream
import java.io.IOException

/*
 * Minimal protobuf (proto2) wire codec. The handful of messages FCM needs are encoded by hand so the
 * library does not pull protobuf-java into the host app.
 */

internal const val WIRE_VARINT = 0
internal const val WIRE_FIXED64 = 1
internal const val WIRE_LEN = 2
internal const val WIRE_FIXED32 = 5

internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun int64(field: Int, value: Long): ProtoWriter = apply {
        tag(field, WIRE_VARINT)
        rawVarint(value)
    }

    /** Negative int32 values are sign-extended to 64 bits, as protobuf requires. */
    fun int32(field: Int, value: Int): ProtoWriter = int64(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = int64(field, if (value) 1 else 0)

    fun fixed64(field: Int, value: Long): ProtoWriter = apply {
        tag(field, WIRE_FIXED64)
        for (i in 0 until 8) out.write((value ushr (8 * i)).toInt() and 0xFF)
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, WIRE_LEN)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(field: Int, value: ProtoWriter): ProtoWriter = bytes(field, value.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }
}

internal class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?) {
    val string: String get() = String(requireBytes(), Charsets.UTF_8)
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L

    fun requireBytes(): ByteArray =
        bytes ?: throw IOException("protobuf field $number is not length-delimited")
}

internal fun parseProto(data: ByteArray): List<ProtoField> {
    val fields = ArrayList<ProtoField>()
    var pos = 0

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= data.size) throw IOException("truncated protobuf varint")
            val b = data[pos++].toInt() and 0xFF
            if (shift < 64) result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 70) throw IOException("malformed protobuf varint")
        }
    }

    fun readFixed(size: Int): Long {
        if (pos + size > data.size) throw IOException("truncated protobuf fixed field")
        var result = 0L
        for (i in 0 until size) result = result or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += size
        return result
    }

    while (pos < data.size) {
        val key = readVarint()
        val number = (key ushr 3).toInt()
        when (val wireType = (key and 7).toInt()) {
            WIRE_VARINT -> fields += ProtoField(number, wireType, readVarint(), null)
            WIRE_FIXED64 -> fields += ProtoField(number, wireType, readFixed(8), null)
            WIRE_FIXED32 -> fields += ProtoField(number, wireType, readFixed(4), null)
            WIRE_LEN -> {
                val length = readVarint()
                if (length < 0 || length > data.size - pos) throw IOException("truncated protobuf bytes field")
                fields += ProtoField(number, wireType, 0, data.copyOfRange(pos, pos + length.toInt()))
                pos += length.toInt()
            }
            else -> throw IOException("unsupported protobuf wire type $wireType")
        }
    }
    return fields
}
