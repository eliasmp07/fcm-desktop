package io.github.eliasmp07.fcmdesktop

import io.github.eliasmp07.fcmdesktop.internal.B64
import io.github.eliasmp07.fcmdesktop.internal.PushKeys
import io.github.eliasmp07.fcmdesktop.internal.WebPushCrypto
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WebPushCryptoTest {

    private fun decrypt(case: Fixtures.CryptoCase, ciphertext: ByteArray = case.ciphertext, keys: PushKeys = case.keys): ByteArray =
        when (case.version) {
            "aesgcm" -> WebPushCrypto.decryptAesGcm(keys, case.senderPublicKey, case.salt, ciphertext, case.recordSize)
            else -> WebPushCrypto.decryptAes128Gcm(keys, ciphertext)
        }

    @Test
    fun decryptsMessagesEncryptedByTheReferenceImplementation() {
        val cases = Fixtures.cryptoCases
        assertEquals(setOf("aesgcm", "aes128gcm"), cases.map { it.version }.toSet())
        assertTrue(cases.any { it.ciphertext.size > 2 * it.recordSize }, "a multi-record case is required")
        for (case in cases) {
            assertContentEquals(case.plaintext, decrypt(case), "${case.version} with ${case.ciphertext.size} bytes")
        }
    }

    @Test
    fun rejectsTamperedCiphertext() {
        for (case in Fixtures.cryptoCases) {
            val tampered = case.ciphertext.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
            assertFailsWith<GeneralSecurityException>(case.version) { decrypt(case, tampered) }
        }
    }

    @Test
    fun rejectsWrongAuthSecret() {
        for (case in Fixtures.cryptoCases) {
            val wrong = PushKeys(case.keys.privateKey, case.keys.publicKey, ByteArray(16))
            assertFailsWith<GeneralSecurityException>(case.version) { decrypt(case, keys = wrong) }
        }
    }

    @Test
    fun rejectsSenderKeyOutsideTheCurve() {
        val case = Fixtures.cryptoCases.first { it.version == "aesgcm" }
        val bogus = case.senderPublicKey.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> {
            WebPushCrypto.decryptAesGcm(case.keys, bogus, case.salt, case.ciphertext)
        }
        assertFailsWith<GeneralSecurityException> {
            WebPushCrypto.decryptAesGcm(case.keys, ByteArray(10), case.salt, case.ciphertext)
        }
    }

    @Test
    fun rejectsGarbage() {
        val keys = WebPushCrypto.generateKeys()
        assertFailsWith<GeneralSecurityException> { WebPushCrypto.decryptAes128Gcm(keys, ByteArray(0)) }
        assertFailsWith<GeneralSecurityException> { WebPushCrypto.decryptAes128Gcm(keys, ByteArray(200) { 7 }) }
    }

    @Test
    fun generatedKeysWorkWithBothEncodings() {
        repeat(20) { // leading-zero coordinates show up roughly once every 128 keys; exercise several
            val keys = WebPushCrypto.generateKeys()
            assertEquals(65, keys.publicKey.size)
            assertEquals(4, keys.publicKey[0].toInt())
            assertEquals(16, keys.authSecret.size)
            assertEquals(87, B64.urlEncode(keys.publicKey).length)
            val text = "hola #$it".toByteArray()

            val legacy = TestEncryptor.aesGcm(keys.publicKey, keys.authSecret, text)
            assertContentEquals(text, WebPushCrypto.decryptAesGcm(keys, legacy.senderPublicKey, legacy.salt, legacy.ciphertext))

            assertContentEquals(text, WebPushCrypto.decryptAes128Gcm(keys, TestEncryptor.aes128Gcm(keys.publicKey, keys.authSecret, text)))
        }
    }

    @Test
    fun base64AcceptsBothAlphabetsAndPadding() {
        val data = byteArrayOf(-5, -1, -2, 0, 62, 63)
        assertContentEquals(data, B64.decode(java.util.Base64.getEncoder().encodeToString(data)))
        assertContentEquals(data, B64.decode(java.util.Base64.getUrlEncoder().encodeToString(data)))
        assertContentEquals(data, B64.decode(B64.urlEncode(data)))
    }
}
