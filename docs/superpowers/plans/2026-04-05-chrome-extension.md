# Chrome Extension Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the ZapTransfer Chrome extension — MV3 with offscreen document architecture, popup UI, context menus, keyboard shortcuts, and E2E encrypted file transfer.

**Architecture:** Thin service worker (event dispatcher) + fat offscreen document (WebRTC, WebSocket, crypto, transfer logic). Popup reads from chrome.storage.session. libsodium.js for all crypto.

**Tech Stack:** Chrome Manifest V3, JavaScript (ES modules), libsodium.js, qr.js, WebRTC API, WebSocket API

**Spec Reference:** docs/superpowers/specs/2026-04-04-zaptransfer-design.md (Sections 4, 5, 7)

**Dependency:** Relay server must be deployed first (Plan 1: `2026-04-05-relay-server.md`)

---

## File Structure

```
extension/
├── manifest.json
├── background.js                    # Service worker (thin shell)
├── offscreen/
│   ├── transfer-engine.html         # Offscreen document shell
│   ├── transfer-engine.js           # Main orchestrator
│   ├── crypto.js                    # libsodium wrapper
│   ├── ws-client.js                 # WebSocket client to relay
│   ├── webrtc-manager.js            # Peer connections + data channels
│   ├── transfer-manager.js          # Chunking, flow control, state machine
│   └── checkpoint.js                # State persistence to chrome.storage.session
├── popup/
│   ├── popup.html
│   ├── popup.js                     # Main popup logic
│   ├── popup.css                    # Styles
│   └── pairing.js                   # QR display, PIN, SAS, device naming
├── lib/
│   ├── sodium.js                    # libsodium.js (~200KB)
│   └── qr.js                       # QR code generator (~5KB)
├── shared/
│   ├── constants.js                 # Config values, relay URL, STUN servers
│   └── message-types.js             # Inter-component message enum
├── icons/
│   ├── icon-16.png
│   ├── icon-32.png
│   ├── icon-48.png
│   └── icon-128.png
└── test/
    ├── crypto.test.js               # Crypto module unit tests
    ├── transfer-manager.test.js     # State machine + chunking tests
    └── wire-format.test.js          # Binary header encode/decode tests
```

---

## Phase A: Foundation

### Task 1: Project Scaffold

**Files:**
- Create: `extension/manifest.json`
- Create: `extension/shared/constants.js`
- Create: `extension/shared/message-types.js`
- Create: `extension/.gitignore`
- Create: `extension/package.json` (dev dependencies for testing only)

- [ ] **Step 1: Create manifest.json**

```json
{
  "manifest_version": 3,
  "name": "ZapTransfer",
  "version": "0.1.0",
  "description": "Instant, encrypted file transfer between your devices.",
  "permissions": ["offscreen", "storage", "notifications", "contextMenus", "commands", "alarms", "activeTab"],
  "host_permissions": ["<all_urls>"],
  "background": { "service_worker": "background.js", "type": "module" },
  "action": {
    "default_popup": "popup/popup.html",
    "default_icon": { "16": "icons/icon-16.png", "48": "icons/icon-48.png", "128": "icons/icon-128.png" }
  },
  "commands": {
    "send-clipboard": { "suggested_key": { "default": "Ctrl+Shift+Z" }, "description": "Send clipboard to last device" },
    "open-device-picker": { "suggested_key": { "default": "Ctrl+Shift+X" }, "description": "Open device picker" }
  },
  "icons": { "16": "icons/icon-16.png", "48": "icons/icon-48.png", "128": "icons/icon-128.png" }
}
```

- [ ] **Step 2: Create shared/constants.js**

