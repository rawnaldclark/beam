/**
 * @file crypto.test.js
 * @description Tests for the Beam crypto module (extension/offscreen/crypto.js).
 *
 * Runs with:  node --test test/crypto.test.js
 *
 * Design notes:
 * - Tests are grouped with describe() so failures are easy to locate.
 * - Each test imports the module-under-test once via a top-level await so the
 *   shared `_sodium` state is initialised before any test body runs.
 * - We use assert.strictEqual / assert.deepStrictEqual / assert.ok for
 *   deterministic checks, and assert.throws / assert.rejects where errors are
 *   expected.
 */

import { describe, it, before } from 'node:test';
import assert from 'node:assert/strict';
import sodium from 'libsodium-wrappers-sumo';

// ── module under test ──────────────────────────────────────────────────────
import {
  init,
  generateKeyPairs,
  deriveDeviceId,
  sign,
  verify,
  deriveSharedSecret,
  deriveSessionKey,
  deriveChunkKey,
  deriveMetadataKey,
  deriveChunkNonce,
  padChunk,
  unpadChunk,
  encryptChunk,
  decryptChunk,
  encryptMetadata,
  decryptMetadata,
  deriveSAS,
  sasToEmoji,
} from '../offscreen/crypto.js';

// ── helpers ────────────────────────────────────────────────────────────────

/** Return a random Uint8Array of `n` bytes using libsodium (available after init). */
function randomBytes(n) {
  return sodium.randombytes_buf(n);
}

