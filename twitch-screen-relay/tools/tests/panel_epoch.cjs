// Test-only DOM/transport fixture for the shipped module's delayed-read epoch fence.
// This does not prove a production relay restart or physical browser/LCD behavior.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

function element() {
  return { textContent: '', value: '', hidden: false, disabled: false,
    setAttribute() {}, append() {}, replaceChildren() {}, addEventListener() {}, contains() { return false; } };
}
const nodes = new Map();
const document = {
  activeElement: null,
  getElementById(id) { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); },
  querySelectorAll() { return []; },
  createElement: element,
  createElementNS: element,
};
const window = { addEventListener() {} };
class EventSource { addEventListener() {} close() {} }
const series = { startAt: '2026-01-01T00:00:00.000Z', bucketMs: 5000, values: [] };
function snapshot(epoch, revision) {
  return { relayEpoch: epoch, revision,
    display: { twitchDisplayEnabled: false, preferredDashboard: 'generic', revision },
    monitoring: { desired: { mode: 'disabled', channel: null }, effective: { mode: 'disabled', channel: null },
      transition: 'ready', sourceGeneration: 1, grantCompatibility: { status: 'not_required', features: [] } },
    sources: { cpu: { status: 'missing', reason: 'not_supplied', data: null }, weather: { status: 'missing', reason: 'not_supplied', data: null } },
    devices: [], notifications: [],
    charts: { notificationArrivals: series, viewers: series, chatRate: series, devices: [] } };
}
let response = snapshot('0000000000000001', 1);
let delayed = false;
let release;
const fetch = async () => {
  const value = delayed ? await new Promise(resolve => { release = resolve; }) : response;
  return { ok: true, json: async () => value };
};
async function main() {
  let source = fs.readFileSync(path.join(__dirname, '../../resources/admin/panel.js'), 'utf8');
  if (process.env.PANEL_TEST_REMOVE_EPOCH_FENCE === '1') {
    source = source.replace('if (epochGeneration !== before && snapshot && snapshot.relayEpoch !== value.relayEpoch) return;', '');
  }
  const AsyncFunction = Object.getPrototypeOf(async function() {}).constructor;
  const api = await new AsyncFunction('document', 'window', 'fetch', 'location', 'EventSource', 'navigator',
    source + '\nreturn {accept, readSnapshot, current: () => snapshot};')(document, window, fetch,
    { protocol: 'http:', hostname: '127.0.0.1' }, EventSource, { onLine: true });
  delayed = true;
  const oldRead = api.readSnapshot();
  const current = snapshot('0000000000000002', 1);
  api.accept(current, true);
  release(snapshot('0000000000000001', 99));
  await oldRead;
  assert.equal(api.current(), current, 'delayed old-epoch GET rolled back newer feed authority');
  const sameEpoch = snapshot('0000000000000002', 4);
  api.accept(sameEpoch, true);
  api.accept(snapshot('0000000000000002', 3), true);
  assert.equal(api.current(), sameEpoch, 'same-epoch revision regressed');
  api.accept(snapshot('0000000000000001', 100), false);
  assert.equal(api.current(), sameEpoch, 'ordinary foreign-epoch event superseded authority');
  delayed = false;
  response = snapshot('0000000000000003', 1);
  await api.readSnapshot();
  assert.equal(api.current(), response, 'uncontended canonical new-epoch GET was incorrectly rejected');
  console.log('4 shipped-module epoch/revision assertions passed (test-only DOM/HTTP fixtures)');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