```js
export const RELAY_URL = 'wss://zaptransfer-relay.fly.dev';
export const RELAY_URL_BACKUP = 'wss://relay.zaptransfer.example.com';
export const STUN_SERVERS = [
  { urls: 'stun:stun.l.google.com:19302' },
  { urls: 'stun:stun1.l.google.com:19302' },
  { urls: 'stun:stun.services.mozilla.com:3478' },
  { urls: 'stun:stun.cloudflare.com:3478' }
];
export const KEEPALIVE_INTERVAL_MS = 25000;
export const HEARTBEAT_INTERVAL_MS = 30000;
export const ICE_GATHERING_TIMEOUT_MS = 5000;
export const ICE_CHECK_TIMEOUT_MS = 8000;
export const ACCEPTANCE_TIMEOUT_MS = 60000;
export const CHUNK_TIERS = [8192, 16384, 32768, 65536, 131072, 262144, 524288];
export const DEFAULT_CHUNK_TIER = 3; // 64KB
export const WINDOW_INITIAL = 4;
export const WINDOW_MIN = 2;
export const WINDOW_MAX_DIRECT = 64;
export const WINDOW_MAX_RELAY = 8;
export const MAX_CLIPBOARD_HISTORY = 20;
export const MAX_TRANSFER_HISTORY = 10;
export const CHECKPOINT_INTERVAL_CHUNKS = 10;
```

- [ ] **Step 3: Create shared/message-types.js**

```js
// Inter-component messages (SW <-> Offscreen <-> Popup)
export const MSG = {
  KEEPALIVE_PING: 'KEEPALIVE_PING',
  KEEPALIVE_PONG: 'KEEPALIVE_PONG',
  INITIATE_TRANSFER: 'INITIATE_TRANSFER',
  TRANSFER_PROGRESS: 'TRANSFER_PROGRESS',
  TRANSFER_COMPLETE: 'TRANSFER_COMPLETE',
  TRANSFER_FAILED: 'TRANSFER_FAILED',
  INCOMING_TRANSFER: 'INCOMING_TRANSFER',
  FETCH_IMAGE: 'FETCH_IMAGE',
  IMAGE_FETCHED: 'IMAGE_FETCHED',
  GET_DEVICE_LIST: 'GET_DEVICE_LIST',
  DEVICE_LIST: 'DEVICE_LIST',
  DEVICE_PRESENCE_CHANGED: 'DEVICE_PRESENCE_CHANGED',
  START_PAIRING: 'START_PAIRING',
  PAIRING_QR_DATA: 'PAIRING_QR_DATA',
  PAIRING_SAS: 'PAIRING_SAS',
  PAIRING_CONFIRM_SAS: 'PAIRING_CONFIRM_SAS',
  PAIRING_SET_DEVICE_NAME: 'PAIRING_SET_DEVICE_NAME',
  PAIRING_COMPLETE: 'PAIRING_COMPLETE',
  GET_TRANSFER_HISTORY: 'GET_TRANSFER_HISTORY',
  GET_CLIPBOARD_HISTORY: 'GET_CLIPBOARD_HISTORY',
  UPDATE_BADGE: 'UPDATE_BADGE',
  SEND_NOTIFICATION: 'SEND_NOTIFICATION',
  CAPTURE_SCREENSHOT: 'CAPTURE_SCREENSHOT'
};

// Wire protocol message types (device <-> relay)
export const WIRE = {
  AUTH_CHALLENGE: 'auth-challenge',
  AUTH_RESPONSE: 'auth-response',
  AUTH_OK: 'auth-ok',
  AUTH_ERROR: 'auth-error',
  PRESENCE: 'presence',
  TRANSFER_INIT: 'transfer-init',
  TRANSFER_ACCEPT: 'transfer-accept',
  TRANSFER_REJECT: 'transfer-reject',
  ICE_CANDIDATE: 'ice-candidate',
  RELAY_READY: 'relay-ready',
  RELAY_RELEASE: 'relay-release',
  RECONNECT: 'reconnect',
  RECONNECT_ACK: 'reconnect-ack',
  PING: 'ping',
  PONG: 'pong',
  QUOTA_WARNING: 'quota-warning'
};
```

- [ ] **Step 4: Create package.json + .gitignore**

