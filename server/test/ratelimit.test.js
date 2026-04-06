/**
 * ratelimit.test.js — TDD tests for the RateLimiter module.
 *
 * Tests cover all five limit domains in isolation using purely in-memory state —
 * no real network I/O and no real-clock dependencies beyond the one sliding-
 * window test that uses a short artificial delay.
 *
 * Test plan:
 *   1.  Allows up to maxConnectionsPerIp (5) from one IP
 *   2.  Rejects the 6th connection from the same IP
 *   3.  Allows a new connection after releaseConnection()
 *   4.  Different IPs have independent connection counts
 *   5.  Allows up to maxMessagesPerSec (50) within the 1-second window
 *   6.  Rejects the 51st message within the same window
 *   7.  Resets the message counter after the 1-second window elapses
 *   8.  Allows up to maxConcurrentDevices (50) and rejects the 51st
 *   9.  addBandwidth() accumulates bytes; quotaInfo() reflects usage
 *  10.  isRelayDisabled() returns true once 80 % of monthly bandwidth is consumed
 */

import { describe, it, before, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { RateLimiter } from '../src/ratelimit.js';

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

/**
 * Promisify a short delay so test 7 can wait for the sliding window to expire.
 * @param {number} ms
 * @returns {Promise<void>}
 */
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

describe('RateLimiter', () => {
  /** @type {RateLimiter} */
  let rl;

  beforeEach(() => {
    // Fresh instance for every test; default constructor options match spec values.
    rl = new RateLimiter();
  });

  // -------------------------------------------------------------------------
  // Test 1 — Allows up to 5 connections from one IP
  // -------------------------------------------------------------------------
  it('allows up to maxConnectionsPerIp (5) connections from the same IP', () => {
    const ip = '1.2.3.4';

    for (let i = 0; i < 5; i++) {
      assert.ok(
        rl.allowConnection(ip),
        `Connection ${i + 1} from ${ip} should be allowed`,
      );
      rl.trackConnection(ip);
    }

    // The 5th is exactly at the limit — verify the counter is 5
    const allowed = rl.allowConnection(ip);
    // We already tracked 5; the 6th check (without tracking) must return false
    assert.ok(!allowed, 'allowConnection should return false once limit is reached');
  });

  // -------------------------------------------------------------------------
  // Test 2 — Rejects the 6th connection from the same IP
  // -------------------------------------------------------------------------
  it('rejects the 6th connection from the same IP', () => {
    const ip = '10.0.0.1';

    // Fill the slot up to the limit
    for (let i = 0; i < 5; i++) {
      rl.trackConnection(ip);
    }

    assert.ok(
      !rl.allowConnection(ip),
      'allowConnection must return false after 5 connections are tracked',
    );
  });

  // -------------------------------------------------------------------------
  // Test 3 — Allows a new connection after releaseConnection()
  // -------------------------------------------------------------------------
  it('allows a new connection after releaseConnection() frees a slot', () => {
    const ip = '192.168.1.1';

    for (let i = 0; i < 5; i++) {
      rl.trackConnection(ip);
    }

    // Full — next should be rejected
    assert.ok(!rl.allowConnection(ip), 'should be blocked at limit');

    // Release one slot
    rl.releaseConnection(ip);

    // Now one slot is free
    assert.ok(rl.allowConnection(ip), 'should be allowed after releasing one slot');
  });

  // -------------------------------------------------------------------------
  // Test 4 — Different IPs have independent connection counts
  // -------------------------------------------------------------------------
  it('tracks connection counts independently per IP', () => {
    const ipA = '10.1.1.1';
    const ipB = '10.1.1.2';

    // Fill ipA to the limit
    for (let i = 0; i < 5; i++) {
      rl.trackConnection(ipA);
    }

    // ipA is blocked but ipB is unaffected
    assert.ok(!rl.allowConnection(ipA), 'ipA should be blocked at limit');
    assert.ok(rl.allowConnection(ipB), 'ipB should still be allowed');
  });

  // -------------------------------------------------------------------------
  // Test 5 — Allows up to 50 messages per second
  // -------------------------------------------------------------------------
  it('allows up to maxMessagesPerSec (50) messages within the sliding window', () => {
    const connId = 'conn-a';

    for (let i = 0; i < 50; i++) {
      assert.ok(
        rl.allowMessage(connId),
        `Message ${i + 1} should be allowed within the 50/s limit`,
      );
    }
  });

  // -------------------------------------------------------------------------
  // Test 6 — Rejects the 51st message within the same window
  // -------------------------------------------------------------------------
  it('rejects the 51st message within the 1-second sliding window', () => {
    const connId = 'conn-b';

    // Consume all 50 slots
    for (let i = 0; i < 50; i++) {
      rl.allowMessage(connId);
    }

    assert.ok(
      !rl.allowMessage(connId),
      '51st message within the same window must be rejected',
    );
  });

  // -------------------------------------------------------------------------
  // Test 7 — Resets the message counter after the 1-second window elapses
  // -------------------------------------------------------------------------
  it('resets the message counter after the 1-second window elapses', async () => {
    // Use a limiter with a short window so the test does not wait a full second.
    // We expose windowMs as an optional constructor parameter for testability.
    const shortRl = new RateLimiter({ windowMs: 100 });
    const connId  = 'conn-c';

    // Saturate the window
    for (let i = 0; i < 50; i++) {
      shortRl.allowMessage(connId);
    }

    // Confirm blocked
    assert.ok(!shortRl.allowMessage(connId), 'should be blocked before window expires');

    // Wait for the window to roll over (100 ms + a small buffer)
    await sleep(150);

    // Should be allowed again in the new window
    assert.ok(
      shortRl.allowMessage(connId),
      'should be allowed again after the window resets',
    );
  });

  // -------------------------------------------------------------------------
  // Test 8 — Max concurrent devices enforced
  // -------------------------------------------------------------------------
  it('allows up to maxConcurrentDevices (50) and rejects the 51st', () => {
    // Register 50 unique devices
    for (let i = 0; i < 50; i++) {
      const deviceId = `device-${i}`;
      assert.ok(rl.allowDevice(), `Device ${i + 1} should be allowed`);
      rl.trackDevice(deviceId);
    }

    // The 51st should be rejected
    assert.ok(!rl.allowDevice(), 'allowDevice must return false once 50 devices are tracked');

    // Release one device and confirm a slot opens
    rl.releaseDevice('device-0');
    assert.ok(rl.allowDevice(), 'allowDevice should allow again after releasing a device');
  });

  // -------------------------------------------------------------------------
  // Test 9 — addBandwidth() accumulates bytes; quotaInfo() reflects usage
  // -------------------------------------------------------------------------
  it('accumulates bandwidth and reflects usage in quotaInfo()', () => {
    const GB = 1024 ** 3;

    rl.addBandwidth(10 * GB);  // 10 GB used

    const info = rl.quotaInfo();

    assert.equal(info.usedBytes, 10 * GB, 'usedBytes should equal the total added');
    assert.equal(info.limitBytes, 160 * GB, 'limitBytes should equal 160 GB by default');
    assert.ok(
      typeof info.usedRatio === 'number' && info.usedRatio > 0 && info.usedRatio < 1,
      'usedRatio should be a number between 0 and 1',
    );
  });

  // -------------------------------------------------------------------------
  // Test 10 — isRelayDisabled() returns true at the 80 % warning threshold
  // -------------------------------------------------------------------------
  it('disables relay once monthly bandwidth reaches 80 % of the quota', () => {
    const GB = 1024 ** 3;

    // Relay must be enabled at the start
    assert.ok(!rl.isRelayDisabled(), 'relay should be enabled initially');

    // Add bandwidth up to 79 % — still under the 80 % threshold
    rl.addBandwidth(Math.floor(160 * GB * 0.79));
    assert.ok(!rl.isRelayDisabled(), 'relay should still be enabled at 79 %');

    // Tip over to exactly 80 %
    rl.addBandwidth(Math.ceil(160 * GB * 0.01) + 1);
    assert.ok(rl.isRelayDisabled(), 'relay must be disabled once 80 % of quota is consumed');
  });
});
