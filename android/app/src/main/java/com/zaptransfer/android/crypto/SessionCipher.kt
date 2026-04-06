package com.zaptransfer.android.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

// ── Wire-format constants ──────────────────────────────────────────────────────

/** HKDF info string used for the triple-DH session key (UTF-8 bytes). */
private val INFO_SESSION = "zaptransfer-session".toByteArray(Charsets.UTF_8)

/** HKDF info string for the per-chunk encryption key. */
private val INFO_CHUNK_KEY = "zaptransfer-chunk-encryption".toByteArray(Charsets.UTF_8)

/** HKDF info string for the metadata encryption key. */
private val INFO_METADATA_KEY = "zaptransfer-metadata-encryption".toByteArray(Charsets.UTF_8)

/** XChaCha20-Poly1305-IETF nonce length: 24 bytes. */
private const val XCHACHA20_NONCE_BYTES = 24

/** XChaCha20-Poly1305-IETF key length: 32 bytes. */
private const val XCHACHA20_KEY_BYTES = 32

/** Poly1305 authentication tag appended to each ciphertext: 16 bytes. */
private const val POLY1305_TAG_BYTES = 16

/**
 * Minimum padded chunk size (64 bytes). Chunks smaller than this receive no
 * meaningful size-hiding benefit, but padding below 64 bytes is never applied
 * to avoid inflating tiny chunks more than 2x their actual size.
 */
private const val MIN_PADDED_SIZE = 64

/**
 * Maximum padded chunk bucket (1 MiB). Chunks above this size are padded
 * to the nearest 1 MiB multiple rather than the next power of two, to avoid
 * doubling memory use for large chunks.
 */
private const val MAX_POWER_OF_TWO_SIZE = 1 shl 20  // 1 048 576 bytes

// ── SessionCipher ──────────────────────────────────────────────────────────────

/**
 * Handles all symmetric cryptography for a single transfer session.
 *
 * Key hierarchy (mirrors the Chrome extension for wire compatibility):
 *
 * ```
 * Triple-DH outputs (dh1, dh2, dh3) + salt
 *         │
 *         ▼  HKDF("zaptransfer-session")
 *    sessionKey (32 bytes)
 *         │
 *         ├──▶ HKDF("zaptransfer-chunk-encryption")    → chunkKey (32 bytes)
 *         └──▶ HKDF("zaptransfer-metadata-encryption") → metadataKey (32 bytes)
 * ```
 *
 * Each chunk is encrypted with XChaCha20-Poly1305-IETF:
 *  - Key:   [chunkKey]
 *  - Nonce: [deriveChunkNonce] — deterministic 24-byte value derived from chunkKey + index
 *  - AAD:   caller-supplied additional authenticated data (e.g., transfer ID + chunk index)
 *
 * Power-of-2 bucket padding [padChunk] / [unpadChunk] hides exact content sizes.
 * Metadata (file name, MIME type, size) is encrypted separately with [metadataKey].
 *
 * Thread safety: all methods are stateless and safe to call from multiple threads.
 */
@Singleton
class SessionCipher @Inject constructor() {

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    // ── Key derivation ─────────────────────────────────────────────────────────

    /**
     * Derives a 32-byte session key from three Diffie-Hellman shared secrets
     * and a random salt using HKDF-SHA256.
     *
     * The input keying material is the concatenation `dh1 || dh2 || dh3`
     * (96 bytes total). This mirrors the X3DH / Double Ratchet convention
     * where multiple DH outputs are combined before extraction.
     *
     * Wire compatibility: same algorithm as the Chrome extension — both sides
     * must supply the DH values in the same order.
     *
     * @param dh1  First 32-byte ECDH output (e.g., sender ephemeral × receiver identity).
     * @param dh2  Second 32-byte ECDH output.
     * @param dh3  Third 32-byte ECDH output.
     * @param salt 32-byte random salt agreed upon in the handshake.
     * @return 32-byte session key.
     */
    fun deriveSessionKey(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, salt: ByteArray): ByteArray {
        require(dh1.size == 32) { "dh1 must be 32 bytes, got ${dh1.size}" }
        require(dh2.size == 32) { "dh2 must be 32 bytes, got ${dh2.size}" }
        require(dh3.size == 32) { "dh3 must be 32 bytes, got ${dh3.size}" }
        require(salt.size == 32) { "salt must be 32 bytes, got ${salt.size}" }

        val ikm = dh1 + dh2 + dh3  // 96-byte input keying material
        return hkdf(ikm = ikm, salt = salt, info = INFO_SESSION, outputLen = 32)
    }