- [ ] **Step 5: Commit** — `git commit -m "feat(extension): project scaffold with manifest, constants, message types"`

---

### Task 2: Crypto Module

**Files:**
- Create: `extension/offscreen/crypto.js`
- Download: `extension/lib/sodium.js` from libsodium.js CDN

- [ ] **Step 1: Write test for crypto module**

Create `extension/test/crypto.test.js`:
```js
import { describe, it } from 'node:test';
import assert from 'node:assert';
// Tests: keygen, deviceId derivation, sign/verify, encrypt/decrypt chunk,
// HKDF session key, deterministic nonce, SAS emoji derivation, metadata envelope
```

- [ ] **Step 2: Run test — expect FAIL** (`node --test extension/test/crypto.test.js`)

- [ ] **Step 3: Implement crypto.js**

Key exports:
```js
export async function init()                    // Load and init libsodium
export function generateKeyPairs()              // Returns {x25519: {pk, sk}, ed25519: {pk, sk}}
export function deriveDeviceId(ed25519Pk)       // Base64url(SHA256(pk)[0:16])
export function sign(message, ed25519Sk)        // Ed25519 detached signature
export function verify(message, sig, ed25519Pk) // Ed25519 verify
export function deriveSharedSecret(mySk, peerPk) // X25519 scalar mult
export function deriveSessionKey(dh1, dh2, dh3, salt) // Triple-DH HKDF
export function deriveChunkKey(sessionKey)       // HKDF with "zaptransfer-chunk-encryption"
export function deriveMetadataKey(sessionKey)    // HKDF with "zaptransfer-metadata-encryption"
export function deriveChunkNonce(chunkKey, chunkIndex) // Deterministic 24-byte nonce
export function encryptChunk(plaintext, chunkKey, chunkIndex, aad) // XChaCha20-Poly1305
export function decryptChunk(ciphertext, chunkKey, chunkIndex, aad)
export function padChunk(plaintext)              // Pad to power-of-2 bucket with random
export function unpadChunk(padded)               // Extract plaintext from padded buffer
export function encryptMetadata(metadata, metadataKey) // Metadata envelope
export function decryptMetadata(envelope, metadataKey)
export function deriveSAS(sharedSecret, pk1, pk2) // 8 bytes for 4 emoji
export function sasToEmoji(sasBytes)             // Map to 4 emoji from 256-emoji table
```

Each function uses libsodium.js functions directly. All keys/nonces are Uint8Array.

- [ ] **Step 4: Run test — expect PASS**

- [ ] **Step 5: Commit** — `git commit -m "feat(extension): crypto module with libsodium wrapper"`

---

### Task 3: Wire Format Helpers

**Files:**
- Create: `extension/offscreen/wire-format.js`
- Create: `extension/test/wire-format.test.js`

- [ ] **Step 1: Write test for binary chunk header encode/decode**

Test cases: encode chunkIndex=0 + decode matches, encode large values, isFinal flag, round-trip.

- [ ] **Step 2: Implement wire-format.js**

```js
// Binary chunk header: 64 bytes
// [0] type(1B) [1-16] transferId(16B) [17-20] chunkIndex(4B BE)
// [21-28] byteOffset(8B BE) [29-32] chunkSize(4B BE) [33] flags(1B) [34-63] reserved(30B)
export function encodeChunkHeader(transferIdBytes, chunkIndex, byteOffset, chunkSize, isFinal)
export function decodeChunkHeader(buffer) // Returns {type, transferId, chunkIndex, byteOffset, chunkSize, isFinal}
export function encodeTransferId(uuidString) // UUID string to 16 bytes
export function decodeTransferId(bytes)      // 16 bytes to UUID string
```

- [ ] **Step 3: Run tests — expect PASS**

- [ ] **Step 4: Commit** — `git commit -m "feat(extension): wire format binary header encode/decode"`

---

## Phase B: Service Worker + Offscreen

### Task 4: Service Worker Shell

**Files:**
- Create: `extension/background.js`

