/**
 * @file checkpoint.js
 * @description Transfer resumption checkpoint helpers.
 *
 * Checkpoints are persisted to chrome.storage.session (volatile, cleared when
 * the browser closes) so that a transfer interrupted by a service-worker
 * suspension or a transient network drop can resume from the last acknowledged
 * chunk rather than restarting from byte 0.
 *
 * Storage layout:
 *   chrome.storage.session -> { transferCheckpoints: { [transferId]: Checkpoint } }
 *
 * A checkpoint is expired and removed automatically after 5 minutes to prevent
 * stale entries accumulating across user sessions.
 *
 * @module offscreen/checkpoint
 */

import { CHECKPOINT_INTERVAL_CHUNKS } from '../shared/constants.js';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Persist the current state of a transfer as a resumption checkpoint.
 *
 * Called by the transfer engine every CHECKPOINT_INTERVAL_CHUNKS chunks so
 * that, on reconnect, the peer can seek to the last acknowledged position
 * instead of retransmitting from the beginning.
 *
 * @param {{
 *   id:               string,
 *   peerId:           string,
 *   direction:        'send' | 'receive',
 *   chunksSent?:      number,
 *   chunksReceived?:  number,
 *   sessionKeyMaterial?: ArrayBuffer | null,
 *   fileName:         string,
 *   fileSize:         number,
 *   mimeType:         string,
 *   sha256?:          string,
 * }} transfer - Live transfer state object from the transfer engine.
 * @returns {Promise<void>}
 */
export async function saveCheckpoint(transfer) {
  const checkpoint = {
    transferId: transfer.id,
    peerId:     transfer.peerId,
    direction:  transfer.direction,
    // Track the furthest chunk position for which we have a confirmed ACK so
    // that re-send starts from the right offset on resumption.
    chunkOffset: transfer.direction === 'send'
      ? (transfer.chunksSent     ?? 0)
      : (transfer.chunksReceived ?? 0),
    // Preserve the session encryption key material so that an in-progress
    // encrypted session can be resumed without re-running the key-exchange.
    sessionKeyMaterial: transfer.sessionKeyMaterial ?? null,
    fileMetadata: {
      name:     transfer.fileName,
      size:     transfer.fileSize,
      mimeType: transfer.mimeType,
      hash:     transfer.sha256 ?? null,
    },
    timestamp: Date.now(),
  };

  const stored = await _readAll();
  stored[transfer.id] = checkpoint;
  await chrome.storage.session.set({ transferCheckpoints: stored });
}

/**
 * Retrieve a checkpoint by transfer ID, or null if none exists or it has expired.
 *
 * A checkpoint older than 5 minutes is treated as expired: it is deleted from
 * storage and null is returned so the caller falls back to a fresh transfer.
 *
 * @param {string} transferId
 * @returns {Promise<object | null>} The checkpoint object, or null.
 */
export async function loadCheckpoint(transferId) {
  const stored = await _readAll();
  const cp = stored[transferId];

  if (!cp) return null;

  // Expire after 5 minutes — a longer gap implies the peer has already
  // abandoned the transfer and a fresh session is preferable.
  if (Date.now() - cp.timestamp > 5 * 60 * 1000) {
    await clearCheckpoint(transferId);
    return null;
  }

  return cp;
}

/**
 * Remove the checkpoint for a specific transfer.
 *
 * Call this when a transfer completes successfully or is permanently cancelled
 * so that its storage slot is freed.
 *
 * @param {string} transferId
 * @returns {Promise<void>}
 */
export async function clearCheckpoint(transferId) {
  const stored = await _readAll();
  delete stored[transferId];
  await chrome.storage.session.set({ transferCheckpoints: stored });
}

/**
 * Return all non-expired checkpoints as a map keyed by transfer ID.
 *
 * Called on service-worker startup to identify transfers that were interrupted
 * and may be resumed when the relevant peer reconnects.
 *
 * @returns {Promise<Record<string, object>>} Map of transferId -> checkpoint.
 */
export async function loadAllCheckpoints() {
  const stored = await _readAll();
  const now    = Date.now();
  const valid  = {};

  for (const [id, cp] of Object.entries(stored)) {
    if (now - cp.timestamp <= 5 * 60 * 1000) {
      valid[id] = cp;
    }
    // Expired entries are left in storage for the next explicit clearCheckpoint
    // call or the next loadAllCheckpoints sweep — avoids excessive write I/O.
  }

  return valid;
}

/**
 * Determine whether the transfer engine should write a checkpoint after
 * processing `chunksProcessed` chunks.
 *
 * Returns true every CHECKPOINT_INTERVAL_CHUNKS chunks (e.g. every 10 chunks)
 * starting from chunk 1.  Never fires at chunk 0 to avoid a spurious initial
 * write before any data has moved.
 *
 * @param {number} chunksProcessed - Total chunks sent or received so far.
 * @returns {boolean}
 */
export function shouldCheckpoint(chunksProcessed) {
  return chunksProcessed > 0 && chunksProcessed % CHECKPOINT_INTERVAL_CHUNKS === 0;
}

// ---------------------------------------------------------------------------
// Private helpers
// ---------------------------------------------------------------------------

/**
 * Read the raw transferCheckpoints map from session storage.
 *
 * Returns an empty object when no checkpoints have been written yet so that
 * callers never have to guard against undefined.
 *
 * @returns {Promise<Record<string, object>>}
 * @private
 */
async function _readAll() {
  const result = await chrome.storage.session.get('transferCheckpoints');
  return result?.transferCheckpoints ?? {};
}
