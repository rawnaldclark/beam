/**
 * @file webrtc-manager.js
 * @description WebRTC peer-connection manager for the Beam offscreen document.
 *
 * ## Architecture
 *
 * WebRTCManager owns all RTCPeerConnection instances and their DataChannels for
 * every paired peer.  It is the single choke-point through which WebRTC
 * signaling (SDP offer/answer, trickle ICE) flows and the single source of
 * truth about whether a direct P2P path is live for a given peer.
 *
 * ### Channel topology (per peer)
 *   - `beam-control`          — ordered, reliable; carries JSON control messages
 *                               (transfer requests, ACKs, resume signals).
 *   - `beam-data-{transferId}` — unordered, reliable; avoids SCTP head-of-line
 *                               blocking when multiple transfers run in parallel.
 *
 * ### Path racing
 * Chunk data is sent over whichever path is available — P2P DataChannel when
 * `isConnected(peerId)` returns true, relay otherwise.  When a P2P channel
 * opens mid-transfer, `_onConnectedCallback` fires and the transfer engine can
 * upgrade to the faster path immediately.
 *
 * ### ICE restart on network change
 * When `navigator.connection` or `online/offline` events indicate a path
 * disruption, the transfer engine calls `restartIce(peerId)`, which generates a
 * new ICE offer and temporarily keeps the relay path hot as a bridge.
 *
 * ### Rendezvous ID lookup
 * ICE candidates must be routed to the correct relay rendezvous room.
 * `setRendezvousLookup(fn)` injects a closure from `transfer-engine.js` that
 * maps a peer device ID to its stored rendezvous ID without creating a circular
 * import dependency.
 *
 * @module offscreen/webrtc-manager
 */

import {
  STUN_SERVERS,
  ICE_GATHERING_TIMEOUT_MS,
  ICE_CHECK_TIMEOUT_MS,
} from '../shared/constants.js';
import { WIRE } from '../shared/message-types.js';

// ---------------------------------------------------------------------------
// WebRTCManager
// ---------------------------------------------------------------------------

/**
 * Manages WebRTC peer connections for all paired Beam devices.
 *
 * One RTCPeerConnection is maintained per remote device ID.  Creating a new
 * connection while one already exists will close the old one first.
 */
export class WebRTCManager {
  /**
   * @param {import('./ws-client.js').WsClient} wsClient
   *   The authenticated relay WebSocket client, used to deliver trickle ICE
   *   candidates and SDP messages during the signaling phase.
   */
  constructor(wsClient) {
    /** @type {import('./ws-client.js').WsClient} Relay signaling transport. */
    this.ws = wsClient;

    /**
     * Active RTCPeerConnection instances keyed by peer device ID.
     * @type {Map<string, RTCPeerConnection>}
     */
    this.connections = new Map();

    /**
     * DataChannel registry keyed by peer device ID.
     * Each entry is an object with `control` and optional per-transfer `data`
     * channel references: `{ control: RTCDataChannel, [transferId]: RTCDataChannel }`.
     *
     * @type {Map<string, Record<string, RTCDataChannel>>}
     */
    this.dataChannels = new Map();

    /**
     * Callback invoked when a DataChannel message arrives.
     * Signature: (peerId: string, channelType: string, data: any) => void
     *
     * @type {((peerId: string, channelType: string, data: any) => void) | null}
     */
    this._onDataCallback = null;

    /**
     * Callback invoked when a peer connection reaches the 'connected' state.
     * Signature: (peerId: string) => void
     *
     * @type {((peerId: string) => void) | null}
     */
    this._onConnectedCallback = null;

    /**
     * Optional closure that maps a peer device ID to its rendezvous ID.
     * Injected from transfer-engine.js via setRendezvousLookup().
     *
     * @type {((peerId: string) => string) | null}
     * @private
     */
    this._rendezvousLookup = null;
  }

  // ── Callback registration ─────────────────────────────────────────────────

  /**
   * Register a handler for DataChannel messages from any peer.
   *
   * @param {(peerId: string, channelType: string, data: any) => void} callback
   */
  onData(callback) {
    this._onDataCallback = callback;
  }

