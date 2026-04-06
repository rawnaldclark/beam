/**
 * ratelimit.js — Rate limiting and bandwidth quota enforcement for ZapTransfer relay.
 *
 * Protects the relay server against five distinct abuse vectors:
 *
 *   1. Per-IP connection flooding  — max 5 concurrent WS connections per IP.
 *   2. Per-connection message spam — max 50 messages per sliding 1-second window.
 *   3. Device count ceiling        — max 50 authenticated devices at any instant.
 *   4. Monthly bandwidth quota     — 160 GB / month relay cap.
 *   5. Soft quota warning          — relay binary data is disabled once 80 % of
 *                                    the monthly cap is consumed (warning ratio).
 *
 * All state is held in plain Maps/numbers — no external dependencies, no
 * timers that need explicit teardown (the sliding window uses timestamps rather
 * than a persistent timer).
 *
 * Design notes:
 *   - "allow" methods are pure reads: they inspect current state and return a
 *     boolean without mutating anything.
 *   - "track" / "release" methods mutate state.
 *   - This separation lets the caller check the limit BEFORE deciding whether
 *     to accept a connection, then record it only on acceptance.
 *
 * @module ratelimit
 */

// ---------------------------------------------------------------------------
// Defaults
// ---------------------------------------------------------------------------

/** Default maximum WebSocket connections per source IP address. */
const DEFAULT_MAX_CONNECTIONS_PER_IP = 5;

/** Default maximum protocol messages per second per connection ID. */
const DEFAULT_MAX_MESSAGES_PER_SEC = 50;

/** Default maximum simultaneously authenticated device sessions. */
const DEFAULT_MAX_CONCURRENT_DEVICES = 50;

/** Default monthly bandwidth quota in bytes (160 GB). */
const DEFAULT_MONTHLY_BANDWIDTH_BYTES = 160 * 1024 ** 3; // 160 GB

/** Fraction of monthly quota at which binary relay is disabled. */
const DEFAULT_BANDWIDTH_WARNING_RATIO = 0.8;

/** Default sliding window duration in milliseconds. */
const DEFAULT_WINDOW_MS = 1000;

// ---------------------------------------------------------------------------
// RateLimiter class
// ---------------------------------------------------------------------------

/**
 * Stateful rate limiter covering per-IP connections, per-connection message
 * rate, global device count, and monthly bandwidth quota.
 *
 * @example
 * const rl = new RateLimiter();
 *
 * // WebSocket upgrade — verifyClient phase:
 * if (!rl.allowConnection(ip)) { reject(); return; }
 *
 * // Post-handshake — connection accepted:
 * rl.trackConnection(ip);
 *
 * // Authenticated message path:
 * if (!rl.allowMessage(connId)) { ws.close(); return; }
 *
 * // On close:
 * rl.releaseConnection(ip);
 * rl.releaseMessageCounter(connId);
 */
export class RateLimiter {
  /**
   * @param {object}  [opts]
   * @param {number}  [opts.maxConnectionsPerIp=5]            - Max WS connections per source IP.
   * @param {number}  [opts.maxMessagesPerSec=50]             - Max messages per second per connId.
   * @param {number}  [opts.maxConcurrentDevices=50]          - Max simultaneously connected devices.
   * @param {number}  [opts.monthlyBandwidthBytes=160GB]      - Monthly relay byte quota.
   * @param {number}  [opts.bandwidthWarningRatio=0.8]        - Fraction at which relay is disabled.
   * @param {number}  [opts.windowMs=1000]                    - Sliding window duration (ms). Exposed
   *                                                            for testing; callers should use default.
   */
  constructor({
    maxConnectionsPerIp    = DEFAULT_MAX_CONNECTIONS_PER_IP,
    maxMessagesPerSec      = DEFAULT_MAX_MESSAGES_PER_SEC,
    maxConcurrentDevices   = DEFAULT_MAX_CONCURRENT_DEVICES,
    monthlyBandwidthBytes  = DEFAULT_MONTHLY_BANDWIDTH_BYTES,
    bandwidthWarningRatio  = DEFAULT_BANDWIDTH_WARNING_RATIO,
    windowMs               = DEFAULT_WINDOW_MS,
  } = {}) {
    // ---- Configuration ----
    /** @type {number} */ this._maxConnectionsPerIp   = maxConnectionsPerIp;
    /** @type {number} */ this._maxMessagesPerSec      = maxMessagesPerSec;
    /** @type {number} */ this._maxConcurrentDevices   = maxConcurrentDevices;
    /** @type {number} */ this._monthlyBandwidthBytes  = monthlyBandwidthBytes;
    /** @type {number} */ this._bandwidthWarningRatio  = bandwidthWarningRatio;
    /** @type {number} */ this._windowMs               = windowMs;

    // ---- Per-IP connection counters ----
    /**
     * IP address → current open connection count.
     * @type {Map<string, number>}
     */
    this._ipConnections = new Map();

    // ---- Per-connection sliding-window message counters ----
    /**
     * Connection ID → array of message timestamps (epoch ms).
     * Only timestamps within [now - windowMs, now] are retained.
     * @type {Map<string, number[]>}
     */
    this._messageTimestamps = new Map();

    // ---- Concurrent device tracker ----
    /**
     * Set of currently authenticated device IDs.
     * @type {Set<string>}
     */
    this._activeDevices = new Set();

    // ---- Bandwidth quota ----
    /**
     * Total bytes relayed this month (or since server start; see quotaInfo()).
     * @type {number}
     */
    this._bandwidthUsed = 0;
  }