    /**
     * Derives a 32-byte chunk encryption key from the session key.
     *
     * Using a separate sub-key (rather than the session key directly) provides
     * key separation: the chunk key can be rotated or audited independently.
     *
     * @param sessionKey 32-byte session key from [deriveSessionKey].
     * @return 32-byte key for XChaCha20-Poly1305 chunk encryption.
     */
    fun deriveChunkKey(sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "sessionKey must be 32 bytes, got ${sessionKey.size}" }
        return hkdf(ikm = sessionKey, salt = ByteArray(32), info = INFO_CHUNK_KEY, outputLen = 32)
    }

    /**
     * Derives a 32-byte metadata encryption key from the session key.
     *
     * Metadata (file name, size, MIME type) is encrypted with this key before
     * the chunk stream begins, so the receiver can display transfer details
     * without decrypting any payload data.
     *
     * @param sessionKey 32-byte session key from [deriveSessionKey].
     * @return 32-byte key for XChaCha20-Poly1305 metadata encryption.
     */
    fun deriveMetadataKey(sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "sessionKey must be 32 bytes, got ${sessionKey.size}" }
        return hkdf(ikm = sessionKey, salt = ByteArray(32), info = INFO_METADATA_KEY, outputLen = 32)
    }

    /**
     * Derives a deterministic 24-byte nonce for a given chunk index.
     *
     * The nonce is derived as:
     *   `HMAC-SHA256(chunkKey, "nonce" || chunkIndex_BE64)[0..23]`
     *
     * Deterministic nonces mean neither side needs to transmit a per-chunk
     * nonce over the wire, saving 24 bytes per chunk. The nonce is unique per
     * (chunkKey, chunkIndex) pair — since chunkKey is session-scoped and chunk
     * indices are monotonically increasing, nonce reuse is structurally impossible.
     *
     * @param chunkKey   32-byte chunk encryption key from [deriveChunkKey].
     * @param chunkIndex Zero-based index of the chunk within the transfer.
     * @return 24-byte nonce suitable for XChaCha20-Poly1305-IETF.
     */
    fun deriveChunkNonce(chunkKey: ByteArray, chunkIndex: Long): ByteArray {
        require(chunkKey.size == 32) { "chunkKey must be 32 bytes, got ${chunkKey.size}" }
        require(chunkIndex >= 0) { "chunkIndex must be non-negative, got $chunkIndex" }

        // info: 5-byte ASCII prefix + 8-byte big-endian index = 13 bytes
        val info = ByteArray(13).apply {
            "nonce".toByteArray(Charsets.UTF_8).copyInto(this, destinationOffset = 0)
            // Write chunkIndex as big-endian int64 at offset 5
            var idx = chunkIndex
            for (i in 12 downTo 5) {
                this[i] = (idx and 0xFF).toByte()
                idx = idx ushr 8
            }
        }

        val hmac = hmacSha256(key = chunkKey, data = info)
        return hmac.copyOf(XCHACHA20_NONCE_BYTES)  // take first 24 bytes of 32-byte HMAC
    }

    // ── Padding ────────────────────────────────────────────────────────────────

    /**
     * Pads [plaintext] to the next power-of-two bucket size to obscure exact content length.
     *
     * Bucket selection rules:
     *  - If plaintext.size <= [MIN_PADDED_SIZE] (64 B): pad to 64 bytes.
     *  - If plaintext.size <= [MAX_POWER_OF_TWO_SIZE] (1 MiB): pad to the smallest
     *    power of two >= plaintext.size + 4 (4 bytes for the length prefix).
     *  - If plaintext.size > 1 MiB: pad to the nearest 1 MiB multiple.
     *
     * Wire format of the padded buffer:
     * ```
     * [4 bytes: original length as big-endian uint32] [plaintext] [zero padding...]
     * ```
     * The length prefix lets [unpadChunk] strip the padding without a sentinel byte scan.
     *
     * @param plaintext Raw chunk bytes before encryption.
     * @return Padded buffer with a 4-byte length prefix.
     */
    fun padChunk(plaintext: ByteArray): ByteArray {
        val contentLen = plaintext.size
        val paddedSize = computePaddedSize(contentLen + 4)  // +4 for the length prefix

        val result = ByteArray(paddedSize)
        // Write original length as big-endian uint32
        result[0] = (contentLen ushr 24 and 0xFF).toByte()
        result[1] = (contentLen ushr 16 and 0xFF).toByte()
        result[2] = (contentLen ushr 8 and 0xFF).toByte()
        result[3] = (contentLen and 0xFF).toByte()
        // Copy plaintext after the 4-byte header; trailing bytes remain zero (padding)
        plaintext.copyInto(result, destinationOffset = 4)
        return result
    }

    /**
     * Strips the padding added by [padChunk] and returns the original plaintext.
     *
     * Reads the 4-byte big-endian length prefix, then slices that many bytes
     * starting at offset 4.
     *
     * @param padded Padded buffer as produced by [padChunk] (after decryption).
     * @return Original plaintext without the length prefix or trailing zeros.
     * @throws IllegalArgumentException if the buffer is too small or the encoded
     *         length is out of range.
     */
    fun unpadChunk(padded: ByteArray): ByteArray {
        require(padded.size >= 4) { "Padded chunk too small to contain length prefix: ${padded.size}" }

        val originalLen = ((padded[0].toInt() and 0xFF) shl 24) or
            ((padded[1].toInt() and 0xFF) shl 16) or
            ((padded[2].toInt() and 0xFF) shl 8) or
            (padded[3].toInt() and 0xFF)

        require(originalLen >= 0) { "Encoded length is negative: $originalLen" }
        require(originalLen <= padded.size - 4) {
            "Encoded length $originalLen exceeds available data (${padded.size - 4} bytes)"
        }

        return padded.copyOfRange(4, 4 + originalLen)
    }

    // ── Chunk encryption / decryption ──────────────────────────────────────────

    /**
     * Encrypts a single padded chunk with XChaCha20-Poly1305-IETF.
     *
     * Steps:
     *  1. Pad [plaintext] via [padChunk].
     *  2. Derive the 24-byte nonce deterministically via [deriveChunkNonce].
     *  3. Encrypt+authenticate with `crypto_aead_xchacha20poly1305_ietf_encrypt`.
     *
     * The output is `ciphertext || 16-byte Poly1305 tag` (libsodium "combined" mode).
     * Authenticated additional data [aad] is not encrypted but is authenticated —
     * any modification to the AAD will cause decryption to fail. Callers should
     * include at minimum the transfer ID and chunk index in the AAD.
     *
     * @param plaintext  Raw chunk bytes (will be padded internally).
     * @param chunkKey   32-byte chunk encryption key from [deriveChunkKey].
     * @param chunkIndex Zero-based chunk index (used for nonce derivation).
     * @param aad        Additional authenticated data (not encrypted; may be empty).
     * @return Encrypted bytes: `ciphertext || Poly1305 tag` (len = padded + 16).
     * @throws IllegalStateException if libsodium encryption fails.
     */
    fun encryptChunk(
        plaintext: ByteArray,
        chunkKey: ByteArray,
        chunkIndex: Long,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        val padded = padChunk(plaintext)
        val nonce = deriveChunkNonce(chunkKey, chunkIndex)

        // libsodium combined-mode output: ciphertext (same length as message) + 16-byte tag
        val ciphertext = ByteArray(padded.size + POLY1305_TAG_BYTES)
        val ciphertextLen = LongArray(1)

        val success = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext,
            ciphertextLen,
            padded,
            padded.size.toLong(),
            aad,
            aad.size.toLong(),
            null,    // nsec: always null for AEAD (libsodium convention)
            nonce,
            chunkKey
        )
        check(success) { "XChaCha20-Poly1305 encryption failed for chunk $chunkIndex" }

        return ciphertext
    }

    /**
     * Decrypts and authenticates a single encrypted chunk.
     *
     * Steps:
     *  1. Derive the 24-byte nonce deterministically via [deriveChunkNonce].
     *  2. Decrypt and verify the Poly1305 tag.
     *  3. Remove padding via [unpadChunk].
     *
     * @param ciphertext Encrypted bytes including the 16-byte Poly1305 tag.
     * @param chunkKey   32-byte chunk encryption key from [deriveChunkKey].
     * @param chunkIndex Zero-based chunk index (must match the value used during encryption).
     * @param aad        Additional authenticated data (must exactly match encryption-time AAD).
     * @return Decrypted plaintext without padding.
     * @throws IllegalStateException if authentication fails or decryption errors.
     */
    fun decryptChunk(
        ciphertext: ByteArray,
        chunkKey: ByteArray,
        chunkIndex: Long,
        aad: ByteArray = ByteArray(0)
    ): ByteArray {
        require(ciphertext.size > POLY1305_TAG_BYTES) {
            "Ciphertext too short to contain Poly1305 tag: ${ciphertext.size}"
        }

        val nonce = deriveChunkNonce(chunkKey, chunkIndex)
        val decrypted = ByteArray(ciphertext.size - POLY1305_TAG_BYTES)
        val decryptedLen = LongArray(1)

        val success = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            decrypted,
            decryptedLen,
            null,    // nsec: always null for AEAD (libsodium convention)
            ciphertext,
            ciphertext.size.toLong(),
            aad,
            aad.size.toLong(),
            nonce,
            chunkKey
        )
        check(success) {
            "XChaCha20-Poly1305 authentication/decryption failed for chunk $chunkIndex. " +
                "Possible causes: wrong key, wrong AAD, corrupted ciphertext, or replay attack."
        }

        return unpadChunk(decrypted)
    }

    // ── Metadata encryption / decryption ──────────────────────────────────────

    /**
     * Encrypts a JSON metadata string (file name, MIME type, size, etc.) using
     * XChaCha20-Poly1305-IETF with a freshly generated random nonce.
     *
     * Unlike chunk nonces (which are deterministic), the metadata nonce is random
     * because metadata is sent exactly once per session — there is no chunk index
     * to use as a domain-separation input.
     *
     * Envelope format (binary):
     * ```
     * [24 bytes: random nonce] [ciphertext] [16 bytes: Poly1305 tag]
     * ```
     *
     * @param json         UTF-8 JSON string describing the transfer metadata.
     * @param metadataKey  32-byte metadata encryption key from [deriveMetadataKey].
     * @return Binary envelope: `nonce || ciphertext || tag`.
     * @throws IllegalStateException if encryption fails.
     */
    fun encryptMetadata(json: String, metadataKey: ByteArray): ByteArray {
        require(metadataKey.size == XCHACHA20_KEY_BYTES) {
            "metadataKey must be 32 bytes, got ${metadataKey.size}"
        }

        val message = json.toByteArray(Charsets.UTF_8)

        // Generate a random 24-byte nonce using libsodium's CSPRNG.
        // randomBytesBuf(n) returns a freshly-allocated ByteArray filled by the CSPRNG.
        val nonce = sodium.randomBytesBuf(XCHACHA20_NONCE_BYTES)

        val ciphertext = ByteArray(message.size + POLY1305_TAG_BYTES)
        val ciphertextLen = LongArray(1)

        val success = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            ciphertext,
            ciphertextLen,
            message,
            message.size.toLong(),
            ByteArray(0),    // no AAD for metadata envelope
            0L,
            null,
            nonce,
            metadataKey
        )
        check(success) { "XChaCha20-Poly1305 metadata encryption failed" }

        // Prepend the nonce so the receiver can decrypt without any additional handshake
        return nonce + ciphertext
    }

    /**
     * Decrypts a metadata envelope produced by [encryptMetadata].
     *
     * @param envelope    Binary envelope: `nonce || ciphertext || tag`.
     * @param metadataKey 32-byte metadata encryption key from [deriveMetadataKey].
     * @return Decrypted UTF-8 JSON string.
     * @throws IllegalArgumentException if the envelope is too short.
     * @throws IllegalStateException if authentication or decryption fails.
     */
    fun decryptMetadata(envelope: ByteArray, metadataKey: ByteArray): String {
        require(metadataKey.size == XCHACHA20_KEY_BYTES) {
            "metadataKey must be 32 bytes, got ${metadataKey.size}"
        }
        require(envelope.size > XCHACHA20_NONCE_BYTES + POLY1305_TAG_BYTES) {
            "Metadata envelope too short: ${envelope.size} bytes"
        }

        val nonce = envelope.copyOfRange(0, XCHACHA20_NONCE_BYTES)
        val ciphertext = envelope.copyOfRange(XCHACHA20_NONCE_BYTES, envelope.size)

        val plaintext = ByteArray(ciphertext.size - POLY1305_TAG_BYTES)
        val plaintextLen = LongArray(1)

        val success = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            plaintext,
            plaintextLen,
            null,
            ciphertext,
            ciphertext.size.toLong(),
            ByteArray(0),
            0L,
            nonce,
            metadataKey
        )
        check(success) {
            "XChaCha20-Poly1305 metadata authentication/decryption failed. " +
                "Possible causes: wrong key, corrupted envelope."
        }

        return String(plaintext, 0, plaintextLen[0].toInt(), Charsets.UTF_8)
    }

    // ── HKDF (RFC 5869 with HMAC-SHA256) ─────────────────────────────────────

    /**
     * HKDF-Extract + HKDF-Expand (RFC 5869) using HMAC-SHA256.
     *
     * This implementation is intentionally self-contained (no AndroidX crypto
     * dependency) to ensure it matches the Chrome extension's implementation
     * byte-for-byte.
     *
     * @param ikm       Input keying material (the secret entropy source).
     * @param salt      Non-secret random salt; use a zero-filled array if no salt
     *                  is available (RFC 5869 §2.2).
     * @param info      Context/application-specific info string.
     * @param outputLen Desired output length in bytes. Must be <= 32 * 255 for SHA-256.
     * @return [outputLen] bytes of pseudorandom output keying material.
     */
    private fun hkdf(
        ikm: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        outputLen: Int
    ): ByteArray {
        require(outputLen in 1..(32 * 255)) {
            "HKDF output length must be 1..8160, got $outputLen"
        }

        // Extract: PRK = HMAC-SHA256(salt, IKM)
        val prk = hmacSha256(key = salt, data = ikm)

        // Expand: T(1) || T(2) || ... where T(i) = HMAC-SHA256(PRK, T(i-1) || info || i)
        val result = ByteArray(outputLen)
        var prev = ByteArray(0)
        var offset = 0
        var blockIndex = 1

        while (offset < outputLen) {
            // input = T(i-1) || info || counter
            val input = prev + info + byteArrayOf(blockIndex.toByte())
            val block = hmacSha256(key = prk, data = input)
            val copyLen = minOf(block.size, outputLen - offset)
            block.copyInto(result, destinationOffset = offset, endIndex = copyLen)
            offset += copyLen
            prev = block
            blockIndex++
        }

        return result
    }

    /**
     * Computes HMAC-SHA256 of [data] using [key].
     *
     * @param key  HMAC key bytes.
     * @param data Message bytes.
     * @return 32-byte HMAC-SHA256 digest.
     */
    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    // ── Padding bucket calculation ─────────────────────────────────────────────

    /**
     * Computes the padded size bucket for a given content size.
     *
     * - Sizes <= [MIN_PADDED_SIZE]: rounded up to [MIN_PADDED_SIZE].
     * - Sizes <= [MAX_POWER_OF_TWO_SIZE]: rounded up to the next power of two.
     * - Sizes > [MAX_POWER_OF_TWO_SIZE]: rounded up to the next 1 MiB multiple.
     *
     * @param size Content size (including any length prefix) in bytes.
     * @return Target padded size in bytes.
     */
    private fun computePaddedSize(size: Int): Int {
        if (size <= MIN_PADDED_SIZE) return MIN_PADDED_SIZE
        if (size > MAX_POWER_OF_TWO_SIZE) {
            // Round up to the next 1 MiB multiple
            return (size + MAX_POWER_OF_TWO_SIZE - 1) / MAX_POWER_OF_TWO_SIZE * MAX_POWER_OF_TWO_SIZE
        }
        // Round up to the next power of two
        var p = MIN_PADDED_SIZE
        while (p < size) p = p shl 1
        return p
    }
}
