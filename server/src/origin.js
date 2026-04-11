/**
 * origin.js — WebSocket Origin allowlist (Beam audit finding #2).
 *
 * Browsers always send the `Origin` header on a WebSocket handshake; non-browser
 * clients (Android OkHttp, curl, native CLI tools) do not. The relay must only
 * accept connections from:
 *
 *   1. The official Chrome extension: Origin starting with `chrome-extension://`.
 *   2. Non-browser clients: absent or empty Origin header.
 *
 * Any other non-empty Origin (http://, https://, file://, moz-extension://, etc.)
 * is rejected with HTTP 403 before the WebSocket handshake completes. This
 * prevents a random web page from opening a WebSocket to the relay via the
 * user's browser.
 *
 * Exported as a factory so `server.js` and the test helper in
 * `test/gateway.test.js` can both build a matching WebSocketServer without
 * duplicating the policy.
 *
 * @module origin
 */

/**
 * Builds a `verifyClient` function compatible with the `ws` library's
 * asynchronous form: `verifyClient(info, cb)`.
 *
 * @param {object} [opts]
 * @param {(msg: string, meta: object) => void} [opts.onReject] - Optional hook
 *   invoked whenever a connection is rejected; defaults to `console.warn` with
 *   a structured payload. Tests can pass a no-op to silence output.
 * @returns {(info: { origin?: string, req: import('node:http').IncomingMessage },
 *            cb: (allow: boolean, code?: number, message?: string) => void) => void}
 */
export function createVerifyClient(opts = {}) {
  const onReject = opts.onReject ?? ((reason, meta) => {
    // Structured warn so log aggregators can parse it; kept to a single line.
    console.warn(`[origin] reject: ${reason}`, meta);
  });

  return function verifyClient(info, cb) {
    const origin = info.origin;
    const req = info.req;

    // Extract remote IP for logging only. Prefer Fly.io's Fly-Client-IP header
    // (set by the edge proxy) and fall back to the raw socket address.
    const remoteIp =
      (req && req.headers && req.headers['fly-client-ip']) ||
      (req && req.socket && req.socket.remoteAddress) ||
      'unknown';

    // Absent / empty Origin → non-browser client (Android, CLI). Accept.
    if (!origin || origin === '') {
      cb(true);
      return;
    }

    // Chrome extension → accept.
    if (typeof origin === 'string' && origin.startsWith('chrome-extension://')) {
      cb(true);
      return;
    }

    // Any other non-empty Origin → reject with 403.
    onReject('origin not allowed', { origin, remoteIp });
    cb(false, 403, 'origin not allowed');
  };
}
