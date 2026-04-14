/**
 * gateway.js — WebSocket connection management with Ed25519 authentication.
 *
 * Each inbound WebSocket connection goes through a mandatory authentication
 * handshake before any other messages are processed:
 *
 *   Server → Client : { type: "challenge", challenge: "<64 hex chars>" }
 *   Client → Server : { type: "auth", deviceId, publicKey, signature, timestamp }
 *   Server → Client : { type: "auth-ok" }  |  { type: "auth-fail", reason: "..." }
 *
 * The signature must cover the raw challenge bytes concatenated with the UTF-8
 * encoding of the decimal timestamp string:
 *
 *   payload = challengeBytes || Buffer.from(String(timestamp))
 *   signature = Ed25519Sign(privKey, payload)           [base64-encoded]
 *
 * The public key is transmitted base64-encoded. The device ID is derived as:
 *
 *   deviceId = base64url( SHA-256(pubKeyBytes)[0:16] )
 *
 * @module gateway
 */

import { EventEmitter } from 'node:events';
import { randomBytes } from 'node:crypto';
import * as ed from '@noble/ed25519';
import { sha256, sha512 } from '@noble/hashes/sha2.js';
import { MSG, validate } from './protocol.js';

// ---------------------------------------------------------------------------
// Wire @noble/ed25519 v2 synchronous SHA-512 implementation.
// Must be set before any sign/verify calls are made.
// ---------------------------------------------------------------------------
ed.etc.sha512Sync = (...msgs) => sha512(ed.etc.concatBytes(...msgs));

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Auth window in milliseconds — reject timestamps outside this range. */
const AUTH_WINDOW_MS = 30_000;

/** Default time (ms) to wait for auth before forcibly closing the socket. */
const DEFAULT_AUTH_TIMEOUT_MS = 30_000;

/** Length of the challenge in bytes (transmitted as 64 lowercase hex chars). */
const CHALLENGE_BYTES = 32;

/**
 * Maximum number of concurrent UNAUTHENTICATED WebSocket connections allowed
 * from a single client IP address. Authenticated sockets are not counted.
 *
 * Rationale (Beam audit finding #2): the rate limiter's global 50-slot cap on
 * concurrent devices can be exhausted by a single attacker IP that opens many
 * sockets and simply never completes the auth handshake. Because the auth
 * timeout is 30 s, an attacker can trivially deny service for every other
 * user by keeping the unauthenticated pool full. Per-IP accounting fixes that.
 */
export const MAX_UNAUTH_PER_IP = 5;

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Derives a device ID from a raw Ed25519 public key.
 * deviceId = base64url( SHA-256(pubKey)[0:16] )
 *
 * @param {Uint8Array} pubKey - Raw 32-byte Ed25519 public key
 * @returns {string} base64url-encoded 16-byte prefix of the public key hash
 */
function deriveDeviceId(pubKey) {
  const hash = sha256(pubKey);
  return Buffer.from(hash.slice(0, 16)).toString('base64url');
}

/**
 * Serialises a message object to a JSON string and sends it over a WebSocket.
 * Silently ignores sends to already-closed sockets.
 *
 * @param {import('ws').WebSocket} ws
 * @param {object} msg
 */
function safeSend(ws, msg) {
  try {
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify(msg));
    }
  } catch {
    /* ignore — socket may have closed between the readyState check and send */
  }
}

// ---------------------------------------------------------------------------
// Gateway class
// ---------------------------------------------------------------------------

/**
 * Manages all active WebSocket connections, enforces the auth handshake, and
 * provides a message-routing interface for higher-level modules.
 *
 * Events emitted:
 *   'authenticated' (deviceId: string, ws: WebSocket)  — after successful auth
 *   'disconnect'    (deviceId: string)                  — after authenticated close
 *   'message'       (deviceId: string, msg: object, ws) — for authenticated messages
 *
 * @extends EventEmitter
 */