- [ ] **Step 1: Implement background.js**

```js
import { MSG } from './shared/message-types.js';
import { KEEPALIVE_INTERVAL_MS } from './shared/constants.js';

// onInstalled: create alarm, create context menu stubs
chrome.runtime.onInstalled.addListener(() => {
  chrome.alarms.create('keepalive', { periodInMinutes: KEEPALIVE_INTERVAL_MS / 60000 });
  setupContextMenus();
});

// onStartup: ensure offscreen exists
chrome.runtime.onStartup.addListener(ensureOffscreen);

// Alarm handler: ping offscreen
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'keepalive') {
    chrome.runtime.sendMessage({ type: MSG.KEEPALIVE_PING })
      .catch(() => ensureOffscreen());
  }
});

// Message handler: forward to offscreen, handle badge/notification instructions
chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === MSG.UPDATE_BADGE) { /* setBadgeText + color */ }
  if (msg.type === MSG.SEND_NOTIFICATION) { /* chrome.notifications.create */ }
  if (msg.type === MSG.DEVICE_PRESENCE_CHANGED) { setupContextMenus(); }
  if (msg.type === MSG.FETCH_IMAGE) { fetchImage(msg.payload.url, sendResponse); return true; }
});

// Context menu clicks
chrome.contextMenus.onClicked.addListener(handleContextMenuClick);

// Keyboard shortcuts
chrome.commands.onCommand.addListener(handleCommand);

async function ensureOffscreen() { /* create offscreen if !hasDocument */ }
function setupContextMenus() { /* rebuild from chrome.storage.session devicePresence */ }
function handleContextMenuClick(info, tab) { /* extract URL/text, forward to offscreen */ }
function handleCommand(command) { /* send-clipboard or open-device-picker */ }
async function fetchImage(url, sendResponse) { /* fetch with host permission, return blob */ }
```

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): service worker shell with alarm, menu, shortcuts"`

---

### Task 5: Offscreen Document

**Files:**
- Create: `extension/offscreen/transfer-engine.html`
- Create: `extension/offscreen/transfer-engine.js`

- [ ] **Step 1: Create transfer-engine.html**

```html
<!DOCTYPE html>
<html><head><title>ZapTransfer Engine</title></head>
<body>
  <script src="../lib/sodium.js"></script>
  <script type="module" src="transfer-engine.js"></script>
</body></html>
```

- [ ] **Step 2: Create transfer-engine.js — orchestrator**

```js
import { init as initCrypto } from './crypto.js';
import { WsClient } from './ws-client.js';
import { TransferManager } from './transfer-manager.js';
import { MSG } from '../shared/message-types.js';

// Init libsodium, connect to relay, listen for messages
await initCrypto();
const ws = new WsClient();
const transfers = new TransferManager(ws);

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  switch (msg.type) {
    case MSG.KEEPALIVE_PING:
      sendResponse({ type: MSG.KEEPALIVE_PONG, payload: { activeTransfers: transfers.count(), wsConnected: ws.connected } });
      break;
    case MSG.INITIATE_TRANSFER: transfers.initiate(msg.payload); break;
    case MSG.GET_DEVICE_LIST: sendResponse({ type: MSG.DEVICE_LIST, payload: getDeviceList() }); break;
    case MSG.START_PAIRING: startPairing(sendResponse); return true;
    // ... other message types
  }
});
```

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): offscreen document shell with orchestrator"`

---

### Task 6: Inter-Component Messaging

**Files:**
- Modify: `extension/background.js` — add offscreen creation with WORKERS reason
- Modify: `extension/offscreen/transfer-engine.js` — add message dispatch

- [ ] **Step 1: Implement ensureOffscreen in background.js**

```js
async function ensureOffscreen() {
  if (await chrome.offscreen.hasDocument()) return;
  await chrome.offscreen.createDocument({
    url: 'offscreen/transfer-engine.html',
    reasons: ['WORKERS'],
    justification: 'WebRTC data channels and WebSocket connections for file transfer'
  });
}
```

