/**
 * @file ws-client.js
 * @description WebSocket client for the Beam relay server.
 *
 * Responsibilities:
 *   - Open a WebSocket to the relay and complete the Ed25519 sign-challenge
 *     authentication handshake automatically.
 *   - Register rendezvous IDs with the relay so the server can route
 *     PEER_ONLINE / PEER_OFFLINE events back to us.
 *   - Emit typed events to registered handlers via a simple on() / _emit()
 *     pub-sub pattern.
 *   - Route binary frames to a single registered binary handler.
 *   - Send application-level PING frames on a configurable interval to keep
 *     the relay connection alive.
 *   - Reconnect automatically on close using a capped exponential back-off
 *     schedule, stopping only when disconnect() is called explicitly.
 *
 * Authentication flow (per server/src/protocol.js):
 *   1. Server sends  { type: 'challenge', challenge: <hex-string> }
 *   2. Client signs  challenge_bytes || timestamp_string  with Ed25519 sk
 *   3. Client sends  { type: 'auth', deviceId, publicKey, signature, timestamp }
 *   4. Server sends  { type: 'auth-ok' }  or  { type: 'auth-fail', reason }
 *
 * Usage:
 *   const ws = new WsClient();
 *   await ws.connect(deviceId, deviceKeys, rendezvousIds);
 *   ws.on(WIRE.PEER_ONLINE, msg => { ... });
 *
 * @module offscreen/ws-client
 */

import { sign } from './crypto.js';
import { WIRE } from '../shared/message-types.js';
import { RELAY_URL, HEARTBEAT_INTERVAL_MS } from '../shared/constants.js';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Decode a hexadecimal string into a Uint8Array.
 *
 * @param {string} hex - Even-length hex string (e.g. "deadbeef").
 * @returns {Uint8Array}
 */
function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < hex.length; i += 2) {
    bytes[i / 2] = parseInt(hex.substr(i, 2), 16);
  }
  return bytes;
}

/**
 * Encode a Uint8Array as a standard base64 string.
 *
 * Signatures are at most 64 bytes and public keys 32 bytes, so the spread
 * into String.fromCharCode is safe from stack-overflow here.
 *
 * @param {Uint8Array} bytes
 * @returns {string}
 */
function bytesToBase64(bytes) {
  return btoa(String.fromCharCode(...bytes));
}

// ---------------------------------------------------------------------------
// WsClient
// ---------------------------------------------------------------------------

/**
 * Manages a single authenticated WebSocket connection to the Beam relay.
 *
 * Event emission uses a simple handler map.  Handlers registered with on()
 * are retained across reconnects; there is no automatic cleanup.
 */
export class WsClient {
  constructor() {
    /** @type {WebSocket | null} Active socket instance. */
    this.ws = null;

    /** @type {boolean} True only after a successful AUTH_OK handshake. */
    this.connected = false;

    /** @type {string | null} Stable device identifier for this extension. */
    this.deviceId = null;

    /**
     * Device key pairs produced by generateKeyPairs().
     * @type {{ x25519: { pk: Uint8Array, sk: Uint8Array }, ed25519: { pk: Uint8Array, sk: Uint8Array } } | null}
     */
    this.deviceKeys = null;

    /**
     * Map from wire message type string to array of listener functions.
     * @type {Map<string, Function[]>}
     */
    this._messageHandlers = new Map();

    /**
     * Handler for incoming binary (ArrayBuffer) frames.
     * @type {((data: Uint8Array) => void) | null}
     */
    this._binaryHandler = null;

    /** @type {string[]} Rendezvous IDs to register after AUTH_OK. */
    this._rendezvousIds = [];

    /**
     * How many reconnect attempts have been made since the last successful
     * AUTH_OK.  Set to Infinity by disconnect() to stop the reconnect loop.
     * @type {number}
     */
    this._reconnectAttempt = 0;

    /** @type {ReturnType<typeof setInterval> | null} */
    this._heartbeatTimer = null;

    /**
     * Back-off delays in milliseconds indexed by attempt count.
     * The last entry (30 000 ms) is used for all attempts beyond the array.
     * First attempt has 0 delay so a transient blip reconnects immediately.
     * @type {number[]}
     */
    this._backoffMs = [0, 500, 1000, 2000, 4000, 8000, 16000, 30000];
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  /**
   * Connect to the relay server and complete the authentication handshake.
   *
   * Stores credentials so that automatic reconnects replay the same auth flow.
   * Resolves once AUTH_OK is received.  Rejects if the initial TCP/WS open
   * fails before the challenge arrives (subsequent reconnects are silent).
   *
   * @param {string}     deviceId       - Stable base64url device identifier.
   * @param {object}     deviceKeys     - Key pairs from generateKeyPairs().
   * @param {string[]}  [rendezvousIds] - Rendezvous IDs to register after auth.
   * @returns {Promise<void>}
   */
  async connect(deviceId, deviceKeys, rendezvousIds = []) {
    this.deviceId = deviceId;
    this.deviceKeys = deviceKeys;
    this._rendezvousIds = rendezvousIds;
    await this._connect(RELAY_URL);
  }

  /**
   * Send a JSON message to the relay.
   *
   * @param {object | ArrayBuffer | Uint8Array} msg
   * @returns {boolean} false if the socket is not open.
   */
  send(msg) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false;
    if (
      typeof msg === 'object' &&
      !(msg instanceof ArrayBuffer) &&
      !(msg instanceof Uint8Array)
    ) {
      this.ws.send(JSON.stringify(msg));
    } else {
      this.ws.send(msg);
    }
    return true;
  }

