// Shipped-module DOM/HTTP/EventSource regressions. Not production restart or physical evidence.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const source = fs.readFileSync(path.join(__dirname, '../../resources/admin/panel.js'), 'utf8');
const A = '0000000000000001', B = '0000000000000002', C = '0000000000000003';
function value(epoch = A, revision = 1) {
  const series = { startAt: '2026-01-01T00:00:00.000Z', bucketMs: 5000, values: [] };
  return { relayEpoch: epoch, revision,
    display: { twitchDisplayEnabled: false, preferredDashboard: 'cpu', revision },
    monitoring: { desired: { mode: 'disabled', channel: null }, effective: { mode: 'disabled', channel: null },
      transition: 'ready', sourceGeneration: 1, grantCompatibility: { status: 'not_required', features: [] } },
    sources: { cpu: { status: 'fresh', reason: 'fresh', remainingFreshnessMs: 1000,
      data: { host: 'Synthetic', cpuPercent: 12.34, memoryPercent: 0, sourceId: 'test' } },
      weather: { status: 'missing', reason: 'not_supplied', data: null } },
    devices: [{ deviceId: 'synthetic', firmwareVersion: 'test', reportStatus: 'fresh', desiredDisplayRevision: 1,
      appliedDisplayRevision: 1, baseScreen: 'cpu', reason: 'selected', observationAgeUpperMs: 14999,
      active: null, pending: [], pendingCapacity: 8, transportQueuedFrames: 0, transportReservedEvents: 0, chartAvailable: false }],
    notifications: [], charts: { notificationArrivals: series, viewers: series, chatRate: series, devices: [] } };
}
async function fixture() {
  const nodes = new Map(), feeds = [], timers = new Map();
  let now = 0, clockStep = 0, next = 0, response = value(), deferred = false, release, requests = 0;
  function element() { return { textContent: '', value: '', hidden: false, disabled: false,
    setAttribute() {}, append() {}, replaceChildren() {}, addEventListener() {}, contains() { return false; } }; }
  const document = { activeElement: null, getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
    querySelectorAll() { return []; }, createElement: element, createElementNS: element };
  class EventSource {
    constructor() { this.handlers = {}; this.closed = false; feeds.push(this); }
    addEventListener(name, callback) { this.handlers[name] = callback; }
    close() { this.closed = true; }
    emit(v, type = 'snapshot') { this.handlers[type]({ type, data: JSON.stringify(v), lastEventId: `${v.relayEpoch}:${v.revision}` }); }
  }
  const setTimeout = (callback, ms) => { const id = ++next; timers.set(id, { callback, at: now + ms }); return id; };
  const clearTimeout = id => timers.delete(id);
  const fetch = async () => { requests++; return { ok: true, json: async () => deferred ? await new Promise(r => { release = r; }) : response }; };
  const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
  const api = await new AsyncFunction('document', 'window', 'fetch', 'location', 'EventSource', 'navigator', 'performance', 'setTimeout', 'clearTimeout',
    source + '\nreturn {readSnapshot, current: () => snapshot};')(document, { addEventListener() {} }, fetch,
    { protocol: 'http:', hostname: '127.0.0.1' }, EventSource, { onLine: true }, { now: () => { const sampled = now; now += clockStep; return sampled; } }, setTimeout, clearTimeout);
  return { api, feeds, nodes, timers, requests: () => requests,
    stepClockReads(ms) { clockStep = ms; },
    reply(v) { response = v; }, delay() { deferred = true; }, resolve(v) { release(v); },
    async flush() { await Promise.resolve(); await Promise.resolve(); },
    advance(ms) { now += ms; for (const [id, timer] of [...timers]) if (timer.at <= now && timers.has(id)) { timers.delete(id); timer.callback(); } } };
}
test('same-old-epoch push cannot veto canonical new-epoch GET', async () => {
  const f = await fixture(); f.feeds[0].emit(value(A, 2)); f.delay();
  const pending = f.api.readSnapshot(); await f.flush(); f.feeds[0].emit(value(A, 3));
  f.resolve(value(B, 1)); await pending; assert.equal(f.api.current().relayEpoch, B);
});
for (const first of [true, false]) for (const type of ['snapshot', 'reset']) {
  test(`canonical new epoch fences queued old ${type} (first=${first})`, async () => {
    const f = await fixture(), old = f.feeds[0]; if (!first) old.emit(value(A, 2));
    f.reply(value(B, 1)); await f.api.readSnapshot();
    assert.equal(old.closed, true); old.emit(value(A, 99), type);
    assert.equal(f.api.current().relayEpoch, B);
    assert.doesNotMatch(f.nodes.get('feed-status').textContent, /epoch 0000000000000001/);
    f.feeds.at(-1).emit(value(B, 2)); assert.equal(f.api.current().revision, 2);
  });
}
test('legitimate feed reset/new first can adopt opaque new epochs', async () => {
  const f = await fixture(); f.feeds[0].emit(value(B, 1)); f.feeds[0].emit(value(C, 1), 'reset');
  assert.equal(f.api.current().relayEpoch, C);
});
test('feed epoch transition rejects delayed old GET, even after same-epoch updates', async () => {
  const f = await fixture(); f.feeds[0].emit(value(A, 2)); f.delay();
  const pending = f.api.readSnapshot(); await f.flush(); f.feeds[0].emit(value(B, 1), 'reset');
  f.feeds[0].emit(value(B, 2)); f.resolve(value(A, 99)); await pending;
  assert.equal(f.api.current().relayEpoch, B);
});
test('regressive same-epoch reset cannot replace authority or relabel feed revision', async () => {
  const f = await fixture(); f.feeds[0].emit(value(A, 4)); f.feeds[0].emit(value(A, 3), 'reset');
  assert.equal(f.api.current().revision, 4); assert.match(f.nodes.get('feed-status').textContent, /revision 4/);
});
test('source and nearly stale report expire while SSE stays open', async () => {
  const f = await fixture(); f.feeds[0].emit(value(A, 2));
  f.advance(1); assert.match(f.nodes.get('device-detail').textContent, /fresh firmware observation/);
  f.advance(1); assert.doesNotMatch(f.nodes.get('device-detail').textContent, /fresh firmware observation/);
  f.advance(998); assert.match(f.nodes.get('cpu-value').textContent, /stale.*not current/);
  assert.match(f.nodes.get('cpu-value').textContent, /CPU 12.34%.*memory 0%/);
  assert.match(f.nodes.get('feed-status').textContent, /Feed connected/); assert.equal(f.requests(), 1);
  f.feeds[0].emit(value(A, 3)); assert.match(f.nodes.get('cpu-value').textContent, /fresh: fresh/);
  assert.match(f.nodes.get('device-detail').textContent, /fresh firmware observation/);
});
test('duplicate revision does not renew captured source/report lifetime', async () => {
  const f = await fixture(); f.feeds[0].emit(value(A, 2)); f.advance(900);
  f.feeds[0].emit(value(A, 2)); f.advance(100);
  assert.match(f.nodes.get('cpu-value').textContent, /stale.*not current/);
});
test('expiry crossed during rendering cannot lose the last source timer', async () => {
  const f = await fixture(); f.feeds[0].emit(value(A, 2));
  // The report timer wakes just before CPU expiry. Rendering then crosses it.
  f.stepClockReads(0.25); f.advance(999.8); f.stepClockReads(0);
  f.advance(2);
  assert.match(f.nodes.get('cpu-value').textContent, /stale.*not current/);
  assert.match(f.nodes.get('feed-status').textContent, /Feed connected/);
  assert.equal(f.requests(), 1);
});

test('weather deadline and missing calibration remain independent of feed health', async () => {
  const f = await fixture(), v = value(A, 2);
  v.sources.weather = { status: 'fresh', reason: 'fresh', remainingFreshnessMs: 250,
    data: { location: 'Synthetic', temperature: 0, unit: 'celsius', condition: 'clear', sourceId: 'weather' } };
  v.devices[0].observationAgeUpperMs = null; f.feeds[0].emit(v); f.advance(250);
  assert.match(f.nodes.get('weather-value').textContent, /0 C.*stale.*not current/);
  assert.doesNotMatch(f.nodes.get('device-detail').textContent, /fresh firmware observation/);
});