- [ ] **Step 2: Test manually** — load extension in Chrome, verify offscreen is created and keepalive works

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): offscreen creation with WORKERS reason and keepalive"`

---

## Phase C: Relay Connection

### Task 7: WebSocket Client

**Files:**
- Create: `extension/offscreen/ws-client.js`

- [ ] **Step 1: Implement WsClient class**

```js
export class WsClient {
  constructor() { this.ws = null; this.connected = false; this.backoff = [0,500,1000,2000,4000,8000,16000,30000]; this.attempt = 0; }

  async connect(relayUrl, deviceKeys) {
    // 1. Open WebSocket to relay
    // 2. Wait for auth-challenge
    // 3. Sign challenge||timestamp with Ed25519
    // 4. Send auth-response with deviceId, signature, rendezvousIds
    // 5. Wait for auth-ok
    // 6. Start heartbeat interval (30s)
    // 7. On close: reconnect with exponential backoff
  }

  send(message) { /* JSON.stringify for control, raw for binary */ }
  onMessage(handler) { /* register message callback */ }
  onPresence(handler) { /* register presence change callback */ }
  disconnect() { /* clean close */ }
}
```

Key behaviors: auto-reconnect on close/error, Ed25519 auth, heartbeat ping/pong, warm ping on connect.

- [ ] **Step 2: Manual test** — connect to deployed relay server, verify auth handshake

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): WebSocket client with Ed25519 auth and reconnection"`

---

### Task 8: Presence Tracking

**Files:**
- Modify: `extension/offscreen/transfer-engine.js` — wire presence to chrome.storage.session

- [ ] **Step 1: Handle presence messages from relay**

When relay sends `{type: "presence", deviceId, status, rendezvousId}`:
- Update `chrome.storage.session` devicePresence map
- Send `DEVICE_PRESENCE_CHANGED` to service worker (triggers context menu rebuild)
- Popup reads devicePresence from storage on open

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): presence tracking with storage sync"`

---

## Phase D: Pairing

### Task 9: QR Code Generation + Display

**Files:**
- Create: `extension/popup/pairing.js`
- Modify: `extension/popup/popup.js` — import pairing module

- [ ] **Step 1: Implement QR payload generation in offscreen**

On `START_PAIRING` message: generate QR payload `{v:1, did, epk, xpk, relay}`, send to popup.

- [ ] **Step 2: Implement QR display in popup**

Use qr.js to render QR code to canvas. Show prominently in first-launch state.

- [ ] **Step 3: Add 8-digit PIN below QR** with 60s countdown timer

- [ ] **Step 4: Commit** — `git commit -m "feat(extension): QR code + PIN display for pairing"`

---

### Task 10: Key Exchange Protocol

**Files:**
- Modify: `extension/offscreen/transfer-engine.js`

- [ ] **Step 1: Handle PairRequest from relay**

When Android scans QR and sends PairRequest via relay:
1. Extract peer's ed25519_pk, x25519_pk
2. Verify Ed25519 signature on PairRequest
3. Compute shared_secret = X25519(our_sk, peer_pk)
4. Send PairAccept with our signature

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): X25519 key exchange for pairing"`

---

### Task 11: SAS Verification + Device Naming

**Files:**
- Modify: `extension/popup/pairing.js`
- Modify: `extension/offscreen/transfer-engine.js`

- [ ] **Step 1: Derive and display SAS emoji**

After key exchange: derive SAS bytes via HKDF → map to 4 emoji → send to popup → display with "They Match" / "Cancel" buttons.

- [ ] **Step 2: Device naming screen**

After SAS confirmed: show editable name field (pre-filled from peer), icon picker grid, "Done" button (disabled when empty).

- [ ] **Step 3: Save paired device to chrome.storage.local**

```js
{ deviceId, name, icon, ed25519PublicKey, x25519PublicKey, pairedAt }
```