export class Gateway extends EventEmitter {
  /**
   * @param {object} opts
   * @param {import('ws').WebSocketServer} opts.wss         - Attached WebSocket server
   * @param {number} [opts.authTimeoutMs=30000]             - Ms before unauthenticated sockets are closed
   */
  constructor({ wss, authTimeoutMs = DEFAULT_AUTH_TIMEOUT_MS } = {}) {
    super();

    /**
     * Authenticated devices: deviceId → WebSocket.
     * @type {Map<string, import('ws').WebSocket>}
     */
    this.devices = new Map();

    /**
     * Reverse lookup: WebSocket → deviceId (for efficient disconnect cleanup).
     * @type {Map<import('ws').WebSocket, string>}
     */
    this.wsToDevice = new Map();

    /**
     * Pending auth challenges: WebSocket → challenge hex string.
     * Entries are removed on successful auth or socket close.
     * @type {Map<import('ws').WebSocket, string>}
     */
    this.pendingChallenges = new Map();

    /** Configurable auth timeout in milliseconds. @type {number} */
    this.authTimeoutMs = authTimeoutMs;

    /**
     * Per-IP count of concurrent UNAUTHENTICATED WebSocket connections.
     * Incremented in `_onConnection`, decremented on successful auth
     * (`_handleAuth`) or on close of a still-unauthenticated socket
     * (`_onClose`). See `MAX_UNAUTH_PER_IP` for rationale.
     *
     * @type {Map<string, number>}
     */
    this.unauthCountByIp = new Map();

    /** External message handler set via onMessage(). @type {Function|null} */
    this._messageHandler = null;

    if (wss) {
      this._attachToWss(wss);
    }
  }

  // -------------------------------------------------------------------------
  // Public API
  // -------------------------------------------------------------------------

  /**
   * Registers a handler function that will be called with every authenticated
   * inbound message (after the auth handshake is complete).
   *
   * @param {(deviceId: string, msg: object, ws: import('ws').WebSocket) => void} handler
   */
  onMessage(handler) {
    this._messageHandler = handler;
  }

  /**
   * Sends a JSON message to an authenticated device by device ID.
   * Returns false if the device is not connected.
   *
   * @param {string} deviceId
   * @param {object} msg
   * @returns {boolean} true if the send was attempted
   */
  send(deviceId, msg) {
    const ws = this.devices.get(deviceId);
    if (!ws) return false;
    safeSend(ws, msg);
    return true;
  }

  /**
   * Sends a JSON message directly to a WebSocket instance.
   *
   * @param {import('ws').WebSocket} ws
   * @param {object} msg
   */
  sendTo(ws, msg) {
    safeSend(ws, msg);
  }

  // -------------------------------------------------------------------------
  // Private — WebSocketServer attachment
  // -------------------------------------------------------------------------

  /**
   * Attaches connection and error handlers to the provided WebSocketServer.
   * @param {import('ws').WebSocketServer} wss
   */
  _attachToWss(wss) {
    // The `ws` library passes (ws, req) — forward both so `_onConnection`
    // can extract the real client IP from the upgrade request for per-IP
    // unauthenticated-connection accounting.
    wss.on('connection', (ws, req) => this._onConnection(ws, req));
  }

  // -------------------------------------------------------------------------
  // Private — Connection lifecycle
  // -------------------------------------------------------------------------

  /**
   * Handles a new inbound WebSocket connection.
   *
   * Steps:
   *   1. Extract client IP from the upgrade request. Fly.io's edge proxy sets
   *      `Fly-Client-IP`; behind any other reverse proxy we fall back to the
   *      raw TCP remote address. We do NOT trust `X-Forwarded-For` here —
   *      that header is owned by the rate-limiter layer in `server.js` and
   *      using it in two places would make the two caps disagree.
   *   2. Enforce `MAX_UNAUTH_PER_IP`: if this IP already has the maximum
   *      number of still-unauthenticated sockets, close the new socket with
   *      WebSocket code 1008 (policy violation) and bail. Authenticated
   *      sockets are free — only the unauthenticated pool is capped.
   *   3. Generate a fresh 32-byte challenge and send it.
   *   4. Schedule an auth timeout and wire up message/close/error handlers.
   *
   * @param {import('ws').WebSocket} ws
   * @param {import('node:http').IncomingMessage} [req] - Upgrade request; when
   *   omitted (e.g. legacy tests that call _onConnection directly without a
   *   req) the per-IP cap cannot be enforced and the connection is treated
   *   as anonymous. Production paths always pass `req`.
   */
  _onConnection(ws, req) {
    // --- Per-IP unauthenticated connection cap (Beam audit #2) -------------
    //
    // Extract the best-effort real client IP. `Fly-Client-IP` is set by
    // Fly.io's edge; other proxies are not supported for this check, by
    // design — see the doc comment above for why.
    let ip;
    if (req) {
      ip =
        (req.headers && req.headers['fly-client-ip']) ||
        (req.socket && req.socket.remoteAddress) ||
        'unknown';
    } else {
      ip = 'unknown';
    }

    const currentUnauth = this.unauthCountByIp.get(ip) ?? 0;
    if (currentUnauth >= MAX_UNAUTH_PER_IP) {
      // Too many pending-auth sockets for this IP — refuse immediately.
      // 1008 = policy violation. We do NOT send a challenge or register
      // any state; the socket simply closes.
      try {
        ws.close(1008, 'unauth connection limit');
      } catch {
        /* socket may already be torn down — ignore */
      }
      return;
    }

    // Reserve a slot and remember it on the socket so we can decrement
    // exactly once, whether via successful auth or via close-while-unauth.
    this.unauthCountByIp.set(ip, currentUnauth + 1);
    ws._beamUnauthIp = ip;

    // Generate a fresh 32-byte challenge for this connection
    const challengeBytes = randomBytes(CHALLENGE_BYTES);
    const challengeHex = challengeBytes.toString('hex');
    this.pendingChallenges.set(ws, challengeHex);

    // Send challenge immediately
    safeSend(ws, { type: MSG.CHALLENGE, challenge: challengeHex });

    // Schedule auth timeout — close the socket if auth not completed in time
    const authTimer = setTimeout(() => {
      if (this.pendingChallenges.has(ws)) {
        // Still unauthenticated — close the socket
        ws.close();
      }
    }, this.authTimeoutMs);

    // Ensure the timer doesn't keep the Node.js event loop alive
    if (authTimer.unref) authTimer.unref();

    // Attach per-socket handlers
    ws.on('message', (data) => this._onMessage(ws, data));
    ws.on('close', () => this._onClose(ws));
    ws.on('error', () => {
      /* errors are surfaced via the close event; suppress unhandled-error crashes */
    });
  }

