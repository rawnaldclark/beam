package com.zaptransfer.android.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.Sign
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the two key pairs every Beam device needs:
 *  - X25519 (Diffie-Hellman): used for key exchange / ECDH during pairing
 *  - Ed25519 (signing): used to authenticate handshake messages and identity
 *
 * All four key halves are stored as Base64 strings inside
 * [EncryptedSharedPreferences], which wraps them with AES-256-GCM at rest
 * using a key that lives in the Android Keystore hardware-backed store.
 */
data class KeyPairs(
    val x25519Pk: ByteArray,   // 32 bytes — Curve25519 public key
    val x25519Sk: ByteArray,   // 32 bytes — Curve25519 secret key
    val ed25519Pk: ByteArray,  // 32 bytes — Ed25519 public key
    val ed25519Sk: ByteArray   // 64 bytes — Ed25519 secret key (seed || public)
) {
    // ByteArray equals/hashCode are identity-based by default; override for value equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeyPairs) return false
        return x25519Pk.contentEquals(other.x25519Pk) &&
            x25519Sk.contentEquals(other.x25519Sk) &&
            ed25519Pk.contentEquals(other.ed25519Pk) &&
            ed25519Sk.contentEquals(other.ed25519Sk)
    }

    override fun hashCode(): Int {
        var result = x25519Pk.contentHashCode()
        result = 31 * result + x25519Sk.contentHashCode()
        result = 31 * result + ed25519Pk.contentHashCode()
        result = 31 * result + ed25519Sk.contentHashCode()
        return result
    }
}

/**
 * Manages the device's long-lived cryptographic identity.
 *
 * Responsibilities:
 *  1. Generate X25519 + Ed25519 key pairs on first launch.
 *  2. Persist them encrypted to disk via [EncryptedSharedPreferences].
 *  3. Expose operations: sign, verify, ECDH shared-secret derivation.
 *  4. Derive a stable device ID from the Ed25519 public key.
 *
 * Thread safety: all operations are synchronous and pure after construction.
 * The Hilt [Singleton] scope ensures one instance per process — key generation
 * happens at most once per install.
 *
 * Security notes:
 *  - [MasterKey] uses AES-256-GCM backed by the Android Keystore — the raw
 *    AES key never leaves secure hardware on devices that support it.
 *  - [EncryptedSharedPreferences] uses AES-256-SIV for key encryption and
 *    AES-256-GCM for value encryption.
 *  - Private keys are never logged or included in crash reports.
 *
 * @param context Application context provided by Hilt via [@ApplicationContext].
 */