- [ ] **Step 4: Commit** — `git commit -m "feat(extension): SAS verification and device naming"`

---

## Phase E: Transfer Engine (WebSocket Relay Only)

### Task 12: Transfer State Machine

**Files:**
- Create: `extension/offscreen/transfer-manager.js`
- Create: `extension/test/transfer-manager.test.js`

- [ ] **Step 1: Write state machine tests**

Test transitions: IDLE→REQUESTING, REQUESTING→AWAITING_ACCEPT, accept→TRANSFERRING, decline→DECLINED, timeout→DECLINED, all chunks ACKed→VERIFYING, hash match→COMPLETE, mismatch→FAILED.

- [ ] **Step 2: Implement TransferManager class**

```js
export class TransferManager {
  constructor(wsClient) { this.state = 'IDLE'; this.transfers = new Map(); }

  async initiate(payload) { /* file/text/clipboard → create transfer, send metadata, start timer */ }
  handleAccept(msg) { /* cancel timer, begin sending chunks */ }
  handleDecline(msg) { /* cleanup */ }
  handleChunkAck(msg) { /* update window, send next chunks */ }
  handleIncoming(msg) { /* auto-accept for paired, begin receiving */ }

  // State transitions enforced — invalid transitions throw
}
```

- [ ] **Step 3: Run tests — expect PASS**

- [ ] **Step 4: Commit** — `git commit -m "feat(extension): transfer state machine"`

---

### Task 13: Chunked File Encryption + Adaptive Sizing

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`

- [ ] **Step 1: Implement file chunking with adaptive sizes**

```js
class ChunkSizer {
  constructor() { this.tier = 3; /* 64KB */ this.measurements = []; }
  get size() { return CHUNK_TIERS[this.tier]; }
  recordAck(rttMs, chunkSize) { /* sliding window of 8, adapt tier up/down */ }
}
```

- [ ] **Step 2: Implement per-chunk encrypt with deterministic nonces**

For each chunk: read slice → pad to bucket → derive nonce from (chunkKey, chunkIndex) → XChaCha20-Poly1305 encrypt with AAD → prepend binary header → send.

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): adaptive chunk sizing with per-chunk encryption"`

---

### Task 14: AIMD Flow Control

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`

- [ ] **Step 1: Implement AIMD window**

```js
class FlowController {
  constructor(mode) { this.window = WINDOW_INITIAL; this.inFlight = 0; this.maxWindow = mode === 'relay' ? WINDOW_MAX_RELAY : WINDOW_MAX_DIRECT; this.ackedSinceIncrease = 0; }
  canSend() { return this.inFlight < this.window; }
  onSend() { this.inFlight++; }
  onAck() { this.inFlight--; this.ackedSinceIncrease++; if (this.ackedSinceIncrease >= this.window) { this.window = Math.min(this.window + 1, this.maxWindow); this.ackedSinceIncrease = 0; } }
  onLoss() { this.window = Math.max(Math.floor(this.window / 2), WINDOW_MIN); this.ackedSinceIncrease = 0; }
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): AIMD flow control window"`

---

### Task 15: Send Flow

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`

- [ ] **Step 1: Implement complete send pipeline**

1. User drops file → read as ArrayBuffer
2. Compute SHA-256 of entire file
3. Triple-DH session key exchange with peer
4. Send encrypted transfer-request metadata
5. On accept: begin chunk loop
6. For each chunk: read slice → pad → encrypt → encode header → send via WS
7. Respect flow control window (pause when full)
8. Handle ACKs (update window, adapt chunk size)
9. After final ACK: wait for verify-result
10. Update badge/notification on complete

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): complete send flow over WebSocket relay"`

---

### Task 16: Receive Flow

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`

- [ ] **Step 1: Implement receive pipeline**

1. Incoming transfer-request → auto-accept for paired device
2. Triple-DH session key exchange
3. Receive chunks → decode header → decrypt → unpad → reorder buffer
4. Feed sequential chunks to incremental SHA-256
5. Write completed chunks to Blob
6. After final chunk: finalize hash, compare with metadata
7. On match: save via chrome.downloads.download()
8. Send notification: "[Device] sent you [filename] — [Open] [Save]"

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): complete receive flow with incremental SHA-256"`