  // -------------------------------------------------------------------------
  // Private — Message handling
  // -------------------------------------------------------------------------

  /**
   * Handles a raw WebSocket message frame.
   * Validates the JSON structure, routes auth messages to _handleAuth(), and
   * forwards authenticated messages to the registered handler.
   *
   * @param {import('ws').WebSocket} ws
   * @param {Buffer|string} data
   */
  _onMessage(ws, data) {
    // --- Parse JSON ---
    let msg;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      safeSend(ws, { type: MSG.ERROR, message: 'Invalid JSON' });
      return;
    }

    // --- Protocol-level validation (type, required fields, size) ---
    const result = validate(msg);
    if (!result.valid) {
      safeSend(ws, { type: MSG.ERROR, message: result.error });
      return;
    }

    const isAuthenticated = this.wsToDevice.has(ws);

    // --- Pre-auth gate: only auth/reconnect are allowed before handshake ---
    if (!isAuthenticated) {
      if (msg.type === MSG.AUTH || msg.type === MSG.RECONNECT) {
        this._handleAuth(ws, msg);
      } else {
        safeSend(ws, { type: MSG.ERROR, message: 'not authenticated' });
      }
      return;
    }

    // --- Authenticated path ---
    const deviceId = this.wsToDevice.get(ws);

    // Handle ping internally — respond with pong
    if (msg.type === MSG.PING) {
      safeSend(ws, { type: MSG.PONG });
      return;
    }

