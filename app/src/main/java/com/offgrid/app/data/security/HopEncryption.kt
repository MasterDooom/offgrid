package com.offgrid.app.data.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Prototype hop-by-hop encryption for the OFFGRID mesh.
 *
 * Every radio hop gets a different AES-256 key derived from the two node identities at that hop.
 * A relay decrypts the packet it received, reads the routing envelope, and encrypts the same
 * payload again for the next hop. This is intentionally NOT end-to-end encryption: a relay can
 * see the plaintext while forwarding, which is useful for the current mesh-routing prototype.
 *
 * Production hardening should replace deterministic key derivation with authenticated ECDH key
 * exchange and persistent/rotated device identity keys.
 */
object HopEncryption {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val DOMAIN = "OFFGRID-HOP-V1"

    fun linkKey(firstNodeId: String, secondNodeId: String): ByteArray {
        val ordered = listOf(firstNodeId, secondNodeId).sorted()
        return sha256("$DOMAIN|${ordered[0]}|${ordered[1]}").copyOf(KEY_BYTES)
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray): String {
        require(key.size == KEY_BYTES) { "Hop key must be 256 bits" }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Hop key must be 256 bits" }
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "Invalid encrypted payload" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
}