---

### Task 17: Clipboard Transfer

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`

- [ ] **Step 1: Implement clipboard fast path**

Send: read clipboard → encrypt as single message → send `clipboard-transfer` (no two-phase handshake).
Receive: decrypt → write to clipboard via `navigator.clipboard.writeText()` → store in clipboardHistory in chrome.storage.session → send notification with preview.

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): clipboard transfer fast path"`

---

## Phase F: Popup UI

### Task 18: First-Launch Pairing State

**Files:**
- Create: `extension/popup/popup.html`
- Create: `extension/popup/popup.js`
- Create: `extension/popup/popup.css`

- [ ] **Step 1: Build first-launch UI**

360px wide, 540px max height. When no paired devices: full-screen QR code + PIN + "Pair Your First Device" text. QR is prominent and large.

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): popup first-launch pairing UI"`

---

### Task 19: Device List + Normal State

**Files:**
- Modify: `extension/popup/popup.js`

- [ ] **Step 1: Device list with online/offline**

Query offscreen for device list. Render cards with: icon, name, status dot (green=online, grey=offline), connection type ("Local" / "Relay"). Click to select as send target.

- [ ] **Step 2: Drag/drop zone + quick-action buttons**

Drop zone: `dragover`/`drop` handlers → read files → initiate transfer. Buttons: Clipboard (read clipboard), Screenshot (captureVisibleTab via SW), Tab URL (current tab URL).

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): popup device list, drag/drop, quick actions"`

---

### Task 20: Transfer Progress + History

**Files:**
- Modify: `extension/popup/popup.js`

- [ ] **Step 1: Active transfer progress**

Read activeTransfers from chrome.storage.session. Show inline progress bar in device card with percentage, speed, ETA, cancel button.

- [ ] **Step 2: Recent transfers + clipboard history**

Last 10 transfers with direction, name, time. Clipboard history expandable section (last 20).

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): popup transfer progress and history"`

---

## Phase G: Chrome Integration

### Task 21: Context Menu

**Files:**
- Modify: `extension/background.js`

- [ ] **Step 1: Dynamic context menu per online device**

On presence change: `chrome.contextMenus.removeAll()` → rebuild with entries per online device for images, links, selected text. No "Send to All".

- [ ] **Step 2: Handle context menu clicks**

Image: SW fetches srcUrl via host permission → passes blob to offscreen. Link: forward linkUrl as text. Text: forward selectionText.

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): dynamic context menu per online device"`

---

### Task 22: Keyboard Shortcuts

**Files:**
- Modify: `extension/background.js`

- [ ] **Step 1: Ctrl+Shift+Z — send clipboard to last-used device**
- [ ] **Step 2: Ctrl+Shift+X — open popup (chrome.action.openPopup)**
- [ ] **Step 3: Commit** — `git commit -m "feat(extension): keyboard shortcuts for clipboard and device picker"`

---

### Task 23: Badge + Notifications

**Files:**
- Modify: `extension/background.js`

- [ ] **Step 1: Badge progress**

On UPDATE_BADGE from offscreen: set badge text (percentage, checkmark, error), background color (blue, green, red). Throttle to 1/second.

- [ ] **Step 2: Notifications with actions**

Completion: "Sent [file] to [device]". Receive: "[Device] sent you [file] — [Open] [Save]". Failure: "Transfer failed — [Retry]". Handle button clicks via `chrome.notifications.onButtonClicked`.

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): badge progress and transfer notifications"`

---

## Phase H: Resume + Recovery

### Task 24: State Checkpointing

**Files:**
- Create: `extension/offscreen/checkpoint.js`

- [ ] **Step 1: Implement checkpoint write every 10 chunks**