@Singleton
class KeyManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val sodium = LazySodiumAndroid(SodiumAndroid())

    /**
     * The AES-256-GCM master key stored in the Android Keystore.
     * Constructed lazily but effectively at first use — initialization is
     * cheap because KeyStore key creation is idempotent.
     */
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Encrypted shared preferences file that holds the four key halves.
     * Named "beam_keys" — do not change the name post-release as it would
     * orphan existing encrypted stores without a migration path.
     */
    private val encPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            "beam_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the persisted [KeyPairs], generating and saving them first if
     * they don't exist yet. Safe to call on every app start.
     *
     * @return The device's stable long-lived key pairs.
     */
    fun getOrCreateKeys(): KeyPairs {
        return loadKeys() ?: generateKeyPairs().also { saveKeys(it) }
    }

    /**
     * Generates fresh X25519 + Ed25519 key pairs using libsodium.
     *
     * This is the raw generation step without persistence. Prefer
     * [getOrCreateKeys] for normal use — this is exposed for use in tests
     * and for ephemeral session key pairs.
     *
     * @return Freshly generated [KeyPairs].
     */
    fun generateKeyPairs(): KeyPairs {
        // X25519 key pair for Diffie-Hellman key exchange
        val x25519Pk = ByteArray(Box.PUBLICKEYBYTES)   // 32 bytes
        val x25519Sk = ByteArray(Box.SECRETKEYBYTES)   // 32 bytes
        sodium.cryptoBoxKeypair(x25519Pk, x25519Sk)

        // Ed25519 key pair for signing handshake messages and asserting identity
        val ed25519Pk = ByteArray(Sign.PUBLICKEYBYTES)  // 32 bytes
        val ed25519Sk = ByteArray(Sign.SECRETKEYBYTES)  // 64 bytes (seed || pk)
        sodium.cryptoSignKeypair(ed25519Pk, ed25519Sk)

        return KeyPairs(x25519Pk, x25519Sk, ed25519Pk, ed25519Sk)
    }

    /**
     * Derives a stable, URL-safe device identifier from an Ed25519 public key.
     *
     * Algorithm: SHA-256(ed25519Pk) → take first 16 bytes → Base64url (no padding).
     * This produces a 22-character string that is collision-resistant at the
     * fleet sizes Beam targets and safe to transmit in URLs and QR codes.
     *
     * @param ed25519Pk The 32-byte Ed25519 public key to hash.
     * @return A 22-character Base64url-encoded device ID string.
     */
    fun deriveDeviceId(ed25519Pk: ByteArray): String {
        val hash = ByteArray(32)
        sodium.cryptoHashSha256(hash, ed25519Pk, ed25519Pk.size.toLong())
        return Base64.encodeToString(
            hash.sliceArray(0 until 16),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    /**
     * Signs [message] with this device's Ed25519 private key using a detached signature.
     *
     * The resulting 64-byte signature can be sent alongside the message and
     * verified by any peer that knows this device's Ed25519 public key.
     *
     * @param message Arbitrary bytes to sign.
     * @return 64-byte detached Ed25519 signature.
     */
    fun sign(message: ByteArray): ByteArray {
        val keys = getOrCreateKeys()
        val sig = ByteArray(Sign.BYTES)  // always 64 bytes
        sodium.cryptoSignDetached(sig, message, message.size.toLong(), keys.ed25519Sk)
        return sig
    }

    /**
     * Verifies a detached Ed25519 [signature] over [message] using the given [publicKey].
     *
     * @param message   The original message bytes.
     * @param signature The 64-byte detached signature to verify.
     * @param publicKey The 32-byte Ed25519 public key of the alleged signer.
     * @return true if the signature is valid; false otherwise.
     */
    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return sodium.cryptoSignVerifyDetached(signature, message, message.size, publicKey)
    }

    /**
     * Derives a 32-byte Diffie-Hellman shared secret from our X25519 secret key
     * and a peer's X25519 public key using the Curve25519 scalar multiplication.
     *
     * The raw output of [cryptoScalarMult] is the Montgomery x-coordinate of the
     * shared point — it must be fed into a KDF (see [SessionCipher.deriveSessionKey])
     * before use as a symmetric key.
     *
     * @param ourSk  Our 32-byte X25519 secret key.
     * @param peerPk The peer's 32-byte X25519 public key.
     * @return 32-byte raw ECDH output (not suitable for direct use as a key).
     */
    fun deriveSharedSecret(ourSk: ByteArray, peerPk: ByteArray): ByteArray {
        val shared = ByteArray(32)
        sodium.cryptoScalarMult(shared, ourSk, peerPk)
        return shared
    }

    // ── Private persistence helpers ───────────────────────────────────────────

    /**
     * Persists all four key halves as Base64-encoded strings.
     * Uses [SharedPreferences.Editor.apply] (async flush) — acceptable because
     * key generation on first launch happens before any transfer operation.
     */
    private fun saveKeys(keys: KeyPairs) {
        encPrefs.edit()
            .putString("x25519_pk", Base64.encodeToString(keys.x25519Pk, Base64.NO_WRAP))
            .putString("x25519_sk", Base64.encodeToString(keys.x25519Sk, Base64.NO_WRAP))
            .putString("ed25519_pk", Base64.encodeToString(keys.ed25519Pk, Base64.NO_WRAP))
            .putString("ed25519_sk", Base64.encodeToString(keys.ed25519Sk, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Loads the four key halves from encrypted storage.
     *
     * Returns null if any key entry is missing (i.e., first launch or after
     * a clear-data operation). All four keys must be present or none is used.
     *
     * @return [KeyPairs] if all four entries exist; null otherwise.
     */
    private fun loadKeys(): KeyPairs? {
        val x25519PkB64 = encPrefs.getString("x25519_pk", null) ?: return null
        val x25519SkB64 = encPrefs.getString("x25519_sk", null) ?: return null
        val ed25519PkB64 = encPrefs.getString("ed25519_pk", null) ?: return null
        val ed25519SkB64 = encPrefs.getString("ed25519_sk", null) ?: return null
        return KeyPairs(
            x25519Pk = Base64.decode(x25519PkB64, Base64.NO_WRAP),
            x25519Sk = Base64.decode(x25519SkB64, Base64.NO_WRAP),
            ed25519Pk = Base64.decode(ed25519PkB64, Base64.NO_WRAP),
            ed25519Sk = Base64.decode(ed25519SkB64, Base64.NO_WRAP)
        )
    }
}