  /**
   * Register a handler invoked when a peer's RTCPeerConnection state becomes
   * 'connected' (meaning at least one ICE candidate pair is validated and a
   * DataChannel is usable).
   *
   * @param {(peerId: string) => void} callback
   */
  onConnected(callback) {
    this._onConnectedCallback = callback;
  }

  /**
   * Inject a rendezvous-ID lookup function so that outbound ICE candidates
   * and SDP messages can be routed to the correct relay room without a direct
   * dependency on the paired device list.
   *
   * @param {(peerId: string) => string} fn
   *   Function that returns the rendezvous ID for a given peer device ID.
   */
  setRendezvousLookup(fn) {
    this._rendezvousLookup = fn;
  }

  // ── Offer / answer creation ───────────────────────────────────────────────

  /**
   * Create an RTCPeerConnection and generate an SDP offer for `peerId`.
   *
   * This is the initiator path: called when we want to establish a direct
   * channel to a peer (e.g. the peer just came online or we are starting a
   * transfer and no P2P path exists yet).
   *
   * Steps:
   *   1. Create (or replace) the RTCPeerConnection.
   *   2. Open the ordered control channel (beam-control).
   *   3. Create and set the local SDP offer.
   *   4. Return the SDP offer string so the caller can relay it to the peer.
   *
   * Trickle ICE is used: the offer is returned immediately without waiting for
   * all candidates.  Candidates trickle via the `onicecandidate` handler.
   *
   * @param {string} peerId - Relay device ID of the remote peer.
   * @returns {Promise<RTCSessionDescriptionInit>} The local SDP offer.
   */
  async createOffer(peerId) {
    const pc = this._createPeerConnection(peerId);

    // Open the ordered, reliable control channel.  The data channel for each
    // transfer is opened on-demand when the transfer actually starts so we do
    // not consume SCTP stream IDs unnecessarily.
    const control = pc.createDataChannel('beam-control', {
      ordered: true, // Control messages must arrive in order.
    });
    this._setupDataChannel(peerId, control, 'control');

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);