    // Forward to external handler or emit as event
    if (this._messageHandler) {
      this._messageHandler(deviceId, msg, ws);
    }
    this.emit('message', deviceId, msg, ws);
  }

  // -------------------------------------------------------------------------
  // Private — Auth verification
  // -------------------------------------------------------------------------

  /**
   * Verifies an auth (or reconnect) message and registers the device on success.
   *
   * Verification order (fail-fast, cheapest checks first):
   *   1. Timestamp freshness (arithmetic only)
   *   2. Device ID derivation and comparison (hash + base64url)
   *   3. Ed25519 signature verification (most expensive)
   *
   * @param {import('ws').WebSocket} ws
   * @param {{ deviceId: string, publicKey: string, signature: string, timestamp: number }} msg
   */
  _handleAuth(ws, msg) {
    const challenge = this.pendingChallenges.get(ws);
    if (!challenge) {
      // Should not happen in normal flow but guard defensively
      safeSend(ws, { type: MSG.AUTH_FAIL, reason: 'No pending challenge for this connection' });
      return;
    }

    const { deviceId, publicKey, signature, timestamp } = msg;

    // --- Check 1: Timestamp freshness ---
    const age = Math.abs(Date.now() - timestamp);
    if (age > AUTH_WINDOW_MS) {
      safeSend(ws, { type: MSG.AUTH_FAIL, reason: 'timestamp out of range (must be within 30 seconds)' });
      return;
    }

    // --- Decode public key ---
    let pubKeyBytes;
    try {
      pubKeyBytes = Buffer.from(publicKey, 'base64');
      if (pubKeyBytes.length !== 32) throw new Error('wrong length');
    } catch {
      safeSend(ws, { type: MSG.AUTH_FAIL, reason: 'publicKey must be a base64-encoded 32-byte Ed25519 public key' });
      return;
    }

    // --- Check 2: Device ID derivation ---
    const expectedDeviceId = deriveDeviceId(pubKeyBytes);
    if (expectedDeviceId !== deviceId) {
      safeSend(ws, { type: MSG.AUTH_FAIL, reason: 'device ID does not match SHA-256(publicKey)[0:16]' });
      return;
    }

    // --- Decode signature ---
    let sigBytes;
    try {
      sigBytes = Buffer.from(signature, 'base64');
    } catch {
      safeSend(ws, { type: MSG.AUTH_FAIL, reason: 'signature must be base64-encoded' });
      return;
    }

    // --- Check 3: Ed25519 signature ---
    // Payload = challengeBytes || UTF-8(String(timestamp))
    const challengeBytes = Buffer.from(challenge, 'hex');
    const timestampBytes = Buffer.from(String(timestamp));
    const payload = Buffer.concat([challengeBytes, timestampBytes]);

    let signatureValid;
    try {
      signatureValid = ed.verify(sigBytes, payload, pubKeyBytes);
    } catch {
      signatureValid = false;
    }

    if (!signatureValid) {
      safeSend(ws, { type: MSG.AUTH_FAIL, reason: 'signature verification failed' });
      return;
    }

    // --- Auth success ---
    console.log(`[gateway] auth-ok: ${deviceId} (existing=${this.devices.has(deviceId)})`);
    this.pendingChallenges.delete(ws);
    // Release the per-IP unauthenticated slot now that this socket is a
    // real authenticated peer. `_releaseUnauthSlot` is idempotent: calling
    // it again from `_onClose` after auth success is a no-op.
    this._releaseUnauthSlot(ws);

    // Zombie-tolerant re-auth: if this deviceId already has an authenticated
    // socket (e.g. the client reconnected before the old socket's TCP close
    // has propagated), we simply overwrite `devices[deviceId]` with the new
    // socket. The old socket's `wsToDevice` entry remains in place; when
    // the old socket eventually closes, `_onClose` will notice it is no
    // longer the current registration and skip the teardown.
    //
    // We deliberately do NOT actively close the old socket here, because
    // doing so races with clients that may be in the middle of a normal
    // reconnect flow and creates an eviction cascade. The defensive check
    // in `_onClose` is sufficient to prevent the stale close from wiping
    // the new registration.
    this.devices.set(deviceId, ws);
    this.wsToDevice.set(ws, deviceId);

    safeSend(ws, { type: MSG.AUTH_OK });
    this.emit('authenticated', deviceId, ws);
  }

  // -------------------------------------------------------------------------
  // Private — Per-IP unauth slot accounting
  // -------------------------------------------------------------------------

  /**
   * Releases the per-IP unauthenticated-connection slot reserved for `ws`
   * back to `this.unauthCountByIp`. Safe to call multiple times — the slot
   * marker (`ws._beamUnauthIp`) is cleared on the first call, making
   * subsequent calls no-ops.
   *
   * This is called in two places:
   *   - `_handleAuth` on auth success (socket is now authenticated)
   *   - `_onClose` (covers unauthenticated socket closes)
   *
   * @param {import('ws').WebSocket} ws
   */
  _releaseUnauthSlot(ws) {
    const ip = ws._beamUnauthIp;
    if (ip === undefined) return;
    ws._beamUnauthIp = undefined;

    const count = this.unauthCountByIp.get(ip) ?? 0;
    if (count <= 1) {
      this.unauthCountByIp.delete(ip);
    } else {
      this.unauthCountByIp.set(ip, count - 1);
    }
  }

  // -------------------------------------------------------------------------
  // Private — Disconnect cleanup
  // -------------------------------------------------------------------------

  /**
   * Cleans up all maps for a closing WebSocket.
   * Emits 'disconnect' if the socket was authenticated.
   *
   * @param {import('ws').WebSocket} ws
   */
  _onClose(ws) {
    // Remove pending challenge (covers unauthenticated closes)
    this.pendingChallenges.delete(ws);

    // Release the per-IP unauth slot if this socket never authenticated.
    // Idempotent — returns immediately if the slot was already released
    // by a successful auth.
    this._releaseUnauthSlot(ws);

    // Clean up authenticated device registration — but ONLY if the closing
    // ws is still the current registration for its deviceId.
    //
    // Defense in depth: if a re-auth has already installed a replacement
    // socket for this deviceId (via the zombie-eviction path in
    // _handleAuth), we must NOT wipe the new registration when the old
    // one's TCP close eventually fires. The eviction path explicitly
    // clears wsToDevice[oldWs] first, so this check is normally dead
    // code, but it remains a safety net in case any future code path
    // forgets to clear the reverse mapping.
    const deviceId = this.wsToDevice.get(ws);
    if (deviceId !== undefined) {
      this.wsToDevice.delete(ws);
      if (this.devices.get(deviceId) === ws) {
        this.devices.delete(deviceId);
        this.emit('disconnect', deviceId);
      }
    }
  }
}
