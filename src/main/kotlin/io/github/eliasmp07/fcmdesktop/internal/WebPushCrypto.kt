package io.github.eliasmp07.fcmdesktop.internal

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Key material of a Web Push subscription. */
internal class PushKeys(
    /** PKCS#8 encoded P-256 private key. */
    val privateKey: ByteArray,
    /** Uncompressed P-256 point (65 bytes): the `p256dh` value. */
    val publicKey: ByteArray,
    /** 16 random bytes: the `auth` value. */
    val authSecret: ByteArray,
)

/**
 * Web Push message encryption (receiver side), using only the JDK.
 * Supports both encodings FCM may use: the legacy `aesgcm` (keys travel in the `crypto-key` and
 * `encryption` headers) and `aes128gcm` (RFC 8291, keys travel in the payload header).
 */
internal object WebPushCrypto {
    private const val TAG_BYTES = 16
    private const val KEY_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val POINT_BYTES = 65

    private val curve: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    }

    fun generateKeys(random: SecureRandom = SecureRandom()): PushKeys {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), random)
        val pair = generator.generateKeyPair()
        val auth = ByteArray(16).also(random::nextBytes)
        return PushKeys(pair.private.encoded, encodePoint((pair.public as ECPublicKey).w), auth)
    }

    /** Legacy `aesgcm` content encoding (draft-ietf-webpush-encryption-04). */
    fun decryptAesGcm(keys: PushKeys, senderPublicKey: ByteArray, salt: ByteArray, payload: ByteArray, recordSize: Int = 4096): ByteArray {
        val secret = sharedSecret(keys, senderPublicKey)
        val prk = hkdf(keys.authSecret, secret, "Content-Encoding: auth\u0000".toByteArray(), 32)
        val context = "P-256\u0000".toByteArray() + lengthPrefixed(keys.publicKey) + lengthPrefixed(senderPublicKey)
        val key = hkdf(salt, prk, "Content-Encoding: aesgcm\u0000".toByteArray() + context, KEY_BYTES)
        val nonce = hkdf(salt, prk, "Content-Encoding: nonce\u0000".toByteArray() + context, NONCE_BYTES)

        val out = java.io.ByteArrayOutputStream()
        forEachRecord(payload, recordSize + TAG_BYTES) { index, record, _ ->
            val plain = decryptRecord(key, nonce, index, record)
            if (plain.size < 2) throw GeneralSecurityException("aesgcm record too short")
            val padding = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
            if (2 + padding > plain.size) throw GeneralSecurityException("aesgcm padding exceeds record")
            out.write(plain, 2 + padding, plain.size - 2 - padding)
        }
        return out.toByteArray()
    }

    /** `aes128gcm` content encoding (RFC 8188 + RFC 8291). */
    fun decryptAes128Gcm(keys: PushKeys, payload: ByteArray): ByteArray {
        if (payload.size < 21) throw GeneralSecurityException("aes128gcm header too short")
        val salt = payload.copyOfRange(0, 16)
        val recordSize = ((payload[16].toLong() and 0xFF) shl 24) or ((payload[17].toLong() and 0xFF) shl 16) or
            ((payload[18].toLong() and 0xFF) shl 8) or (payload[19].toLong() and 0xFF)
        val idLength = payload[20].toInt() and 0xFF
        if (idLength != POINT_BYTES || payload.size < 21 + idLength) {
            throw GeneralSecurityException("aes128gcm header has no sender key")
        }
        if (recordSize < TAG_BYTES + 2 || recordSize > Int.MAX_VALUE) throw GeneralSecurityException("bad aes128gcm record size")
        val senderPublicKey = payload.copyOfRange(21, 21 + idLength)
        val body = payload.copyOfRange(21 + idLength, payload.size)

        val secret = sharedSecret(keys, senderPublicKey)
        val ikm = hkdf(keys.authSecret, secret, "WebPush: info\u0000".toByteArray() + keys.publicKey + senderPublicKey, 32)
        val key = hkdf(salt, ikm, "Content-Encoding: aes128gcm\u0000".toByteArray(), KEY_BYTES)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce\u0000".toByteArray(), NONCE_BYTES)

        val out = java.io.ByteArrayOutputStream()
        forEachRecord(body, recordSize.toInt()) { index, record, last ->
            val plain = decryptRecord(key, nonce, index, record)
            var end = plain.size - 1
            while (end >= 0 && plain[end].toInt() == 0) end--
            if (end < 0) throw GeneralSecurityException("aes128gcm record has no delimiter")
            val delimiter = plain[end].toInt()
            if (delimiter != (if (last) 2 else 1)) throw GeneralSecurityException("bad aes128gcm delimiter")
            out.write(plain, 0, end)
        }
        return out.toByteArray()
    }

    private inline fun forEachRecord(data: ByteArray, size: Int, block: (index: Long, record: ByteArray, last: Boolean) -> Unit) {
        if (data.isEmpty()) throw GeneralSecurityException("empty encrypted payload")
        var offset = 0
        var index = 0L
        while (offset < data.size) {
            val end = minOf(offset + size, data.size)
            block(index++, data.copyOfRange(offset, end), end == data.size)
            offset = end
        }
    }

    private fun decryptRecord(key: ByteArray, baseNonce: ByteArray, index: Long, record: ByteArray): ByteArray {
        val nonce = baseNonce.copyOf()
        for (i in 0 until 8) {
            val position = NONCE_BYTES - 1 - i
            nonce[position] = (nonce[position].toInt() xor (index ushr (8 * i)).toInt()).toByte()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BYTES * 8, nonce))
        return cipher.doFinal(record)
    }

    private fun sharedSecret(keys: PushKeys, senderPublicKey: ByteArray): ByteArray {
        val privateKey: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keys.privateKey))
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(decodePoint(senderPublicKey), curve))
        return KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
    }

    internal fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length <= 32)
        val prk = hmac(salt, ikm)
        return hmac(prk, info + byteArrayOf(1)).copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun lengthPrefixed(data: ByteArray): ByteArray =
        byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()) + data

    private fun encodePoint(point: ECPoint): ByteArray =
        byteArrayOf(4) + fixed32(point.affineX) + fixed32(point.affineY)

    private fun fixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    private fun decodePoint(encoded: ByteArray): ECPoint {
        if (encoded.size != POINT_BYTES || encoded[0].toInt() != 4) {
            throw GeneralSecurityException("sender key is not an uncompressed P-256 point")
        }
        val x = BigInteger(1, encoded.copyOfRange(1, 33))
        val y = BigInteger(1, encoded.copyOfRange(33, 65))
        // Reject points that are not on the curve before using them in ECDH.
        val p = (curve.curve.field as java.security.spec.ECFieldFp).p
        val right = (x.pow(3) + curve.curve.a * x + curve.curve.b).mod(p)
        if (x >= p || y >= p || y.pow(2).mod(p) != right) {
            throw GeneralSecurityException("sender key is not on the P-256 curve")
        }
        return ECPoint(x, y)
    }
}

internal object B64 {
    fun urlEncode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** Accepts both alphabets, with or without padding. */
    fun decode(text: String): ByteArray =
        Base64.getUrlDecoder().decode(text.trim().replace('+', '-').replace('/', '_').trimEnd('='))
}
