/**
 * gateway.test.js — TDD tests for the WebSocket Gateway with Ed25519 auth.
 *
 * Test order follows the happy-path-first convention, then failure modes:
 *   1. Challenge is sent on connection (64 hex chars = 32 bytes)
 *   2. Valid auth succeeds → AUTH_OK
 *   3. Wrong signature → AUTH_FAIL with "signature" in reason
 *   4. Mismatched device ID → AUTH_FAIL with "device" in reason
 *   5. Stale timestamp (60 s old) → AUTH_FAIL with "timestamp" in reason
 *   6. Pre-auth non-auth message → ERROR with "not authenticated"
 *   7. Authenticated device appears in gateway.devices, removed on close
 *   8. Auth timeout — connection closed if no auth within authTimeoutMs
 */

import { describe, it, before, after, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import * as ed from '@noble/ed25519';
import { sha256, sha512 } from '@noble/hashes/sha2.js';

import { Gateway, MAX_UNAUTH_PER_IP } from '../src/gateway.js';
import { createVerifyClient } from '../src/origin.js';

// ---------------------------------------------------------------------------
// Wire noble-ed25519 v2 synchronous SHA-512 (required for signSync / etc.)
// ---------------------------------------------------------------------------
ed.etc.sha512Sync = (...msgs) => sha512(ed.etc.concatBytes(...msgs));

// ---------------------------------------------------------------------------
// Cryptographic helpers
// ---------------------------------------------------------------------------

/**
 * Generates an Ed25519 keypair.
 * @returns {{ privKey: Uint8Array, pubKey: Uint8Array }}
 */
function generateKeypair() {
  const privKey = ed.utils.randomPrivateKey();
  const pubKey = ed.getPublicKey(privKey);
  return { privKey, pubKey };
}

/**
 * Derives the device ID from an Ed25519 public key.
 * deviceId = base64url( SHA256(pubKey)[0:16] )
 *
 * @param {Uint8Array} pubKey
 * @returns {string}
 */
function deriveDeviceId(pubKey) {
  const hash = sha256(pubKey);
  return Buffer.from(hash.slice(0, 16)).toString('base64url');
}

/**
 * Signs the auth payload: challenge_bytes || timestamp_string_bytes.
 * The timestamp is encoded as its decimal UTF-8 string representation to
 * avoid any endianness ambiguity.
 *
 * @param {string} challengeHex - 64-char hex string from server
 * @param {number} timestamp    - Unix milliseconds
 * @param {Uint8Array} privKey
 * @returns {string} base64-encoded signature
 */
function signAuth(challengeHex, timestamp, privKey) {
  const challengeBytes = Buffer.from(challengeHex, 'hex');
  const timestampBytes = Buffer.from(String(timestamp));
  const payload = Buffer.concat([challengeBytes, timestampBytes]);
  const sig = ed.sign(payload, privKey);
  return Buffer.from(sig).toString('base64');
}

// ---------------------------------------------------------------------------
// Test server helpers
// ---------------------------------------------------------------------------

/**
 * Creates an HTTP server bound to a random OS-assigned port, attaches a
 * WebSocketServer and a Gateway instance, then starts listening.
 *
 * @param {object} [gatewayOpts] - Options forwarded to Gateway constructor
 * @returns {Promise<{ server: http.Server, wss: WebSocketServer, gateway: Gateway, port: number }>}
 */
function createTestServer(gatewayOpts = {}) {
  return new Promise((resolve, reject) => {
    const server = http.createServer();
    // Match the production WebSocketServer configuration: origin allowlist
    // runs before the WS handshake completes. Tests use `onReject: () => {}`
    // to silence the structured warn output that would otherwise pollute
    // the test report when the origin-rejection test fires.
    const wss = new WebSocketServer({
      server,
      verifyClient: createVerifyClient({ onReject: () => {} }),
    });
    const gateway = new Gateway({ wss, ...gatewayOpts });

    server.listen(0, '127.0.0.1', () => {
      const { port } = server.address();
      resolve({ server, wss, gateway, port });
    });
    server.once('error', reject);
  });
}

/**
 * Closes the HTTP server and all open WS connections, returning a promise.
 * @param {http.Server} server
 * @returns {Promise<void>}
 */
function closeServer(server) {
  return new Promise((resolve) => server.close(() => resolve()));
}

/**
 * Opens a WebSocket to the test server and collects up to `count` JSON
 * messages, then closes the socket.
 *
 * @param {number} port
 * @param {number} count     - How many messages to wait for
 * @param {number} [timeout] - Max wait in ms (default 2000)
 * @returns {Promise<object[]>} Parsed JSON objects
 */
function collectMessages(port, count, timeout = 2000) {
  return new Promise((resolve, reject) => {
    const messages = [];
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    const timer = setTimeout(() => {
      ws.close();
      resolve(messages); // return whatever arrived
    }, timeout);

    ws.on('message', (data) => {
      try {
        messages.push(JSON.parse(data.toString()));
      } catch {
        /* ignore non-JSON frames */
      }
      if (messages.length >= count) {
        clearTimeout(timer);
        ws.close();
        resolve(messages);
      }
    });

    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

/**
 * Opens a WS, waits for the initial challenge, sends an auth message, and
 * returns the WS instance plus the challenge-message object.
 *
 * @param {number} port
 * @returns {Promise<{ ws: WebSocket, challenge: string }>}
 */
function connectAndGetChallenge(port) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    ws.once('message', (data) => {
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return reject(new Error('Non-JSON first frame')); }
      if (msg.type !== 'challenge') return reject(new Error(`Expected challenge, got ${msg.type}`));
      resolve({ ws, challenge: msg.challenge });
    });
    ws.on('error', reject);
  });
}