// ═══════════════════════════════════════════════════════════════════════════
// 1. init()
// ═══════════════════════════════════════════════════════════════════════════
describe('init()', () => {
  it('resolves without error and marks sodium as ready', async () => {
    await assert.doesNotReject(init);
  });

  it('calling init() a second time is idempotent', async () => {
    await assert.doesNotReject(init);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 2. generateKeyPairs()
// ═══════════════════════════════════════════════════════════════════════════
describe('generateKeyPairs()', () => {
  let kp;

  before(async () => {
    await init();
    kp = generateKeyPairs();
  });

  it('returns an object with x25519 and ed25519 properties', () => {
    assert.ok(kp.x25519, 'missing x25519');
    assert.ok(kp.ed25519, 'missing ed25519');
  });

  it('x25519.pk is 32 bytes', () => {
    assert.strictEqual(kp.x25519.pk.byteLength, 32);
  });

  it('x25519.sk is 32 bytes', () => {
    assert.strictEqual(kp.x25519.sk.byteLength, 32);
  });

  it('ed25519.pk is 32 bytes', () => {
    assert.strictEqual(kp.ed25519.pk.byteLength, 32);
  });

  it('ed25519.sk is 64 bytes', () => {
    assert.strictEqual(kp.ed25519.sk.byteLength, 64);
  });

  it('successive calls produce different keys', () => {
    const kp2 = generateKeyPairs();
    // Extremely unlikely to be equal for random keys
    assert.notDeepStrictEqual(kp.x25519.pk, kp2.x25519.pk);
    assert.notDeepStrictEqual(kp.ed25519.pk, kp2.ed25519.pk);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 3. deriveDeviceId(ed25519Pk)
// ═══════════════════════════════════════════════════════════════════════════
describe('deriveDeviceId()', () => {
  let kp;

  before(async () => {
    await init();
    kp = generateKeyPairs();
  });

  it('returns a string', () => {
    const id = deriveDeviceId(kp.ed25519.pk);
    assert.strictEqual(typeof id, 'string');
  });

  it('returns a 22-character base64url string (16 bytes → 22 chars no padding)', () => {
    const id = deriveDeviceId(kp.ed25519.pk);
    assert.strictEqual(id.length, 22, `expected 22 chars, got ${id.length}: "${id}"`);
  });

  it('contains only base64url characters (A-Z a-z 0-9 - _)', () => {
    const id = deriveDeviceId(kp.ed25519.pk);
    assert.match(id, /^[A-Za-z0-9_-]+$/);
  });

  it('is deterministic — same input yields same output', () => {
    const id1 = deriveDeviceId(kp.ed25519.pk);
    const id2 = deriveDeviceId(kp.ed25519.pk);
    assert.strictEqual(id1, id2);
  });

  it('is different for different keys', () => {
    const kp2 = generateKeyPairs();
    assert.notStrictEqual(
      deriveDeviceId(kp.ed25519.pk),
      deriveDeviceId(kp2.ed25519.pk),
    );
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 4. sign() / verify()
// ═══════════════════════════════════════════════════════════════════════════
describe('sign / verify', () => {
  let kp;
  let message;
  let sig;

  before(async () => {
    await init();
    kp = generateKeyPairs();
    message = new TextEncoder().encode('hello beam');
    sig = sign(message, kp.ed25519.sk);
  });

  it('sign() returns a 64-byte Uint8Array', () => {
    assert.ok(sig instanceof Uint8Array);
    assert.strictEqual(sig.byteLength, 64);
  });

  it('verify() returns true for a valid signature', () => {
    assert.strictEqual(verify(message, sig, kp.ed25519.pk), true);
  });

  it('verify() returns false when message is tampered', () => {
    const tampered = new Uint8Array(message);
    tampered[0] ^= 0xff;
    assert.strictEqual(verify(tampered, sig, kp.ed25519.pk), false);
  });

  it('verify() returns false with a wrong key', () => {
    const kp2 = generateKeyPairs();
    assert.strictEqual(verify(message, sig, kp2.ed25519.pk), false);
  });

  it('verify() returns false with a corrupted signature', () => {
    const bad = new Uint8Array(sig);
    bad[0] ^= 0xff;
    assert.strictEqual(verify(message, bad, kp.ed25519.pk), false);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 5. deriveSharedSecret()
// ═══════════════════════════════════════════════════════════════════════════
describe('deriveSharedSecret()', () => {
  let alice;
  let bob;

  before(async () => {
    await init();
    alice = generateKeyPairs();
    bob = generateKeyPairs();
  });

  it('returns a 32-byte Uint8Array', () => {
    const secret = deriveSharedSecret(alice.x25519.sk, bob.x25519.pk);
    assert.ok(secret instanceof Uint8Array);
    assert.strictEqual(secret.byteLength, 32);
  });

  it('is commutative: alice→bob == bob→alice', () => {
    const ab = deriveSharedSecret(alice.x25519.sk, bob.x25519.pk);
    const ba = deriveSharedSecret(bob.x25519.sk, alice.x25519.pk);
    assert.deepStrictEqual(ab, ba);
  });

  it('produces a different secret for a different peer', () => {
    const carol = generateKeyPairs();
    const ab = deriveSharedSecret(alice.x25519.sk, bob.x25519.pk);
    const ac = deriveSharedSecret(alice.x25519.sk, carol.x25519.pk);
    assert.notDeepStrictEqual(ab, ac);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 6. deriveSessionKey()
// ═══════════════════════════════════════════════════════════════════════════
describe('deriveSessionKey()', () => {
  let dh1, dh2, dh3, salt, sessionKey;

  before(async () => {
    await init();
    dh1 = randomBytes(32);
    dh2 = randomBytes(32);
    dh3 = randomBytes(32);
    salt = randomBytes(32);
    sessionKey = deriveSessionKey(dh1, dh2, dh3, salt);
  });

  it('returns a 32-byte Uint8Array', () => {
    assert.ok(sessionKey instanceof Uint8Array);
    assert.strictEqual(sessionKey.byteLength, 32);
  });

  it('is deterministic', () => {
    const k2 = deriveSessionKey(dh1, dh2, dh3, salt);
    assert.deepStrictEqual(sessionKey, k2);
  });

  it('changes when any DH input changes', () => {
    const alt = deriveSessionKey(randomBytes(32), dh2, dh3, salt);
    assert.notDeepStrictEqual(sessionKey, alt);
  });

  it('changes when salt changes', () => {
    const alt = deriveSessionKey(dh1, dh2, dh3, randomBytes(32));
    assert.notDeepStrictEqual(sessionKey, alt);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 7. deriveChunkKey() / deriveMetadataKey()
// ═══════════════════════════════════════════════════════════════════════════
describe('deriveChunkKey / deriveMetadataKey', () => {
  let sessionKey, chunkKey, metadataKey;

  before(async () => {
    await init();
    sessionKey = randomBytes(32);
    chunkKey = deriveChunkKey(sessionKey);
    metadataKey = deriveMetadataKey(sessionKey);
  });

  it('deriveChunkKey returns 32 bytes', () => {
    assert.strictEqual(chunkKey.byteLength, 32);
  });

  it('deriveMetadataKey returns 32 bytes', () => {
    assert.strictEqual(metadataKey.byteLength, 32);
  });

  it('chunkKey and metadataKey are different', () => {
    assert.notDeepStrictEqual(chunkKey, metadataKey);
  });

  it('both are deterministic', () => {
    assert.deepStrictEqual(chunkKey, deriveChunkKey(sessionKey));
    assert.deepStrictEqual(metadataKey, deriveMetadataKey(sessionKey));
  });

  it('different sessionKey yields different derived keys', () => {
    const sk2 = randomBytes(32);
    assert.notDeepStrictEqual(chunkKey, deriveChunkKey(sk2));
    assert.notDeepStrictEqual(metadataKey, deriveMetadataKey(sk2));
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 8. deriveChunkNonce()
// ═══════════════════════════════════════════════════════════════════════════
describe('deriveChunkNonce()', () => {
  let chunkKey;

  before(async () => {
    await init();
    chunkKey = randomBytes(32);
  });

  it('returns a 24-byte Uint8Array', () => {
    const n = deriveChunkNonce(chunkKey, 0);
    assert.ok(n instanceof Uint8Array);
    assert.strictEqual(n.byteLength, 24);
  });

  it('is deterministic', () => {
    const n1 = deriveChunkNonce(chunkKey, 7);
    const n2 = deriveChunkNonce(chunkKey, 7);
    assert.deepStrictEqual(n1, n2);
  });

  it('produces different nonces for different chunk indices', () => {
    const n0 = deriveChunkNonce(chunkKey, 0);
    const n1 = deriveChunkNonce(chunkKey, 1);
    const n255 = deriveChunkNonce(chunkKey, 255);
    assert.notDeepStrictEqual(n0, n1);
    assert.notDeepStrictEqual(n0, n255);
    assert.notDeepStrictEqual(n1, n255);
  });

  it('produces different nonces for different chunk keys', () => {
    const ck2 = randomBytes(32);
    const n1 = deriveChunkNonce(chunkKey, 0);
    const n2 = deriveChunkNonce(ck2, 0);
    assert.notDeepStrictEqual(n1, n2);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 9. padChunk() / unpadChunk()
// ═══════════════════════════════════════════════════════════════════════════
describe('padChunk / unpadChunk', () => {
  before(async () => { await init(); });

  /**
   * Bucket thresholds:
   *   dataLen = 4 + plaintext.length
   *   ≤ 65536  → 64 KiB
   *   ≤ 131072 → 128 KiB
   *   ≤ 262144 → 256 KiB
   *   else     → 512 KiB
   */
  const buckets = [
    { name: '64 KiB bucket',  size: 65536,  payload: 100 },
    { name: '128 KiB bucket', size: 131072, payload: 65533 },
    { name: '256 KiB bucket', size: 262144, payload: 131069 },
    { name: '512 KiB bucket', size: 524288, payload: 262141 },
  ];

  for (const { name, size, payload } of buckets) {
    it(`${name}: padded output is exactly ${size} bytes`, () => {
      const pt = randomBytes(payload);
      const padded = padChunk(pt);
      assert.strictEqual(padded.byteLength, size);
    });
  }

  it('first 4 bytes encode the plaintext length (uint32 LE)', () => {
    const pt = new Uint8Array([1, 2, 3, 4, 5]);
    const padded = padChunk(pt);
    const view = new DataView(padded.buffer, padded.byteOffset);
    assert.strictEqual(view.getUint32(0, true), 5);
  });

  it('round-trip: unpadChunk(padChunk(data)) === data', () => {
    const pt = new TextEncoder().encode('round-trip test data');
    const padded = padChunk(pt);
    const recovered = unpadChunk(padded);
    assert.deepStrictEqual(recovered, pt);
  });

  it('round-trip with arbitrary binary data', () => {
    const pt = randomBytes(12345);
    assert.deepStrictEqual(unpadChunk(padChunk(pt)), pt);
  });

  it('padded size is always a power-of-2 bucket', () => {
    for (const payload of [1, 1000, 60000, 65533, 100000, 200000, 260000]) {
      const padded = padChunk(randomBytes(payload));
      const validSizes = [65536, 131072, 262144, 524288];
      assert.ok(
        validSizes.includes(padded.byteLength),
        `unexpected bucket size ${padded.byteLength} for payload ${payload}`,
      );
    }
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 10. encryptChunk() / decryptChunk()
// ═══════════════════════════════════════════════════════════════════════════
describe('encryptChunk / decryptChunk', () => {
  let chunkKey;
  const aad = new TextEncoder().encode('test-aad');

  before(async () => {
    await init();
    chunkKey = randomBytes(32);
  });

  it('round-trip: decryptChunk(encryptChunk(pt)) === pt', () => {
    const pt = new TextEncoder().encode('hello encrypted world');
    const ct = encryptChunk(pt, chunkKey, 0, aad);
    const recovered = decryptChunk(ct, chunkKey, 0, aad);
    assert.deepStrictEqual(recovered, pt);
  });

  it('ciphertext is larger than plaintext (overhead = padding + AEAD tag)', () => {
    const pt = new Uint8Array(100);
    const ct = encryptChunk(pt, chunkKey, 0, aad);
    // Padded to 64 KiB + 16-byte Poly1305 tag
    assert.ok(ct.byteLength > pt.byteLength);
  });

  it('different chunk indices produce different ciphertexts (nonce differs)', () => {
    const pt = new Uint8Array(32).fill(0xab);
    const ct0 = encryptChunk(pt, chunkKey, 0, aad);
    const ct1 = encryptChunk(pt, chunkKey, 1, aad);
    assert.notDeepStrictEqual(ct0, ct1);
  });

  it('tampering with ciphertext causes decryption to throw', () => {
    const pt = new Uint8Array(32).fill(0xcd);
    const ct = encryptChunk(pt, chunkKey, 0, aad);
    const tampered = new Uint8Array(ct);
    tampered[tampered.length - 1] ^= 0xff; // flip last byte of auth tag
    assert.throws(() => decryptChunk(tampered, chunkKey, 0, aad));
  });

  it('wrong AAD causes decryption to throw', () => {
    const pt = new Uint8Array(32).fill(0xef);
    const ct = encryptChunk(pt, chunkKey, 0, aad);
    const wrongAad = new TextEncoder().encode('wrong-aad');
    assert.throws(() => decryptChunk(ct, chunkKey, 0, wrongAad));
  });

  it('wrong chunk index causes decryption to throw (nonce mismatch)', () => {
    const pt = new Uint8Array(32).fill(0x12);
    const ct = encryptChunk(pt, chunkKey, 0, aad);
    assert.throws(() => decryptChunk(ct, chunkKey, 1, aad));
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 11. encryptMetadata() / decryptMetadata()
// ═══════════════════════════════════════════════════════════════════════════
describe('encryptMetadata / decryptMetadata', () => {
  let metadataKey;
  const sampleMeta = {
    fileName: 'photo.jpg',
    fileSize: 4096000,
    mimeType: 'image/jpeg',
    chunkCount: 4,
  };

  before(async () => {
    await init();
    metadataKey = randomBytes(32);
  });

  it('round-trip: decryptMetadata(encryptMetadata(meta)) deep-equals meta', () => {
    const envelope = encryptMetadata(sampleMeta, metadataKey);
    const recovered = decryptMetadata(envelope, metadataKey);
    assert.deepStrictEqual(recovered, sampleMeta);
  });

  it('envelope is a Uint8Array with at least 24 bytes (nonce prefix)', () => {
    const envelope = encryptMetadata(sampleMeta, metadataKey);
    assert.ok(envelope instanceof Uint8Array);
    assert.ok(envelope.byteLength >= 24);
  });

  it('two encryptions of the same metadata produce different envelopes (random nonce)', () => {
    const e1 = encryptMetadata(sampleMeta, metadataKey);
    const e2 = encryptMetadata(sampleMeta, metadataKey);
    assert.notDeepStrictEqual(e1, e2);
  });

  it('tampering with the envelope throws', () => {
    const envelope = encryptMetadata(sampleMeta, metadataKey);
    const tampered = new Uint8Array(envelope);
    tampered[tampered.length - 1] ^= 0xff;
    assert.throws(() => decryptMetadata(tampered, metadataKey));
  });

  it('wrong key throws', () => {
    const envelope = encryptMetadata(sampleMeta, metadataKey);
    const wrongKey = randomBytes(32);
    assert.throws(() => decryptMetadata(envelope, wrongKey));
  });

  it('handles metadata with nested objects and unicode strings', () => {
    const complex = { name: '日本語テスト.txt', tags: ['a', 'b'], nested: { x: 1 } };
    const envelope = encryptMetadata(complex, metadataKey);
    assert.deepStrictEqual(decryptMetadata(envelope, metadataKey), complex);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// 12. deriveSAS() / sasToEmoji()
// ═══════════════════════════════════════════════════════════════════════════
describe('deriveSAS / sasToEmoji', () => {
  let sharedSecret, pk1, pk2, sasBytes;

  before(async () => {
    await init();
    sharedSecret = randomBytes(32);
    pk1 = randomBytes(32);
    pk2 = randomBytes(32);
    sasBytes = deriveSAS(sharedSecret, pk1, pk2);
  });

  it('deriveSAS returns exactly 8 bytes', () => {
    assert.ok(sasBytes instanceof Uint8Array);
    assert.strictEqual(sasBytes.byteLength, 8);
  });

  it('deriveSAS is deterministic', () => {
    const s2 = deriveSAS(sharedSecret, pk1, pk2);
    assert.deepStrictEqual(sasBytes, s2);
  });

  it('different sharedSecret changes SAS', () => {
    const alt = deriveSAS(randomBytes(32), pk1, pk2);
    assert.notDeepStrictEqual(sasBytes, alt);
  });

  it('different pk1/pk2 changes SAS', () => {
    const alt = deriveSAS(sharedSecret, randomBytes(32), pk2);
    assert.notDeepStrictEqual(sasBytes, alt);
  });

  it('sasToEmoji returns an array of 4 strings', () => {
    const emojis = sasToEmoji(sasBytes);
    assert.ok(Array.isArray(emojis));
    assert.strictEqual(emojis.length, 4);
    for (const e of emojis) {
      assert.strictEqual(typeof e, 'string');
      assert.ok(e.length >= 1, 'emoji string should be non-empty');
    }
  });

  it('sasToEmoji is deterministic for the same bytes', () => {
    const e1 = sasToEmoji(sasBytes);
    const e2 = sasToEmoji(sasBytes);
    assert.deepStrictEqual(e1, e2);
  });

  it('different SAS bytes yield different emoji sequences', () => {
    const alt = deriveSAS(randomBytes(32), pk1, pk2);
    // It is astronomically unlikely for two random 8-byte values to
    // produce the same 4 emojis.
    const e1 = sasToEmoji(sasBytes);
    const e2 = sasToEmoji(alt);
    assert.notDeepStrictEqual(e1, e2);
  });
});