    return offer;
  }

  /**
   * Handle an incoming SDP offer from a remote peer (answerer path).
   *
   * Steps:
   *   1. Create (or replace) the RTCPeerConnection.
   *   2. Apply the remote description.
   *   3. Create and set the local SDP answer.
   *   4. Return the SDP answer so the caller can relay it to the peer.
   *
   * @param {string} peerId - Relay device ID of the remote peer (the initiator).
   * @param {string} sdp    - SDP offer string received from the peer via relay.
   * @returns {Promise<RTCSessionDescriptionInit>} The local SDP answer.
   */
  async handleOffer(peerId, sdp) {
    const pc = this._createPeerConnection(peerId);

    await pc.setRemoteDescription(
      new RTCSessionDescription({ type: 'offer', sdp }),
    );

    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);

    return answer;
  }

  /**
   * Apply a remote SDP answer to an existing peer connection (initiator path).
   *
   * Called after the remote peer responds to our offer via the relay.
   *
   * @param {string} peerId - Relay device ID of the remote peer.
   * @param {string} sdp    - SDP answer string received from the peer via relay.
   * @returns {Promise<void>}
   */
  async handleAnswer(peerId, sdp) {
    const pc = this.connections.get(peerId);
    if (!pc) {
      console.warn(`[Beam] WebRTCManager.handleAnswer: no connection for peer ${peerId}`);
      return;
    }
    await pc.setRemoteDescription(
      new RTCSessionDescription({ type: 'answer', sdp }),
    );
  }

  /**
   * Add a trickled ICE candidate received from the remote peer.
   *
   * @param {string} peerId    - Relay device ID of the remote peer.
   * @param {object} candidate - RTCIceCandidateInit object from the wire message.
   * @returns {Promise<void>}
   */
  async handleIceCandidate(peerId, candidate) {
    const pc = this.connections.get(peerId);
    if (!pc) {
      console.warn(`[Beam] WebRTCManager.handleIceCandidate: no connection for peer ${peerId}`);
      return;
    }
    try {
      await pc.addIceCandidate(new RTCIceCandidate(candidate));
    } catch (err) {
      // ICE candidate errors are non-fatal (e.g. candidate added after connection
      // closed). Log and continue so other candidates are still processed.
      console.warn(`[Beam] WebRTCManager: addIceCandidate failed for ${peerId}:`, err.message);
    }
  }

  // ── DataChannel management ────────────────────────────────────────────────

  /**
   * Open an unordered data channel for a specific transfer.
   *
   * Using an unordered channel avoids SCTP head-of-line blocking: if one chunk
   * frame is lost in transit, subsequent chunks can still be delivered and
   * acknowledged.  The TransferManager's reorder buffer reassembles in-sequence
   * delivery on top of the unordered transport.
   *
   * @param {string} peerId     - Relay device ID of the remote peer.
   * @param {string} transferId - UUID of the active transfer.
   * @returns {RTCDataChannel | null} The opened channel, or null if no
   *   connection exists for this peer.
   */
  createDataChannel(peerId, transferId) {
    const pc = this.connections.get(peerId);
    if (!pc) {
      console.warn(`[Beam] WebRTCManager.createDataChannel: no connection for peer ${peerId}`);
      return null;
    }

    const label   = `beam-data-${transferId}`;
    // ordered: false — avoids SCTP HOL blocking for chunked file data.
    // reliable: true is the default; we do not set maxRetransmits/maxPacketLifeTime.
    const channel = pc.createDataChannel(label, { ordered: false });

    this._setupDataChannel(peerId, channel, transferId);
    return channel;
  }

  // ── ICE restart ───────────────────────────────────────────────────────────

  /**
   * Trigger an ICE restart for `peerId`.
   *
   * Called when a network change is detected (e.g. Wi-Fi → cellular handoff).
   * Generates a new offer with `iceRestart: true` so the browser gathers
   * fresh ICE candidates over the new network interface.  The transfer engine
   * should temporarily bridge via relay while the new ICE negotiation completes.
   *
   * @param {string} peerId - Relay device ID of the remote peer.
   * @returns {Promise<RTCSessionDescriptionInit | null>}
   *   The new SDP offer to relay to the peer, or null if no connection exists.
   */
  async restartIce(peerId) {
    const pc = this.connections.get(peerId);
    if (!pc) {
      console.warn(`[Beam] WebRTCManager.restartIce: no connection for peer ${peerId}`);
      return null;
    }

    const offer = await pc.createOffer({ iceRestart: true });
    await pc.setLocalDescription(offer);

    console.log(`[Beam] WebRTCManager: ICE restart triggered for peer ${peerId}`);
    return offer;
  }

  // ── Status queries ────────────────────────────────────────────────────────

  /**
   * Return true if the RTCPeerConnection for `peerId` is fully connected (i.e.
   * at least one ICE candidate pair is validated and the DTLS handshake is
   * complete).
   *
   * @param {string} peerId
   * @returns {boolean}
   */
  isConnected(peerId) {
    const pc = this.connections.get(peerId);
    return pc?.connectionState === 'connected';
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  /**
   * Close the RTCPeerConnection for `peerId` and remove it from the registry.
   *
   * Also closes any open DataChannels (RTCPeerConnection.close() does this
   * implicitly, but we also clear the local registry entries).
   *
   * @param {string} peerId - Relay device ID of the remote peer.
   */
  close(peerId) {
    const pc = this.connections.get(peerId);
    if (!pc) return;

    pc.close();
    this.connections.delete(peerId);
    this.dataChannels.delete(peerId);

    console.log(`[Beam] WebRTCManager: connection to ${peerId} closed`);
  }

  /**
   * Close all open peer connections.  Called during extension shutdown or
   * before a full relay reconnect.
   */
  closeAll() {
    for (const peerId of this.connections.keys()) {
      this.close(peerId);
    }
  }

  // ── Internal: connection factory ──────────────────────────────────────────

  /**
   * Create a new RTCPeerConnection for `peerId`, wiring all required event
   * handlers.  If a connection already exists for this peer it is closed first.
   *
   * Event handlers:
   *   - `onicecandidate`        : forward trickle candidates to relay.
   *   - `onconnectionstatechange`: fire `_onConnectedCallback` on success;
   *                               log failures for diagnostics.
   *   - `ondatachannel`         : accept and configure channels opened by the
   *                               remote peer (answerer receives these).
   *
   * @param {string} peerId
   * @returns {RTCPeerConnection}
   * @private
   */
  _createPeerConnection(peerId) {
    // Close any existing connection so we start with a clean slate (e.g. after
    // a network-change triggered restart or a duplicate offer).
    if (this.connections.has(peerId)) {
      this.connections.get(peerId).close();
      this.connections.delete(peerId);
      this.dataChannels.delete(peerId);
    }

    const pc = new RTCPeerConnection({
      iceServers:           STUN_SERVERS,
      // Pre-gather 2 candidate pairs before sending the offer to reduce
      // round-trip latency on responsive networks.
      iceCandidatePoolSize: 2,
    });

    // ── Trickle ICE: forward candidates to the relay ───────────────────────
    pc.onicecandidate = (event) => {
      if (!event.candidate) return; // null = gathering complete; nothing to forward

      this.ws.send({
        type:          WIRE.ICE_CANDIDATE,
        targetDeviceId: peerId,
        rendezvousId:  this._getRendezvousId(peerId),
        candidate:     event.candidate.toJSON(),
      });
    };

    // ── Connection state changes ───────────────────────────────────────────
    pc.onconnectionstatechange = () => {
      const state = pc.connectionState;
      console.log(`[Beam] WebRTCManager: peer ${peerId} connectionState → ${state}`);

      if (state === 'connected' && this._onConnectedCallback) {
        this._onConnectedCallback(peerId);
      }

      if (state === 'failed') {
        // Log for diagnostics; the caller decides whether to attempt ICE
        // restart or fall back permanently to the relay.
        console.warn(`[Beam] WebRTCManager: connection to ${peerId} FAILED`);
      }
    };

    // ── Incoming DataChannels (opened by the remote peer) ──────────────────
    // The answerer side receives channels that were opened by the initiator
    // (e.g. the beam-control channel created in createOffer()).
    pc.ondatachannel = (event) => {
      const channel = event.channel;
      const label   = channel.label;

      // Determine channel type from label.
      const type = label === 'beam-control'
        ? 'control'
        : label.startsWith('beam-data-')
          ? label.slice('beam-data-'.length) // transfer ID as type key
          : label;

      this._setupDataChannel(peerId, channel, type);
    };

    this.connections.set(peerId, pc);
    console.log(`[Beam] WebRTCManager: created RTCPeerConnection for ${peerId}`);
    return pc;
  }

  // ── Internal: DataChannel wiring ──────────────────────────────────────────

  /**
   * Attach `onmessage` and `onopen` handlers to a DataChannel and register it
   * in the dataChannels registry.
   *
   * @param {string}         peerId  - Peer device ID this channel belongs to.
   * @param {RTCDataChannel} channel - The DataChannel to configure.
   * @param {string}         type    - Registry key: 'control' or a transfer ID.
   * @private
   */
  _setupDataChannel(peerId, channel, type) {
    // Initialise peer entry if not present.
    if (!this.dataChannels.has(peerId)) {
      this.dataChannels.set(peerId, {});
    }
    this.dataChannels.get(peerId)[type] = channel;

    channel.onmessage = (event) => {
      if (this._onDataCallback) {
        this._onDataCallback(peerId, type, event.data);
      }
    };

    channel.onopen = () => {
      console.log(`[Beam] DataChannel "${type}" opened with peer ${peerId}`);
    };

    channel.onerror = (event) => {
      // DataChannel errors are surfaced here; they do not automatically close
      // the peer connection so we log and let the connection state handler
      // decide on recovery.
      console.warn(`[Beam] DataChannel "${type}" error with peer ${peerId}:`, event);
    };
  }

  // ── Internal: rendezvous ID resolution ───────────────────────────────────

  /**
   * Resolve the rendezvous ID for a peer device ID using the injected lookup.
   *
   * Returns an empty string if no lookup has been registered (signaling messages
   * will be missing the rendezvousId field, which the relay treats as an error).
   *
   * @param {string} peerId
   * @returns {string}
   * @private
   */
  _getRendezvousId(peerId) {
    return this._rendezvousLookup?.(peerId) ?? '';
  }
}