  /**
   * Send a raw binary frame to the relay.
   *
   * @param {ArrayBuffer | Uint8Array} data
   * @returns {boolean} false if the socket is not open.
   */
  sendBinary(data) {
    if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return false;
    this.ws.send(data);
    return true;
  }

  /**
   * Register a handler for a specific wire message type.
   *
   * Multiple handlers may be registered for the same type; all will be called
   * in registration order.
   *
   * @param {string}   type    - A WIRE.* constant value.
   * @param {Function} handler - Called with the parsed message object.
   */
  on(type, handler) {
    if (!this._messageHandlers.has(type)) {
      this._messageHandlers.set(type, []);
    }
    this._messageHandlers.get(type).push(handler);
  }

  /**
   * Register a handler for incoming binary frames.
   * Only one binary handler is supported; calling this again replaces it.
   *
   * @param {(data: Uint8Array) => void} handler
   */
  onBinary(handler) {
    this._binaryHandler = handler;
  }

  /**
   * Close the connection and suppress automatic reconnection.
   */
  disconnect() {
    this._stopHeartbeat();
    // Infinity prevents _scheduleReconnect from opening a new socket.
    this._reconnectAttempt = Infinity;
    if (this.ws) this.ws.close();
  }

  // ── Internal: connection lifecycle ─────────────────────────────────────────

  /**
   * Open a WebSocket to `url` and wire up the message/close handlers.
   *
   * Returns a Promise that resolves on AUTH_OK or rejects on a connection
   * error before the auth handshake completes.  Subsequent reconnects after
   * initial success do not surface errors to the caller.
   *
   * @param {string} url - WebSocket endpoint URL.
   * @returns {Promise<void>}
   */
  _connect(url) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(url);
      ws.binaryType = 'arraybuffer';
      this.ws = ws;

      // Auth handshake is initiated by the server; nothing to send on open.
      ws.onopen = () => { /* wait for challenge frame */ };

      ws.onmessage = (event) => {
        // Route binary frames directly to the binary handler.
        if (event.data instanceof ArrayBuffer) {
          if (this._binaryHandler) {
            this._binaryHandler(new Uint8Array(event.data));
          }
          return;
        }

        let msg;
        try {
          msg = JSON.parse(event.data);
        } catch (err) {
          console.error('[Beam] WsClient: failed to parse relay message:', err);
          return;
        }

        this._handleMessage(msg, resolve, reject);
      };

      ws.onclose = (event) => {
        this.connected = false;
        this._stopHeartbeat();
        console.log(
          `[Beam] WsClient: connection closed (code=${event.code}, ` +
          `clean=${event.wasClean}) — attempt ${this._reconnectAttempt}`,
        );
        this._scheduleReconnect();
      };