/**
 * Sends a message on a WS and returns the next JSON message received.
 * @param {WebSocket} ws
 * @param {object}   payload
 * @param {number}   [timeout]
 * @returns {Promise<object>}
 */
function sendAndReceive(ws, payload, timeout = 2000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timeout waiting for response')), timeout);
    ws.once('message', (data) => {
      clearTimeout(timer);
      try { resolve(JSON.parse(data.toString())); } catch { reject(new Error('Non-JSON response')); }
    });
    ws.send(JSON.stringify(payload));
  });
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('Gateway', () => {
  /** @type {{ server: http.Server, wss: WebSocketServer, gateway: Gateway, port: number }} */
  let ctx;

  before(async () => {
    ctx = await createTestServer();
  });

  after(async () => {
    await closeServer(ctx.server);
  });

  // -------------------------------------------------------------------------
  // Test 1 — Challenge on connection
  // -------------------------------------------------------------------------
  it('sends a 32-byte challenge (64 hex chars) on connection', async () => {
    const messages = await collectMessages(ctx.port, 1);
    assert.equal(messages.length, 1, 'expected exactly one message');
    const msg = messages[0];
    assert.equal(msg.type, 'challenge', 'first message type must be "challenge"');
    assert.match(msg.challenge, /^[0-9a-f]{64}$/i, 'challenge must be 64 lowercase hex chars');
  });

  // -------------------------------------------------------------------------
  // Test 2 — Valid auth → AUTH_OK
  // -------------------------------------------------------------------------
  it('accepts a valid auth message and responds with auth-ok', async () => {
    const { privKey, pubKey } = generateKeypair();
    const deviceId = deriveDeviceId(pubKey);
    const { ws, challenge } = await connectAndGetChallenge(ctx.port);

    const timestamp = Date.now();
    const signature = signAuth(challenge, timestamp, privKey);
    const publicKey = Buffer.from(pubKey).toString('base64');

    const reply = await sendAndReceive(ws, {
      type: 'auth',
      deviceId,
      publicKey,
      signature,
      timestamp,
    });

    ws.close();
    assert.equal(reply.type, 'auth-ok', `expected auth-ok, got: ${JSON.stringify(reply)}`);
  });

  // -------------------------------------------------------------------------
  // Test 3 — Wrong signature → AUTH_FAIL with "signature" in reason
  // -------------------------------------------------------------------------
  it('rejects an invalid signature with AUTH_FAIL containing "signature"', async () => {
    const { privKey, pubKey } = generateKeypair();
    const deviceId = deriveDeviceId(pubKey);
    const { ws, challenge } = await connectAndGetChallenge(ctx.port);

    const timestamp = Date.now();
    // Corrupt: sign with a different challenge value
    const badSignature = signAuth('ff'.repeat(32), timestamp, privKey);
    const publicKey = Buffer.from(pubKey).toString('base64');

    const reply = await sendAndReceive(ws, {
      type: 'auth',
      deviceId,
      publicKey,
      signature: badSignature,
      timestamp,
    });

    ws.close();
    assert.equal(reply.type, 'auth-fail', `expected auth-fail, got: ${JSON.stringify(reply)}`);
    assert.match(reply.reason, /signature/i, 'reason must mention "signature"');
  });

  // -------------------------------------------------------------------------
  // Test 4 — Mismatched device ID → AUTH_FAIL with "device" in reason
  // -------------------------------------------------------------------------
  it('rejects a mismatched deviceId with AUTH_FAIL containing "device"', async () => {
    const { privKey, pubKey } = generateKeypair();
    const { ws, challenge } = await connectAndGetChallenge(ctx.port);

    const timestamp = Date.now();
    const signature = signAuth(challenge, timestamp, privKey);
    const publicKey = Buffer.from(pubKey).toString('base64');

    const reply = await sendAndReceive(ws, {
      type: 'auth',
      deviceId: 'AAAAAAAAAAAAAAAAAAAAAA', // wrong device ID
      publicKey,
      signature,
      timestamp,
    });

    ws.close();
    assert.equal(reply.type, 'auth-fail', `expected auth-fail, got: ${JSON.stringify(reply)}`);
    assert.match(reply.reason, /device/i, 'reason must mention "device"');
  });

  // -------------------------------------------------------------------------
  // Test 5 — Stale timestamp (60 s old) → AUTH_FAIL with "timestamp" in reason
  // -------------------------------------------------------------------------
  it('rejects a stale timestamp with AUTH_FAIL containing "timestamp"', async () => {
    const { privKey, pubKey } = generateKeypair();
    const deviceId = deriveDeviceId(pubKey);
    const { ws, challenge } = await connectAndGetChallenge(ctx.port);

    const timestamp = Date.now() - 60_000; // 60 seconds old — outside 30 s window
    const signature = signAuth(challenge, timestamp, privKey);
    const publicKey = Buffer.from(pubKey).toString('base64');

    const reply = await sendAndReceive(ws, {
      type: 'auth',
      deviceId,
      publicKey,
      signature,
      timestamp,
    });

    ws.close();
    assert.equal(reply.type, 'auth-fail', `expected auth-fail, got: ${JSON.stringify(reply)}`);
    assert.match(reply.reason, /timestamp/i, 'reason must mention "timestamp"');
  });

  // -------------------------------------------------------------------------
  // Test 6 — Pre-auth non-auth message → ERROR "not authenticated"
  // -------------------------------------------------------------------------
  it('returns ERROR "not authenticated" for pre-auth non-auth messages', async () => {
    const { ws } = await connectAndGetChallenge(ctx.port);

    const reply = await sendAndReceive(ws, { type: 'ping' });

    ws.close();
    assert.equal(reply.type, 'error', `expected error, got: ${JSON.stringify(reply)}`);
    assert.match(reply.message, /not authenticated/i, 'error message must say "not authenticated"');
  });

  // -------------------------------------------------------------------------
  // Test 7 — Device tracked in gateway.devices, removed on close
  // -------------------------------------------------------------------------
  it('registers authenticated device in gateway.devices and removes it on close', async () => {
    const { privKey, pubKey } = generateKeypair();
    const deviceId = deriveDeviceId(pubKey);
    const { ws, challenge } = await connectAndGetChallenge(ctx.port);

    const timestamp = Date.now();
    const signature = signAuth(challenge, timestamp, privKey);
    const publicKey = Buffer.from(pubKey).toString('base64');

    const reply = await sendAndReceive(ws, {
      type: 'auth',
      deviceId,
      publicKey,
      signature,
      timestamp,
    });
    assert.equal(reply.type, 'auth-ok');

    // Device must appear in the map immediately after auth-ok
    assert.ok(ctx.gateway.devices.has(deviceId), 'devices map should contain the authenticated deviceId');

    // After closing, wait for the gateway's 'disconnect' event which fires
    // only after the server-side close handler has cleaned up the maps.
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timeout waiting for disconnect event')), 2000);
      ctx.gateway.once('disconnect', (disconnectedId) => {
        if (disconnectedId === deviceId) {
          clearTimeout(timer);
          resolve();
        }
      });
      ws.close();
    });

    assert.ok(!ctx.gateway.devices.has(deviceId), 'devices map should not contain deviceId after close');
  });

  // -------------------------------------------------------------------------
  // Test 8 — Auth timeout closes the connection
  // -------------------------------------------------------------------------
  it('closes unauthenticated connections after authTimeoutMs', async () => {
    // Create a separate server with a very short timeout (150 ms) for this test
    const fastCtx = await createTestServer({ authTimeoutMs: 150 });

    try {
      const { ws } = await connectAndGetChallenge(fastCtx.port);

      const closed = await new Promise((resolve) => {
        ws.once('close', () => resolve(true));
        // If still open after 500 ms, something is wrong
        setTimeout(() => resolve(false), 500);
      });

      assert.ok(closed, 'connection should have been closed by auth timeout');
    } finally {
      await closeServer(fastCtx.server);
    }
  });

  // -------------------------------------------------------------------------
  // Test 9 — Zombie-tolerant re-auth: a second auth with the same deviceId
  //           takes ownership of the registration without actively closing
  //           the old socket, and the OLD socket's eventual close does NOT
  //           fire a disconnect event (because `devices[id] === ws` check
  //           in `_onClose` guards against wiping the new registration).
  //
  // Regression test for the "needs force-stop" presence flakiness bug.
  // -------------------------------------------------------------------------
  it('zombie-tolerant re-auth preserves the new registration', async () => {
    const zombieCtx = await createTestServer();
    try {
      const { privKey, pubKey } = generateKeypair();
      const deviceId = deriveDeviceId(pubKey);
      const publicKey = Buffer.from(pubKey).toString('base64');

      let disconnectCount = 0;
      const onDisconnect = (id) => {
        if (id === deviceId) disconnectCount += 1;
      };
      zombieCtx.gateway.on('disconnect', onDisconnect);

      // --- First auth ---
      const first = await connectAndGetChallenge(zombieCtx.port);
      const ts1 = Date.now();
      const sig1 = signAuth(first.challenge, ts1, privKey);
      const reply1 = await sendAndReceive(first.ws, {
        type: 'auth', deviceId, publicKey, signature: sig1, timestamp: ts1,
      });
      assert.equal(reply1.type, 'auth-ok');
      const firstWs = zombieCtx.gateway.devices.get(deviceId);
      assert.ok(firstWs, 'first auth should register the device');

      // --- Second auth (simulates a zombie reconnect before the old TCP close) ---
      const second = await connectAndGetChallenge(zombieCtx.port);
      const ts2 = Date.now();
      const sig2 = signAuth(second.challenge, ts2, privKey);
      const reply2 = await sendAndReceive(second.ws, {
        type: 'auth', deviceId, publicKey, signature: sig2, timestamp: ts2,
      });
      assert.equal(reply2.type, 'auth-ok');

      const currentWs = zombieCtx.gateway.devices.get(deviceId);
      assert.ok(currentWs, 'device should still be registered after re-auth');
      assert.notEqual(currentWs, firstWs, 'device should point at the new socket');

      // Now close the OLD socket (as would happen naturally when its TCP
      // close propagates). This must NOT wipe the new registration or
      // emit a disconnect — the defensive check in `_onClose` compares
      // `devices[id]` to the closing ws before tearing anything down.
      const firstClosed = new Promise((resolve) => first.ws.once('close', resolve));
      first.ws.close();
      await firstClosed;
      await new Promise((r) => setTimeout(r, 50));

      assert.ok(
        zombieCtx.gateway.devices.has(deviceId),
        'device should still be registered after old socket closes',
      );
      assert.equal(
        disconnectCount,
        0,
        'no disconnect event should fire for the stale socket close',
      );

      // Finally close the NEW socket; this is the current registration
      // so we DO expect a disconnect.
      const secondClosed = new Promise((resolve) => {
        zombieCtx.gateway.once('disconnect', () => resolve());
      });
      second.ws.close();
      await secondClosed;

      assert.ok(
        !zombieCtx.gateway.devices.has(deviceId),
        'device should be unregistered after the active socket closes',
      );
    } finally {
      await closeServer(zombieCtx.server);
    }
  });

  // -------------------------------------------------------------------------
  // Test 10 — Origin allowlist (Beam audit #2)
  // -------------------------------------------------------------------------
  describe('origin allowlist', () => {
    /** @type {{ server: http.Server, wss: WebSocketServer, gateway: Gateway, port: number }} */
    let oCtx;

    before(async () => { oCtx = await createTestServer(); });
    after(async () => { await closeServer(oCtx.server); });

    /**
     * Attempts to open a WebSocket with a specific Origin header and
     * resolves with 'open' or 'rejected' depending on which event fires
     * first.
     *
     * @param {number} port
     * @param {string|undefined} origin - Pass undefined to omit Origin.
     * @returns {Promise<'open'|'rejected'>}
     */
    function tryOpen(port, origin) {
      return new Promise((resolve) => {
        const headers = {};
        if (origin !== undefined) headers.Origin = origin;
        const ws = new WebSocket(`ws://127.0.0.1:${port}`, { headers });

        const done = (outcome) => {
          try { ws.close(); } catch { /* ignore */ }
          resolve(outcome);
        };

        ws.once('open', () => done('open'));
        // The `ws` client surfaces a 403 upgrade failure as an 'error'
        // followed by 'close' — 'unexpected-response' is only emitted in
        // certain configurations. We treat any error-before-open as a
        // rejection for the purposes of this test.
        ws.once('error', () => done('rejected'));
      });
    }

    it('rejects a non-extension Origin with 403', async () => {
      const outcome = await tryOpen(oCtx.port, 'https://evil.com');
      assert.equal(outcome, 'rejected', 'https://evil.com origin must be rejected');
    });

    it('accepts a chrome-extension:// Origin', async () => {
      const outcome = await tryOpen(oCtx.port, 'chrome-extension://abcdefghijklmnop');
      assert.equal(outcome, 'open', 'chrome-extension origin must be accepted');
    });

    it('accepts connections with no Origin header (Android / CLI)', async () => {
      const outcome = await tryOpen(oCtx.port, undefined);
      assert.equal(outcome, 'open', 'missing Origin must be accepted');
    });
  });

  // -------------------------------------------------------------------------
  // Test 11 — Per-IP unauthenticated connection cap (Beam audit #2)
  // -------------------------------------------------------------------------
  describe('per-IP unauthenticated connection cap', () => {
    it('caps concurrent unauth sockets at MAX_UNAUTH_PER_IP and releases on auth', async () => {
      // Fresh server: we need a clean `unauthCountByIp` map.
      const capCtx = await createTestServer();
      try {
        assert.equal(MAX_UNAUTH_PER_IP, 5, 'expected default cap of 5');

        // --- Open the first 5 sockets; each must get a challenge. ---------
        const sockets = [];
        const challenges = [];
        for (let i = 0; i < MAX_UNAUTH_PER_IP; i++) {
          const { ws, challenge } = await connectAndGetChallenge(capCtx.port);
          sockets.push(ws);
          challenges.push(challenge);
        }
        assert.equal(sockets.length, 5);
        assert.equal(challenges.length, 5);
        for (const c of challenges) {
          assert.match(c, /^[0-9a-f]{64}$/i);
        }

        // --- 6th connection: must close immediately with code 1008. -------
        const sixthClose = await new Promise((resolve) => {
          const ws6 = new WebSocket(`ws://127.0.0.1:${capCtx.port}`);
          let gotChallenge = false;
          ws6.on('message', (data) => {
            try {
              const m = JSON.parse(data.toString());
              if (m.type === 'challenge') gotChallenge = true;
            } catch { /* ignore */ }
          });
          ws6.once('close', (code) => resolve({ code, gotChallenge }));
          ws6.once('error', () => { /* swallow — close follows */ });
        });

        assert.equal(sixthClose.code, 1008, 'over-cap socket must close with 1008');
        assert.equal(sixthClose.gotChallenge, false, 'over-cap socket must not receive a challenge');

        // --- Authenticate one of the first 5 → frees a slot. --------------
        const { privKey, pubKey } = generateKeypair();
        const deviceId = deriveDeviceId(pubKey);
        const publicKey = Buffer.from(pubKey).toString('base64');
        const timestamp = Date.now();
        const signature = signAuth(challenges[0], timestamp, privKey);

        const authReply = await sendAndReceive(sockets[0], {
          type: 'auth', deviceId, publicKey, signature, timestamp,
        });
        assert.equal(authReply.type, 'auth-ok');

        // --- A new 6th connection should now succeed. ---------------------
        const { ws: ws6b, challenge: c6b } = await connectAndGetChallenge(capCtx.port);
        assert.match(c6b, /^[0-9a-f]{64}$/i,
          'new connection after auth should get a challenge');
        ws6b.close();

        // Clean up the original 5.
        for (const ws of sockets) {
          try { ws.close(); } catch { /* ignore */ }
        }
      } finally {
        await closeServer(capCtx.server);
      }
    });
  });

  // -------------------------------------------------------------------------
  // Test 12 — Top-level process error handlers registered (Beam audit #4)
  // -------------------------------------------------------------------------
  describe('process error handlers', () => {
    it('registers uncaughtException and unhandledRejection handlers', async () => {
      // Importing ../src/server.js directly would start the real HTTP server
      // on PORT 8080, which is not what we want in a unit test. Instead we
      // import the module for its side effects under a dynamic import with
      // a sacrificial ephemeral port via env. The module installs the
      // process-level handlers at import time.
      const prevPort = process.env.PORT;
      process.env.PORT = '0';
      let mod;
      try {
        mod = await import('../src/server.js');
      } finally {
        if (prevPort === undefined) delete process.env.PORT;
        else process.env.PORT = prevPort;
      }

      try {
        assert.ok(
          process.listenerCount('uncaughtException') > 0,
          'expected at least one uncaughtException listener',
        );
        assert.ok(
          process.listenerCount('unhandledRejection') > 0,
          'expected at least one unhandledRejection listener',
        );
      } finally {
        // Best-effort teardown so the test runner can exit: close the
        // WebSocketServer and the underlying HTTP server started by the
        // module's top-level side effects.
        try { mod.wss.close(); } catch { /* ignore */ }
        await new Promise((resolve) => {
          try { mod.httpServer.close(() => resolve()); }
          catch { resolve(); }
        });
      }
    });
  });
});
