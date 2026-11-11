package io.github.eliasmp07.fcmdesktop

import io.github.eliasmp07.fcmdesktop.internal.B64
import io.github.eliasmp07.fcmdesktop.internal.PushKeys
import io.github.eliasmp07.fcmdesktop.internal.WebPushCrypto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bytes produced by the Python reference implementations (see tools/generate_fixtures.py). */
internal object Fixtures {
    private val root: JsonObject by lazy {
        val text = checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures.json")) { "fixtures.json not found" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    val androidId: Long get() = java.lang.Long.parseUnsignedLong(root.getValue("androidId").jsonPrimitive.content)
    val securityToken: Long get() = java.lang.Long.parseUnsignedLong(root.getValue("securityToken").jsonPrimitive.content)
    val chromeVersion: String get() = root.getValue("chromeVersion").jsonPrimitive.content

    fun proto(name: String): ByteArray = B64.decode(root.getValue("proto").jsonObject.getValue(name).jsonPrimitive.content)

    class CryptoCase(private val json: JsonObject) {
        private fun bytes(name: String) = B64.decode(json.getValue(name).jsonPrimitive.content)
        val version: String = json.getValue("version").jsonPrimitive.content
        val recordSize: Int = json.getValue("recordSize").jsonPrimitive.content.toInt()
        val keys = PushKeys(bytes("privateKey"), bytes("publicKey"), bytes("authSecret"))
        val senderPublicKey = bytes("senderPublicKey")
        val salt = bytes("salt")
        val ciphertext = bytes("ciphertext")
        val plaintext = bytes("plaintext")
    }

    val cryptoCases: List<CryptoCase> get() = root.getValue("crypto").jsonArray.map { CryptoCase(it.jsonObject) }
}

/** Sender side of Web Push encryption, so tests can produce pushes for freshly generated keys. */
internal object TestEncryptor {
    private val random = SecureRandom()
    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }

    class AesGcmResult(val senderPublicKey: ByteArray, val salt: ByteArray, val ciphertext: ByteArray)

    fun aesGcm(receiverPublicKey: ByteArray, authSecret: ByteArray, plaintext: ByteArray): AesGcmResult {
        val (senderPublic, secret) = agree(receiverPublicKey)
        val salt = ByteArray(16).also(random::nextBytes)
        val prk = WebPushCrypto.hkdf(authSecret, secret, "Content-Encoding: auth\u0000".toByteArray(), 32)
        val context = "P-256\u0000".toByteArray() + prefixed(receiverPublicKey) + prefixed(senderPublic)
        val key = WebPushCrypto.hkdf(salt, prk, "Content-Encoding: aesgcm\u0000".toByteArray() + context, 16)
        val nonce = WebPushCrypto.hkdf(salt, prk, "Content-Encoding: nonce\u0000".toByteArray() + context, 12)
        return AesGcmResult(senderPublic, salt, seal(key, nonce, byteArrayOf(0, 0) + plaintext))
    }

    fun aes128Gcm(receiverPublicKey: ByteArray, authSecret: ByteArray, plaintext: ByteArray): ByteArray {
        val (senderPublic, secret) = agree(receiverPublicKey)
        val salt = ByteArray(16).also(random::nextBytes)
        val ikm = WebPushCrypto.hkdf(authSecret, secret, "WebPush: info\u0000".toByteArray() + receiverPublicKey + senderPublic, 32)
        val key = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: aes128gcm\u0000".toByteArray(), 16)
        val nonce = WebPushCrypto.hkdf(salt, ikm, "Content-Encoding: nonce\u0000".toByteArray(), 12)
        val header = salt + byteArrayOf(0, 0, 0x10, 0, senderPublic.size.toByte()) + senderPublic
        return header + seal(key, nonce, plaintext + byteArrayOf(2))
    }

    private fun agree(receiverPublicKey: ByteArray): Pair<ByteArray, ByteArray> {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }
        val point = ECPoint(BigInteger(1, receiverPublicKey.copyOfRange(1, 33)), BigInteger(1, receiverPublicKey.copyOfRange(33, 65)))
        val receiver = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(pair.private)
            doPhase(receiver, true)
            generateSecret()
        }
        val w = (pair.public as ECPublicKey).w
        return (byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)) to secret
    }

    private fun seal(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(data)
        }

    private fun prefixed(data: ByteArray) = byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()) + data

    private fun fixed(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        return if (raw.size >= 32) raw.copyOfRange(raw.size - 32, raw.size) else ByteArray(32 - raw.size) + raw
    }
}