      // onerror does not carry useful information in the browser WebSocket API;
      // onclose always fires afterward, so we handle everything there.
      ws.onerror = (event) => {
        // Reject the initial connect() promise if auth hasn't completed yet.
        // For reconnect attempts the promise is already settled; reject is a no-op.
        reject(new Error('[Beam] WsClient: WebSocket error before auth completed'));
      };
    });
  }

  // ── Internal: message dispatch ─────────────────────────────────────────────

  /**
   * Route an incoming JSON message to the appropriate handler.
   *
   * The `resolveConnect` and `rejectConnect` callbacks belong to the Promise
   * created by `_connect()` for the current socket instance.  They are passed
   * through so the auth flow can settle the connect() promise.
   *
   * @param {object}   msg            - Parsed relay message.
   * @param {Function} resolveConnect - Promise resolve from _connect().
   * @param {Function} rejectConnect  - Promise reject from _connect().
   */
  _handleMessage(msg, resolveConnect, rejectConnect) {
    // ── Authentication handshake ──────────────────────────────────────────

    if (msg.type === WIRE.CHALLENGE) {
      // Server sent the sign-challenge nonce; sign and reply.
      this._handleChallenge(msg).catch((err) => {
        console.error('[Beam] WsClient: challenge signing failed:', err);
        rejectConnect(err);
      });
      return;
    }

    if (msg.type === WIRE.AUTH_OK) {
      this.connected = true;
      this._reconnectAttempt = 0;
      this._startHeartbeat();

      // Re-register rendezvous IDs so the server routes peer events to us.
      if (this._rendezvousIds.length > 0) {
        this.send({
          type: WIRE.REGISTER_RENDEZVOUS,
          rendezvousIds: this._rendezvousIds,
        });
      }

      // Settle the connect() promise exactly once.
      resolveConnect();

      // Also notify any external AUTH_OK listeners (e.g. for reconnect events).
      this._emit(WIRE.AUTH_OK, msg);
      return;
    }

    if (msg.type === WIRE.AUTH_FAIL) {
      const reason = msg.reason ?? 'unknown reason';
      console.error('[Beam] WsClient: authentication rejected by relay:', reason);
      rejectConnect(new Error(`[Beam] Auth failed: ${reason}`));
      this._emit(WIRE.AUTH_FAIL, msg);
      return;
    }

    // ── General relay messages ────────────────────────────────────────────
    this._emit(msg.type, msg);
  }

  /**
   * Sign the relay's challenge nonce and send the AUTH response.
   *
   * Signed payload: challenge_bytes || timestamp_string (UTF-8).
   * This binds the signature to a specific challenge and a time window,
   * preventing replay attacks if the server enforces timestamp freshness.
   *
   * @param {{ challenge: string }} msg - Challenge message from the server.
   * @returns {Promise<void>}
   */
  async _handleChallenge(msg) {
    const { challenge } = msg;
    const timestamp = Date.now();

    // Build the payload that will be signed: raw challenge bytes followed by
    // the decimal timestamp string encoded as UTF-8.
    const challengeBytes  = hexToBytes(challenge);
    const timestampBytes  = new TextEncoder().encode(String(timestamp));
    const payload         = new Uint8Array(challengeBytes.length + timestampBytes.length);
    payload.set(challengeBytes);
    payload.set(timestampBytes, challengeBytes.length);

    // sign() is synchronous after crypto.init() has been awaited during startup.
    const signature = sign(payload, this.deviceKeys.ed25519.sk);

    this.send({
      type:      WIRE.AUTH,
      deviceId:  this.deviceId,
      publicKey: bytesToBase64(this.deviceKeys.ed25519.pk),
      signature: bytesToBase64(signature),
      timestamp,
    });
  }

  // ── Internal: event emission ───────────────────────────────────────────────

  /**
   * Invoke all handlers registered for `type` with the given message.
   *
   * @param {string} type - Wire message type string.
   * @param {object} msg  - The full parsed message object.
   */
  _emit(type, msg) {
    const handlers = this._messageHandlers.get(type);
    if (handlers) {
      handlers.forEach((h) => {
        try {
          h(msg);
        } catch (err) {
          console.error(`[Beam] WsClient: handler for "${type}" threw:`, err);
        }
      });
    }
  }

  // ── Internal: heartbeat ────────────────────────────────────────────────────

  /**
   * Start sending application-level PING frames at the configured interval.
   * The relay expects these to confirm the client is still alive; it will
   * disconnect idle clients after ~30 s on Fly.io infrastructure.
   */
  _startHeartbeat() {
    this._stopHeartbeat(); // Guard against duplicate timers on rapid reconnect.
    this._heartbeatTimer = setInterval(() => {
      this.send({ type: WIRE.PING });
    }, HEARTBEAT_INTERVAL_MS);
  }

  /**
   * Cancel the heartbeat timer if one is running.
   */
  _stopHeartbeat() {
    if (this._heartbeatTimer !== null) {
      clearInterval(this._heartbeatTimer);
      this._heartbeatTimer = null;
    }
  }

  // ── Internal: reconnection ─────────────────────────────────────────────────

  /**
   * Schedule a reconnect attempt with exponential back-off.
   *
   * The back-off index is capped at the last entry in _backoffMs so the delay
   * never grows beyond 30 s.  If disconnect() was called (sentinel Infinity)
   * or the socket has already reconnected, the attempt is skipped.
   */
  _scheduleReconnect() {
    // Infinity is the sentinel value set by disconnect() to stop reconnecting.
    if (this._reconnectAttempt === Infinity) return;

    const idx   = Math.min(this._reconnectAttempt, this._backoffMs.length - 1);
    const delay = this._backoffMs[idx];
    this._reconnectAttempt++;

    setTimeout(() => {
      // A concurrent reconnect (e.g. from a different close event) may have
      // already restored the connection by the time this timer fires.
      if (this.connected || this._reconnectAttempt === Infinity) return;

      console.log(`[Beam] WsClient: reconnecting (attempt ${this._reconnectAttempt})…`);
      this._connect(RELAY_URL).catch((err) => {
        // _connect's onclose will fire and schedule the next attempt.
        console.warn('[Beam] WsClient: reconnect attempt failed:', err);
      });
    }, delay);
  }
}