```js
export async function saveCheckpoint(transfer) {
  const checkpoint = { transferId, peerId, direction, chunkOffset, sessionKeyMaterial, fileMetadata, timestamp: Date.now() };
  const checkpoints = await chrome.storage.session.get('transferCheckpoints') || {};
  checkpoints[transfer.id] = checkpoint;
  await chrome.storage.session.set({ transferCheckpoints: checkpoints });
}
export async function loadCheckpoint(transferId) { /* read and return, null if expired (>5min) */ }
export async function clearCheckpoint(transferId) { /* remove from storage */ }
```

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): transfer state checkpointing"`

---

### Task 25: Offscreen Crash Recovery

**Files:**
- Modify: `extension/offscreen/transfer-engine.js`

- [ ] **Step 1: On offscreen startup, check for checkpoints**

Read transferCheckpoints from chrome.storage.session. For each non-expired checkpoint: reconnect to relay, send resume-request to peer, resume from lastChunkOffset.

- [ ] **Step 2: SW detects offscreen death**

In keepalive alarm handler: if sendMessage fails, recreate offscreen. New offscreen reads checkpoints and resumes.

- [ ] **Step 3: Commit** — `git commit -m "feat(extension): offscreen crash recovery from checkpoints"`

---

## Phase I: WebRTC P2P (Optimization)

### Task 26: WebRTC Peer Connection

**Files:**
- Create: `extension/offscreen/webrtc-manager.js`

- [ ] **Step 1: Implement WebRTC manager**

```js
export class WebRTCManager {
  constructor() { this.peerConnections = new Map(); }

  async createConnection(peerId) {
    const pc = new RTCPeerConnection({ iceServers: STUN_SERVERS, iceCandidatePoolSize: 2 });
    const controlChannel = pc.createDataChannel('zap-control', { ordered: true });
    // Handle onicecandidate → send via relay signaling
    // Handle ondatachannel → register data channel handlers
    return pc;
  }

  createDataChannel(pc, transferId) {
    return pc.createDataChannel(`zap-data-${transferId}`, { ordered: false });
  }
}
```

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): WebRTC peer connection manager"`

---

### Task 27: Parallel Path Racing

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`
- Modify: `extension/offscreen/webrtc-manager.js`

- [ ] **Step 1: Start relay + ICE simultaneously**

On transfer initiate: begin WebSocket relay handshake AND WebRTC offer/answer. First path ready → start sending. If relay first → send over WS, continue ICE in background.

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): parallel path racing relay + P2P"`

---

### Task 28: Mid-Transfer Path Upgrade

**Files:**
- Modify: `extension/offscreen/transfer-manager.js`

- [ ] **Step 1: Upgrade from relay to P2P mid-transfer**

When DataChannel opens during active relay transfer: send path-upgrade message with nextChunkOffset → peer ACKs → switch chunk sending to DataChannel → send relay-release to server.

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): mid-transfer relay to P2P upgrade"`

---

### Task 29: ICE Restart on Network Change

**Files:**
- Modify: `extension/offscreen/webrtc-manager.js`

- [ ] **Step 1: Handle network change**

Listen for `navigator.connection` change and `online`/`offline` events. On change during active P2P: call `pc.restartIce()` → create new offer with `{iceRestart: true}` → exchange via relay. During restart, bridge data through relay.

- [ ] **Step 2: Commit** — `git commit -m "feat(extension): ICE restart on network change"`

---

## Completion Checklist

- [ ] All phases A-I implemented
- [ ] Crypto tests passing (`node --test`)
- [ ] Manual test: pair with Android app via QR
- [ ] Manual test: send text clipboard Chrome → Android
- [ ] Manual test: send file Chrome → Android over relay
- [ ] Manual test: send file with P2P upgrade on same network
- [ ] Manual test: context menu image send
- [ ] Manual test: Ctrl+Shift+Z clipboard shortcut
- [ ] Manual test: badge shows progress, notification on complete
- [ ] Manual test: resume after offscreen kill (close DevTools)