  // ---------------------------------------------------------------------------
  // Per-IP connection limiting
  // ---------------------------------------------------------------------------

  /**
   * Returns true if a new connection from `ip` would be within the per-IP limit.
   * Does NOT mutate state — call trackConnection() to record the new connection.
   *
   * @param {string} ip - Source IP address (IPv4 or IPv6 string).
   * @returns {boolean}
   */
  allowConnection(ip) {
    const current = this._ipConnections.get(ip) ?? 0;
    return current < this._maxConnectionsPerIp;
  }

  /**
   * Increments the open connection count for `ip`.
   * Should be called after the WebSocket connection is fully accepted.
   *
   * @param {string} ip
   */
  trackConnection(ip) {
    const current = this._ipConnections.get(ip) ?? 0;
    this._ipConnections.set(ip, current + 1);
  }

  /**
   * Decrements the open connection count for `ip`.
   * Should be called from the WebSocket close handler.
   * No-ops if the count would go negative (defensive guard).
   *
   * @param {string} ip
   */
  releaseConnection(ip) {
    const current = this._ipConnections.get(ip) ?? 0;
    if (current <= 1) {
      // Remove the entry entirely to avoid unbounded map growth.
      this._ipConnections.delete(ip);
    } else {
      this._ipConnections.set(ip, current - 1);
    }
  }

  // ---------------------------------------------------------------------------
  // Per-connection sliding-window message rate limiting
  // ---------------------------------------------------------------------------

  /**
   * Returns true if the connection identified by `connId` is within the
   * per-second message rate limit, and records this message if so.
   *
   * Uses a sliding window: only timestamps in the past `windowMs` milliseconds
   * are counted. This is more accurate than a fixed-window counter at the cost
   * of O(n) per call where n = messages in the window (bounded by maxMessagesPerSec).
   *
   * @param {string} connId - Unique connection identifier (e.g. a UUID or socket ID).
   * @returns {boolean} true if the message is allowed; false if rate limit exceeded.
   */
  allowMessage(connId) {
    const now = Date.now();
    const windowStart = now - this._windowMs;

    // Retrieve or initialise the timestamp array for this connection.
    let timestamps = this._messageTimestamps.get(connId);
    if (!timestamps) {
      timestamps = [];
      this._messageTimestamps.set(connId, timestamps);
    }

    // Evict timestamps that have scrolled out of the window (oldest first).
    // Since we append in chronological order, expired entries are at the front.
    let i = 0;
    while (i < timestamps.length && timestamps[i] <= windowStart) {
      i++;
    }
    if (i > 0) {
      // Splice out expired entries in one operation rather than iterating splice(0,1).
      timestamps.splice(0, i);
    }

    // Enforce the limit: if the window is already full, deny the message.
    if (timestamps.length >= this._maxMessagesPerSec) {
      return false;
    }

    // Record this message's timestamp and allow it.
    timestamps.push(now);
    return true;
  }

  /**
   * Removes the message-timestamp array for `connId`.
   * Should be called when a connection closes to prevent unbounded memory growth.
   *
   * @param {string} connId
   */
  releaseMessageCounter(connId) {
    this._messageTimestamps.delete(connId);
  }

  // ---------------------------------------------------------------------------
  // Concurrent device count limiting
  // ---------------------------------------------------------------------------

  /**
   * Returns true if adding another device would remain within the concurrent
   * device ceiling.
   * Does NOT mutate state — call trackDevice() to register the device.
   *
   * @returns {boolean}
   */
  allowDevice() {
    return this._activeDevices.size < this._maxConcurrentDevices;
  }

  /**
   * Registers `deviceId` as an active device.
   * Should be called after a device completes authentication.
   *
   * @param {string} deviceId
   */
  trackDevice(deviceId) {
    this._activeDevices.add(deviceId);
  }

  /**
   * Removes `deviceId` from the active device set.
   * Should be called from the disconnect handler.
   *
   * @param {string} deviceId
   */
  releaseDevice(deviceId) {
    this._activeDevices.delete(deviceId);
  }

  // ---------------------------------------------------------------------------
  // Monthly bandwidth quota
  // ---------------------------------------------------------------------------

  /**
   * Adds `bytes` to the running bandwidth total.
   * Should be called once per binary relay frame forwarded.
   *
   * @param {number} bytes - Non-negative byte count.
   */
  addBandwidth(bytes) {
    if (bytes > 0) {
      this._bandwidthUsed += bytes;
    }
  }

  /**
   * Returns true when the consumed bandwidth has reached or exceeded the
   * configured warning ratio of the monthly quota.
   *
   * When this returns true the server should refuse new RELAY_BIND requests
   * (but may still relay data for already-established sessions at the operator's
   * discretion — server.js decides the policy).
   *
   * @returns {boolean}
   */
  isRelayDisabled() {
    return this._bandwidthUsed >= this._monthlyBandwidthBytes * this._bandwidthWarningRatio;
  }

  /**
   * Returns a snapshot of the current bandwidth quota state.
   *
   * @returns {{ usedBytes: number, limitBytes: number, usedRatio: number }}
   */
  quotaInfo() {
    return {
      usedBytes:  this._bandwidthUsed,
      limitBytes: this._monthlyBandwidthBytes,
      usedRatio:  this._bandwidthUsed / this._monthlyBandwidthBytes,
    };
  }
}
