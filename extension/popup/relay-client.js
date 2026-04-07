/**
 * @file popup/relay-client.js
 * @description Lightweight WebSocket relay client for the pairing ceremony.
 *
 * Runs entirely in the popup context using Web Crypto for Ed25519 authentication.
 * Only used during the pairing flow — file transfers continue to use the
 * offscreen document's full relay client.
 *
 * Why not reuse ws-client.js?
 *   - ws-client.js lives in the offscreen document and depends on libsodium.
 *   - The popup has Web Crypto (Ed25519/X25519), chrome.storage.local, and DOM.
 *   - Message forwarding between popup and offscreen is unreliable for pairing.
 *   - This client is intentionally minimal: connect, auth, register rendezvous,
 *     send/receive JSON messages.
 *
 * @module popup/relay-client
 */

const RELAY_URL = 'wss://zaptransfer-relay.fly.dev';

/**
 * Pairing-only relay client.
 *
 * Usage:
 *   const client = new PairingRelayClient();
 *   await client.connect(deviceId, ed25519KeyPair);
 *   client.registerRendezvous([deviceId]);
 *   client.on('pairing-request', handler);
 *   // ... later ...
 *   client.disconnect();
 */
export class PairingRelayClient {
  constructor() {
    /** @type {WebSocket|null} */
    this.ws = null;

    /** @type {string|null} */
    this.deviceId = null;

    /** @type {Map<string, Function[]>} */
    this._handlers = new Map();

    /** @type {boolean} */
    this._connected = false;
  }

  /**
   * Connect to the relay server and authenticate using Web Crypto Ed25519.
   *
   * The relay sends a hex-encoded challenge nonce. We sign
   * `challenge_bytes || timestamp_string` with our Ed25519 private key and
   * reply with the auth payload. The relay verifies the signature against our
   * public key and responds with auth-ok or auth-fail.
   *
   * @param {string} deviceId - This device's ID (base64url hash of ed25519 pk).
   * @param {CryptoKeyPair} ed25519KeyPair - Web Crypto key pair with
   *   privateKey (sign) and publicKey (verify/export).
   * @returns {Promise<void>} Resolves on auth-ok, rejects on error or auth-fail.
   */
  async connect(deviceId, ed25519KeyPair) {
    this.deviceId = deviceId;

    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(RELAY_URL);

      this.ws.onmessage = async (event) => {
        let msg;
        try {
          msg = JSON.parse(event.data);
        } catch {
          console.warn('[Beam relay-client] Non-JSON message ignored');
          return;
        }

        if (msg.type === 'challenge') {
          try {
            const timestamp = Date.now();
            const challengeBytes = hexToBytes(msg.challenge);
            const timestampBytes = new TextEncoder().encode(String(timestamp));

            // Payload to sign: challenge || timestamp (as UTF-8 digits)
            const payload = new Uint8Array(challengeBytes.length + timestampBytes.length);
            payload.set(challengeBytes);
            payload.set(timestampBytes, challengeBytes.length);

            const signature = await crypto.subtle.sign(
              'Ed25519',
              ed25519KeyPair.privateKey,
              payload,
            );

            const publicKeyRaw = await crypto.subtle.exportKey(
              'raw',
              ed25519KeyPair.publicKey,
            );

            this.ws.send(JSON.stringify({
              type:      'auth',
              deviceId:  deviceId,
              publicKey: bytesToBase64(new Uint8Array(publicKeyRaw)),
              signature: bytesToBase64(new Uint8Array(signature)),
              timestamp: timestamp,
            }));
          } catch (err) {
            reject(new Error('Auth signing failed: ' + err.message));
          }
        } else if (msg.type === 'auth-ok') {
          this._connected = true;
          resolve();
        } else if (msg.type === 'auth-fail') {
          reject(new Error('Auth failed: ' + (msg.reason || 'unknown')));
        } else {
          // Dispatch to registered handlers by message type
          const handlers = this._handlers.get(msg.type);
          if (handlers) {
            handlers.forEach(h => {
              try { h(msg); } catch (err) {
                console.error('[Beam relay-client] Handler error:', err);
              }
            });
          }
        }
      };

      this.ws.onerror = (ev) => {
        reject(new Error('WebSocket connection error'));
      };

      this.ws.onclose = () => {
        this._connected = false;
      };
    });
  }

  /**
   * Tell the relay to associate this connection with the given rendezvous IDs
   * so it can route messages from peers who register the same IDs.
   *
   * @param {string[]} ids - Array of rendezvous ID strings.
   */
  registerRendezvous(ids) {
    this.send({ type: 'register-rendezvous', rendezvousIds: ids });
  }

  /**
   * Send a JSON message over the WebSocket.
   * Silently drops the message if the socket is not open.
   *
   * @param {object} msg - JSON-serialisable message object.
   */
  send(msg) {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }

  /**
   * Register a handler for a specific relay message type.
   * Multiple handlers can be registered for the same type.
   *
   * @param {string} type - Message type to listen for (e.g. 'pairing-request').
   * @param {Function} handler - Callback receiving the parsed message object.
   */
  on(type, handler) {
    if (!this._handlers.has(type)) {
      this._handlers.set(type, []);
    }
    this._handlers.get(type).push(handler);
  }

  /**
   * Remove all handlers for a specific message type.
   *
   * @param {string} type - Message type to clear handlers for.
   */
  off(type) {
    this._handlers.delete(type);
  }

  /**
   * Close the WebSocket connection and clear all handlers.
   */
  disconnect() {
    this._handlers.clear();
    if (this.ws) {
      this.ws.onmessage = null;
      this.ws.onerror = null;
      this.ws.onclose = null;
      this.ws.close();
      this.ws = null;
    }
    this._connected = false;
  }

  /** @returns {boolean} True if authenticated and socket is open. */
  get isConnected() {
    return this._connected && this.ws?.readyState === WebSocket.OPEN;
  }
}

// ---------------------------------------------------------------------------
// Encoding helpers
// ---------------------------------------------------------------------------

/**
 * Decode a hex string into a Uint8Array.
 *
 * @param {string} hex - Even-length hex string (e.g. "a1b2c3").
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
 * Encode a Uint8Array to a standard base64 string.
 *
 * @param {Uint8Array} bytes
 * @returns {string}
 */
function bytesToBase64(bytes) {
  return btoa(String.fromCharCode(...bytes));
}
