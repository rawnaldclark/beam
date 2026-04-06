# ZapTransfer — Complete Design Specification

**Version:** 1.0.0
**Date:** 2026-04-04
**Status:** Approved for Implementation

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Requirements](#2-requirements)
3. [Architecture Overview](#3-architecture-overview)
4. [Security and Cryptography](#4-security-and-cryptography)
5. [Transfer Protocol](#5-transfer-protocol)
6. [Networking and Relay Server](#6-networking-and-relay-server)
7. [Chrome Extension](#7-chrome-extension)
8. [Android Application](#8-android-application)
9. [Implementation Roadmap](#9-implementation-roadmap)

---

## 1. Executive Summary

ZapTransfer is a cross-device file transfer system: **Chrome Extension** (MV3) + **Android App** (Kotlin/Compose) + **Relay Server** (Node.js on Fly.io free tier). Transfers files, text, images, URLs of any size between paired devices instantly and securely.

### Core Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Connection | Hybrid WebRTC P2P + WebSocket relay fallback | P2P ~85% of transfers; relay guarantees 100% |
| TURN servers | None | WebSocket relay replaces TURN |
| mDNS/NSD | None | ICE host candidates handle same-network for free |
| Auth | QR code / 8-digit PIN, no accounts | Zero user data stored |
| Crypto | libsodium everywhere | `libsodium.js` + `lazysodium-android` = identical wire format |
| Forward secrecy | Ephemeral X25519 per transfer | Static key compromise can't decrypt past transfers |
| PIN pairing | SPAKE2 | PIN never transmitted, resists offline brute-force |
| Chrome transfer | Offscreen document (not service worker) | SWs die after 30s |
| Accept/decline | Auto-accept for paired devices | Paired = trusted |
| Chunks | Adaptive 8KB-512KB | Fast on LAN, resilient on mobile |
| Flow control | AIMD window 2-64 chunks | Self-tuning |

---

## 2. Requirements

**Functional:** Transfer any size file between paired devices. Work on same network and across networks. QR/PIN pairing (no accounts). Chrome: context menu + popup + hotkeys. Android: standalone app hub. Receiving: notification → user decides. Resume after interruption. E2E encrypted.

**Non-Functional:** XChaCha20-Poly1305 + forward secrecy + zero-knowledge relay. P2P speed with adaptive chunking. <30s pairing, 4 settings max. Survives network switches, Doze, server restarts. Free-tier infrastructure only.

---

## 3. Architecture Overview

```
Chrome Extension                           Android App
┌────────────────────┐              ┌────────────────────┐
│ SW (thin shell)    │              │ Jetpack Compose UI │
│ Offscreen Document │              │ Foreground Service │
│  - WebRTC          │              │  - WebRTC (stream) │
│  - WebSocket       │              │  - OkHttp WS       │
│  - libsodium.js    │              │  - lazysodium      │
└────────┬───────────┘              └────────┬───────────┘
         │   1. Direct P2P (same network)    │
         │◄═════════════════════════════════►│
         │   2. STUN P2P (cross-network)     │
         │◄═════════════════════════════════►│
         │   3. WebSocket Relay (fallback)   │
         │◄──────┐              ┌───────────►│
         │    ┌──▼──────────────▼──┐         │
         │    │  Relay (Fly.io)    │         │
         │    │  Signaling + Relay │         │
         │    │  Zero-knowledge    │         │
         │    └────────────────────┘         │
```

**Parallel path:** Relay + ICE start simultaneously. First ready wins. Mid-transfer P2P upgrade if relay started first.

**Stack:** Chrome (MV3, libsodium.js, WebRTC, qr.js) | Android (Kotlin, Compose, stream-webrtc-android, lazysodium, ML Kit) | Server (Node.js, ws, Fly.io) | Crypto (X25519, Ed25519, XChaCha20-Poly1305, HKDF-SHA256, SPAKE2)

---

## 4. Security and Cryptography

### 4.1 Primitives (all libsodium)

| Purpose | Primitive | Function |
|---|---|---|
| Key agreement | X25519 | `crypto_scalarmult` |
| Signatures | Ed25519 | `crypto_sign_detached` / `_verify_detached` |
| AEAD | XChaCha20-Poly1305 | `crypto_aead_xchacha20poly1305_ietf_encrypt` |
| KDF | HKDF-SHA-256 | `crypto_kdf_hkdf_sha256_extract` + `_expand` |
| Hash | SHA-256 | `crypto_hash_sha256` |
| PIN pairing | SPAKE2 | See 4.4.3 |

### 4.2 Device Identity

**First launch:** generate X25519 keypair (DH) + Ed25519 keypair (signing).

**Device ID:** `Base64url(SHA-256(ed25519_pk)[0:16])` — 22 chars, derived from public key.

**Relay registration:** Server issues 32-byte challenge → device signs `challenge || timestamp` with Ed25519 → server verifies signature + derives device ID from public key. Rejects if timestamp >30s old.

### 4.3 Key Storage

**Android:** Ed25519 signing key in Android Keystore (hardware-backed). X25519 private in EncryptedSharedPreferences. Ephemeral session keys in memory only (`sodium_memzero` after use).

**Chrome:** Private keys encrypted with wrapping key (HKDF from device entropy) in `chrome.storage.local`. Ephemeral keys in `chrome.storage.session` (cleared on browser close).

### 4.4 Pairing

**Roles (fixed):** Chrome DISPLAYS QR codes. Android SCANS them. Never reversed.

#### 4.4.1 QR Pairing

QR payload: `{v:1, did, epk (ed25519), xpk (x25519), relay URL}` (~200 bytes).

Flow: Chrome shows QR → Android scans → key exchange via relay → both derive shared_secret via `crypto_scalarmult` → SAS verification → device naming → pairing saved.

#### 4.4.2 SAS Verification (Post-Pairing)

`sas_bytes = HKDF(shared_secret, salt=ed25519_pk_chrome || ed25519_pk_android, info="zaptransfer-sas-v1", len=8)`. Display as 4 emoji (each 2-byte pair mod 256 indexes into fixed 256-emoji table). User visually confirms match on both devices.

#### 4.4.3 PIN Pairing (SPAKE2)

8-digit PIN, 60s expiry, 3 attempts max per 5-min window. SPAKE2 protocol: PIN never transmitted. Both sides derive `w = HKDF(pin, salt, info)`, compute blinded points on Curve25519, exchange, derive shared secret, verify via HMAC confirmation. If SPAKE2 not available in libsodium at ship time, CPace is acceptable.

### 4.5 Per-Transfer Forward Secrecy (Triple DH)

Each transfer: both generate ephemeral X25519 keypairs.

```
dh1 = DH(ephA, staticB)
dh2 = DH(staticA, ephB)
dh3 = DH(ephA, ephB)
session_key = HKDF(dh1 || dh2 || dh3, salt=random_32B, info="zaptransfer-session")
```

Static keys authenticate (Ed25519 signatures on ephemeral public keys). Ephemeral keys derive traffic keys. Ephemeral private keys zeroed immediately after derivation.

**Sub-keys:** `chunk_key = HKDF(session_key, info="zaptransfer-chunk-encryption")`, `metadata_key = HKDF(session_key, info="zaptransfer-metadata-encryption")`.

### 4.6 Per-Chunk Encryption

**Padding:** Random padding to power-of-2 buckets (64KB, 128KB, 256KB, 512KB). Layout: `uint32_LE(plaintext_len) || plaintext || random_padding`.

**Nonce (deterministic):** `nonce = HKDF(chunk_key, info="chunk-nonce" || uint64_LE(chunkIndex), len=24)`. Unique per (session_key, chunkIndex). Reproducible after crash.

**AEAD:** `crypto_aead_xchacha20poly1305_ietf_encrypt(padded_chunk, aad, nonce, chunk_key)`. AAD: `"zaptransfer-chunk-v1" || uint64(chunkIndex) || uint64(totalChunks) || SHA256(metadata_ciphertext)`. Binds chunk to position, prevents reordering/truncation.

**Decryption failure:** abort entire transfer, discard all chunks, notify sender.

### 4.7 Metadata Protection

All file metadata (name, size, type, hash) encrypted with `metadata_key` before transmission. Transfer metadata envelope: `nonce(24B) || xchacha20poly1305(metadata_json, aad, nonce, metadata_key)`.

### 4.8 Relay Zero-Knowledge

**Rendezvous IDs:** `HKDF(shared_secret, salt=UTC_day_number, info="zaptransfer-rendezvous-id", len=16)`. Rotates daily. Server cannot link rendezvous IDs to device IDs without shared secret.

**Server sees:** rendezvous IDs, encrypted chunks (opaque), chunk count (mitigated by padding), timing. **Never sees:** plaintext, file names, keys, device pairings.

### 4.9 Wire Format

```
Byte 0:    version (0x01)
Byte 1:    message_type (0x01=TransferInit, 0x10=Metadata, 0x20=Chunk, 0x21=ChunkAck, 0x30=Control)
Bytes 2-17:  rendezvous_id (16B)
Bytes 18-21: payload_length (uint32_BE)
Bytes 22-N:  payload (encrypted)
Bytes N-N+64: Ed25519 signature (over all preceding bytes)
```

---

## 5. Transfer Protocol

### 5.1 State Machine

```
IDLE → REQUESTING → AWAITING_ACCEPT → TRANSFERRING → VERIFYING → COMPLETE
                                    → DECLINED
                         TRANSFERRING → PAUSED → TRANSFERRING (resume)
                         TRANSFERRING → FAILED
```

- Strict two-phase handshake: sender sends metadata only, MUST NOT send data until explicit accept
- Acceptance timeout: 60 seconds
- Auto-accept default for paired devices (no prompt)

### 5.2 Message Formats

**Transfer Request:** `{transferId (UUID), fileName, fileSize, mimeType, totalChunks, initialChunkSize, sha256 (hex), contentCategory (clipboard|small|medium|large), resumable}`

**Chunk (binary header, 64 bytes):** `type(1B) | transferId(16B) | chunkIndex(4B uint32) | byteOffset(8B uint64) | chunkSize(4B uint32) | flags(1B, bit0=isFinal) | reserved(30B)` + encrypted chunk data.

**Chunk ACK:** `{ackedChunkIndex, cumulativeAck, receiveWindowRemaining, measuredRttMs}`

**Clipboard (single message):** `{content (UTF-8), sha256, autoCopy:true}` — bypasses two-phase handshake.

### 5.3 Adaptive Chunk Sizing

Tiers: 8KB → 16KB → 32KB → **64KB (default start)** → 128KB → 256KB → 512KB (max).

Measure throughput over sliding window of last 8 ACKs. **Increase** (up one tier): all 8 ACKs loss-free, no RTT spike, throughput >=90% of previous. Cooldown 8 ACKs. **Decrease** (down one tier): any loss or RTT spike. Cooldown 4 ACKs.

### 5.4 AIMD Flow Control

Initial window: 4 chunks. Min: 2. Max: 64 (direct) / 8 (relay). **Additive increase:** +1 after full window ACKed without loss. **Multiplicative decrease:** halve on any loss/timeout.

### 5.5 WebRTC Data Channel Config

| Channel | Ordered | Purpose |
|---|---|---|
| `zap-control` | true (reliable) | Control messages (JSON) |
| `zap-data-{transferId}` | **false** (reliable) | Chunk data (binary) |

Unordered eliminates SCTP head-of-line blocking. App layer handles ordering via chunkIndex.

### 5.6 Content-Type Handling

| Category | Size | Behavior |
|---|---|---|
| Clipboard | <1KB | Single encrypted message, auto-copy to clipboard, no progress bar |
| Small | <1MB | Two-phase handshake, all chunks batched, minimal progress |
| Medium | 1-100MB | Streaming with progress bar, adaptive chunk/window |
| Large | 100MB+ | Streaming + mandatory resume support, periodic checkpoints |

### 5.7 Integrity

SHA-256 computed incrementally (`MessageDigest.update` / streaming hash lib). Chunks fed to hash in sequence order; reorder buffer (max 32 entries) for out-of-order arrival. Final hash compared against sender's hash from metadata envelope.

### 5.8 Resume Protocol

**Receiver persists** (every 50 chunks or 10s): `{transferId, lastAckedChunkIndex, bytesReceived, incrementalHashState, sessionKeyDerivationMaterial, fileMetadata}`. Encrypted at rest. Expires after 24h.

**Resume handshake:** receiver sends `{transferId, lastChunkIndex}` → sender validates, seeks to offset, sends `resume-ack` → chunks resume from `lastChunkIndex + 1`.

**Nonce safety:** deterministic nonces from chunkIndex, so encryption state is fully reconstructable.

### 5.9 Backpressure

Sender pauses when `inFlight >= windowSize`. WebRTC: pause when `bufferedAmount >= 1MB`, resume on `bufferedamountlow` at 512KB. Relay: server stops reading from sender when receiver's write buffer >2MB → TCP backpressure propagates.

---

## 6. Networking and Relay Server

### 6.1 Connection Priority

1. **WebRTC P2P via STUN (UDP)** — 1-5s to ready
2. **WebRTC P2P via ICE-TCP** — 3-8s to ready (for UDP-blocking firewalls)
3. **WebSocket relay** — <1s to ready (guaranteed)

**Excluded:** TURN (relay replaces it), mDNS (ICE host candidates handle local).

### 6.2 Parallel Path Racing

Relay + ICE start simultaneously. Relay is always ready first (<500ms). Transfer begins over relay. If P2P succeeds during transfer → `path-upgrade` message on new DataChannel → sender resumes over P2P → `relay-release` frees server resources.

### 6.3 ICE Config

**STUN servers:** `stun.l.google.com:19302`, `stun1.l.google.com:19302`, `stun.services.mozilla.com:3478`, `stun.cloudflare.com:3478`.

ICE gathering timeout: **5s** (not default 30s — relay is already available). Connectivity check timeout: **8s**. Aggressive nomination. Candidate trickling enabled. `iceCandidatePoolSize: 2`.

**Network change:** ICE restart (`iceRestart: true` in createOffer), NOT full renegotiation. Android: `ConnectivityManager.NetworkCallback` triggers proactive restart. During ICE restart, relay bridges data.

### 6.4 Relay Server Architecture

Single Node.js process, three modules:

- **WebSocket Gateway:** WSS connections, Ed25519 auth, rate limiting, connection mgmt
- **Signaling:** SDP relay, ICE candidate forwarding, rendezvous resolution, presence notifications
- **Data Relay:** Encrypted chunk passthrough, backpressure piping, bandwidth tracking

**In-memory state:** `devices Map<deviceId, ws>`, `rendezvous Map<rendezvousId, Set<deviceId>>`, `sessions Map<transferId, {senderWs, receiverWs, bytesRelayed}>`, `ipConnections Map<ip, count>`, `bandwidth {monthTotal}`.

### 6.5 Auth Flow

Client connects → server sends 32-byte challenge → client signs with Ed25519 + sends deviceId + rendezvousIds → server verifies signature, confirms `SHA256(pubkey)[0:16] == deviceId`, registers device.

### 6.6 Rate Limits & Protection

| Limit | Value |
|---|---|
| WS connections per IP | 5 |
| Messages/sec per connection | 50 |
| Max relay per session | 500 MB |
| Max concurrent devices | 50 |
| Max message size (text) | 64 KB |
| Max message size (binary) | 256 KB |

**Bandwidth quota:** At 80% of 160GB monthly (128GB), disable relay, P2P-only mode, notify clients. Signaling continues.

### 6.7 Presence

Client ping every 30s. Server marks offline after 90s silence. Immediate offline on WS close. Best-effort, not guaranteed — clients should probe before assuming online.

### 6.8 Reconnection

Exponential backoff: 0ms, 500ms, 1s, 2s, 4s, 8s, 16s, 30s cap. On reconnect, clients send `{type:"reconnect", deviceId, sig, activeTransferId, lastChunkOffset}`. Server matches two reconnecting clients → re-establishes relay.

### 6.9 Deployment

**Primary:** Fly.io free tier — shared-cpu-1x, 256MB RAM, 160GB outbound/month, auto-sleep/wake (~3s cold start). TLS terminated by Fly.io edge.

**Backup:** Oracle Cloud always-free ARM VM — 1GB RAM, 10TB outbound. Same Docker image. Caddy for TLS.

**Client failover:** Try primary 5s → try backup. Periodically probe primary if on backup.

---

## 7. Chrome Extension

### 7.1 Architecture

**Service Worker (thin shell):** Extension lifecycle events, context menu creation, keyboard shortcut dispatch, alarm handlers, badge/notification updates, cross-origin image fetch. **Holds no state.**

**Offscreen Document (fat engine):** WebRTC, WebSocket, libsodium crypto, chunked transfer, flow control, state management. **Single source of truth.** Created with `WORKERS` reason.

**Popup:** Reads from `chrome.storage.session`, queries offscreen via messages. Never holds state.

### 7.2 Offscreen Lifecycle

Created on extension startup or first transfer. `chrome.alarms` keepalive every 25s prevents SW termination. On crash: SW detects (message timeout), recreates offscreen, offscreen reads checkpoint from `chrome.storage.session`.

**Checkpoint** (every 10 chunks): `{transferId, peerId, direction, chunkOffset, sessionKeyDerivationMaterial, fileMetadata, timestamp}`. Expired after 5 min.

### 7.3 Manifest V3

```
permissions: offscreen, storage, notifications, contextMenus, commands, alarms, activeTab
host_permissions: <all_urls>  (for cross-origin image fetch)
commands: Ctrl+Shift+Z (send clipboard), Ctrl+Shift+X (device picker)
```

### 7.4 File Structure

```
zaptransfer-extension/
├── manifest.json
├── background.js              # Service worker
├── offscreen/
│   ├── transfer-engine.html   # Offscreen document
│   └── transfer-engine.js     # All transfer/crypto logic
├── popup/
│   ├── popup.html/js/css      # Popup UI
├── lib/
│   ├── sodium.js              # libsodium.js (~200KB)
│   └── qr.js                  # QR generation (~5KB)
└── shared/
    └── constants.js           # Message types, config
```

### 7.5 Popup UI States

**First launch (no devices):** Full-screen pairing flow. QR code displayed prominently + 8-digit PIN below. Empty state IS the pairing screen.

**Normal (devices paired):** Device list (online/offline, local/remote), drag/drop zone, quick-action buttons (Clipboard, Screenshot, Tab URL), recent transfers (last 10), clipboard history link.

**Active transfer:** Device card expands with progress bar, speed, ETA, cancel button.

### 7.6 Context Menu

Dynamic entries per online device: "Send image/link/text to [Device Name]". No "Send to All". Rebuilt on presence change. Image fetch: SW uses `fetch(srcUrl)` with host permission → passes blob to offscreen.

### 7.7 Progress Visibility (Popup Closed)

Badge text: `"47%"` (blue), `"✓"` (green, 3s), `"!"` (red). Notifications on complete/fail/receive with action buttons (Open, Save, Copy, Retry).

### 7.8 Receiving

Auto-accept for paired devices. Files → download + notification. Text → auto-copy to clipboard + notification with preview. URLs → notification with "Open in New Tab". Clipboard history: last 20 items in `chrome.storage.session`.

### 7.9 Storage

**`chrome.storage.local`:** deviceId, encrypted keypairs, pairedDevices[], settings (autoAccept, downloadPath).

**`chrome.storage.session`:** sessionKeys, transferCheckpoints, activeTransfers, devicePresence, transferHistory (10), clipboardHistory (20), lastUsedDeviceId.

---

## 8. Android Application

### 8.1 Tech Stack

Kotlin, Jetpack Compose (Material 3), `io.getstream:stream-webrtc-android` (~8MB/ABI), OkHttp WebSocket, `lazysodium-android`, ML Kit Barcode, Room (KSP), Hilt, Android App Bundles (ABI splits). Min SDK 26.

### 8.2 Package Structure

```
com.zaptransfer.android/
├── ui/
│   ├── devicehub/     # Main screen, device cards, empty state onboarding
│   ├── pairing/       # QR scanner, PIN entry, SAS verification, device naming
│   ├── transfer/      # Progress screen, complete bottom sheet
│   ├── clipboard/     # History screen
│   ���── settings/      # 4 settings only
│   └── components/    # Shared composables
├── service/
│   ├── TransferForegroundService.kt  # FOREGROUND_SERVICE_DATA_SYNC
│   ├── NetworkMonitor.kt             # ConnectivityManager.NetworkCallback
│   └── WakeLockManager.kt            # PARTIAL_WAKE_LOCK + WifiLock
├── webrtc/
│   ├── PeerConnectionManager.kt      # ICE, SDP, data channels
│   ├── IceRestartPolicy.kt           # Network-change ICE restart
│   └── SignalingClient.kt            # OkHttp WS signaling
├── crypto/
│   ├── KeyManager.kt                 # Keystore + EncryptedSharedPrefs
│   ├── SessionCipher.kt              # XChaCha20-Poly1305 per chunk
│   └── HashAccumulator.kt            # Incremental SHA-256
├── data/
│   ├── db/ (Room)
│   │   ├── PairedDeviceEntity        # deviceId, name, icon, keys, pairedAt
│   │   ├── TransferHistoryEntity     # transferId, direction, file info, status
│   │   ├── ChunkProgressEntity       # transferId, lastAckedChunk, tempPath, sha256State
│   │   ├── ClipboardEntryEntity      # content, isUrl, receivedAt (max 20)
│   │   └── OfflineQueueEntity        # targetDevice, type, contentOrUri, expiresAt (max 10)
│   └── repository/
└── util/                              # File helpers, MIME types, notifications
```

### 8.3 Screens

**Device Hub:** Device cards (name, icon, online/offline, connection type, Send File/Send Text buttons). Recent transfers list. Empty state = full-screen pairing CTA. FAB: "+ Pair Device".

**Pairing Flow:** QR Scanner (ML Kit + CameraX) → PIN Entry (8-digit fallback) → SAS Verification (4 emoji with labels) → Device Naming (required, editable field + icon picker).

**Transfer Progress:** File name, size, progress bar, speed, ETA, connection type, cancel button. Status: IN_PROGRESS, PAUSED_RECONNECTING, VERIFYING, FAILED, COMPLETE.

**Transfer Complete (bottom sheet):** Open File / Save to Downloads / Save to Custom Location / Dismiss.

**Settings (exactly 4):** Save location, auto-accept toggle (on by default), device name, paired devices list (with unpair).

**Clipboard History:** Last 20 items, copy button, "Open in Browser" for URLs.

### 8.4 Foreground Service

Type: `FOREGROUND_SERVICE_DATA_SYNC` (Android 14+). `PARTIAL_WAKE_LOCK` + `WifiLock` during active transfer. Persistent notification with progress. Survives activity destruction.

**Doze survival:** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` requested during onboarding (critical for Samsung/Xiaomi/Huawei). Chunk progress persisted to Room (not just memory) — survives process kill.

**Process death recovery:** On `Application.onCreate`, query `chunk_progress` for incomplete transfers → start service → reconnect → resume from last chunk.

### 8.5 Network Change

`ConnectivityManager.NetworkCallback`: WiFi→cellular triggers proactive ICE restart (not wait for timeout). During reconnection, relay bridges data. Cellular→WiFi attempts upgrade back to local P2P.

### 8.6 Receiving

Auto-accept for paired devices (configurable). Files: temp dir during transfer → final location on verify. Text: auto-copy to clipboard + notification preview + clipboard history. URLs: notification with "Open in Browser". Partial file cleanup: delete temps >24h old on app start.

### 8.7 Offline Queue (Stretch Goal)

When target offline: save intent to Room (file URI or text content, target device, 24h TTL, max 10 per device). Auto-send when device comes online. Snackbar: "Queued for Work Laptop."

### 8.8 Permissions

`INTERNET`, `CAMERA` (QR), `FOREGROUND_SERVICE` + `_DATA_SYNC`, `WAKE_LOCK`, `POST_NOTIFICATIONS` (API 33+), `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`.

### 8.9 APK Size

With ABI splits: ~17MB download (Compose ~4MB, OkHttp ~1MB, Room ~0.5MB, ML Kit ~2MB, native libs ~8.5MB, app ~1MB).

---

## 9. Implementation Roadmap

### Build Order

| Phase | Scope | Effort |
|---|---|---|
| 1. Relay Server | WS signaling + relay + Fly.io deploy | 1 week |
| 2. Chrome MVP | Popup UI, WS connection, text/link send (relay only) | 2 weeks |
| 3. Android MVP | Compose UI, WS connection, receive, file picker | 3 weeks |
| 4. Pairing + Crypto | QR/PIN, SPAKE2, SAS, libsodium E2E | 1.5 weeks |
| 5. File Transfer | Chunking, adaptive sizing, AIMD, progress, resume | 2 weeks |
| 6. WebRTC P2P | ICE, data channels, parallel racing, mid-transfer upgrade | 2.5 weeks |
| 7. Polish | Context menus, shortcuts, notifications, service, badges | 2 weeks |
| 8. Testing | Cross-platform crypto, network edges, resume | 1.5 weeks |

**Total: ~16 weeks at 15 hrs/week, or ~6-8 weeks full-time.**

**Critical insight:** Build entirely on WebSocket relay first (Phases 1-5). This is a fully working product. WebRTC (Phase 6) is a performance optimization, not the core.

---

*End of ZapTransfer Design Specification*
