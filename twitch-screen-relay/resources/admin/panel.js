const byId = (id) => document.getElementById(id);
const text = (id, value) => { byId(id).textContent = value; };
let snapshot = null;
let connected = false;
let feed = null;
let feedGeneration = 0;
let retryTimer = null;
let freshnessTimer = null;
let observationTimer = null;
let epochGeneration = 0;
let retryMs = 1000;
let receivedAt = 0;
let selectedDevice = '';
let busy = false;

async function request(path, method = 'GET', body = undefined) {
  const controller = new AbortController();
  const deadline = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(`/api/v1/${path}`, {
      method, cache: 'no-store', signal: controller.signal,
      headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const value = await response.json();
    if (!response.ok) throw new Error(value.code || value.error || `HTTP ${response.status}`);
    return value;
  } finally { clearTimeout(deadline); }
}

function accept(value, authoritative = false) {
  if (snapshot && snapshot.relayEpoch === value.relayEpoch && value.revision <= snapshot.revision) return false;
  if (!authoritative && snapshot && snapshot.relayEpoch !== value.relayEpoch) return false;
  if (snapshot && snapshot.relayEpoch !== value.relayEpoch) ++epochGeneration;
  snapshot = value;
  receivedAt = performance.now();
  const elapsed = render();
  scheduleObservationExpiry(elapsed);
  return true;
}

async function readSnapshot() {
  const before = epochGeneration;
  const value = await request('admin/snapshot');
  // Only an intervening epoch transition supersedes a foreign canonical response. A same-epoch push does not.
  if (epochGeneration !== before && snapshot && snapshot.relayEpoch !== value.relayEpoch) return;
  const changedEpoch = snapshot && snapshot.relayEpoch !== value.relayEpoch;
  if (changedEpoch && feed) {
    // Retire this subscription before adopting new authority, including callbacks already queued by the browser.
    ++feedGeneration;
    feed.close();
    markDisconnected('Feed resynchronizing · canonical epoch changed; awaiting a new subscription.');
  }
  accept(value, true);
  if (changedEpoch && feed) openFeed();
}

function observationElapsedMs() {
  return Math.max(0, performance.now() - receivedAt);
}

function sourceExpired(value, elapsed) {
  return value.status === 'fresh' && elapsed >= value.remainingFreshnessMs;
}

function reportStatus(device, elapsed = observationElapsedMs()) {
  if (device.reportStatus !== 'fresh') return device.reportStatus;
  if (device.observationAgeUpperMs === null) return 'unknown';
  return device.observationAgeUpperMs + elapsed > 15000 ? 'stale' : 'fresh';
}

function scheduleObservationExpiry(renderedElapsed) {
  clearTimeout(observationTimer);
  if (!snapshot) return;
  const elapsed = observationElapsedMs();
  const deadlines = [];
  Object.values(snapshot.sources).forEach((value) => {
    // A deadline crossed during rendering still needs a relabel callback.
    if (value.status === 'fresh' && value.remainingFreshnessMs > renderedElapsed) deadlines.push(value.remainingFreshnessMs - elapsed);
  });
  snapshot.devices.forEach((device) => {
    if (device.reportStatus === 'fresh' && device.observationAgeUpperMs !== null && device.observationAgeUpperMs + renderedElapsed <= 15000) {
      deadlines.push(15001 - device.observationAgeUpperMs - elapsed);
    }
  });
  if (deadlines.length) observationTimer = setTimeout(() => {
    const sampled = render();
    scheduleObservationExpiry(sampled);
  }, Math.max(1, Math.ceil(Math.min(...deadlines))));
}

function markDisconnected(reason) {
  connected = false;
  clearTimeout(freshnessTimer);
  text('feed-status', reason);
  if (snapshot) render();
}

function openFeed() {
  if (!navigator.onLine) {
    markDisconnected('Feed disconnected · browser offline; observations frozen.');
    return;
  }
  clearTimeout(retryTimer);
  const generation = ++feedGeneration;
  feed?.close();
  feed = new EventSource('/api/v1/admin/events');
  let first = true;
  const consume = (event) => {
    if (generation !== feedGeneration) return;
    try {
      const value = JSON.parse(event.data);
      if (event.lastEventId !== `${value.relayEpoch}:${value.revision}`) throw new Error('Snapshot identity mismatch');
      if (snapshot && snapshot.relayEpoch === value.relayEpoch && value.revision < snapshot.revision) return;
      if (!first && event.type !== 'reset' && snapshot && snapshot.relayEpoch !== value.relayEpoch) return;
      connected = true;
      retryMs = 1000;
      if (!accept(value, first || event.type === 'reset')) render();
      first = false;
      text('feed-status', `Feed connected · epoch ${snapshot.relayEpoch} · revision ${snapshot.revision}`);
      clearTimeout(freshnessTimer);
      freshnessTimer = setTimeout(() => markDisconnected('Feed stale · last complete snapshot retained, not current.'), 16000);
    } catch (error) { markDisconnected(`Invalid feed: ${error.message}`); }
  };
  feed.addEventListener('snapshot', consume);
  feed.addEventListener('reset', consume);
  feed.onerror = () => {
    if (generation !== feedGeneration) return;
    feed.close();
    markDisconnected('Feed disconnected · observations frozen. Reconnecting…');
    clearTimeout(retryTimer);
    const delay = retryMs * (1 + Math.random() * 0.25);
    retryMs = Math.min(30000, retryMs * 2);
    retryTimer = setTimeout(openFeed, delay);
  };
}

function element(tag, value) {
  const node = document.createElement(tag);
  node.textContent = value;
  return node;
}

function sourceDescription(value, kind, elapsed) {
  if (!value.data) return `${value.status}: ${value.reason}. No measurement.`;
  const data = value.data;
  const numeric = kind === 'cpu'
    ? `${data.host}: CPU ${data.cpuPercent}% · memory ${data.memoryPercent === null ? 'unavailable' : `${data.memoryPercent}%`}`
    : `${data.location}: ${data.temperature} ${data.unit === 'fahrenheit' ? 'F' : 'C'} · ${data.condition}`;
  const status = sourceExpired(value, elapsed) ? 'stale: captured freshness elapsed; last-known measurement, not current' : `${value.status}: ${value.reason}`;
  return `${numeric}. ${status}; ${value.remainingFreshnessMs} ms at capture. Source ${data.sourceId}.`;
}

function render() {
  const elapsed = observationElapsedMs();
  const display = snapshot.display;
  const choice = display.twitchDisplayEnabled ? 'twitch' : display.preferredDashboard;
  document.querySelectorAll('[data-display]').forEach((button) => {
    button.setAttribute('aria-pressed', String(button.dataset.display === choice));
  });
  text('display-detail', `Desired ${choice} · remembered ${display.preferredDashboard} · revision ${display.revision}. Firmware application is separate.`);
  const monitoring = snapshot.monitoring;
  const desired = monitoring.desired;
  // Do not overwrite an operator's unsubmitted edit while a feed update arrives.
  if (!byId('monitor-form').contains(document.activeElement)) {
    byId('monitor-mode').value = desired.mode;
    byId('monitor-channel').value = desired.channel || '';
  }
  text('monitor-detail', `Desired ${desired.mode}/${desired.channel || 'none'}; effective ${monitoring.effective.mode}/${monitoring.effective.channel || 'none'} · ${monitoring.transition} · generation ${monitoring.sourceGeneration}${monitoring.error ? ` · ${monitoring.error.code || monitoring.error.error}` : ''}.`);
  text('grant-detail', `Grant ${monitoring.grantCompatibility.status}. ${monitoring.grantCompatibility.features.map((feature) => `${feature.feature}: ${feature.eligible ? 'eligible' : feature.reason || 'unavailable'}`).join('; ')}`);
  const remotePlain = location.protocol !== 'https:' && !['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
  byId('connect-twitch').hidden = remotePlain;
  text('consent-detail', remotePlain
    ? 'Connect unavailable from remote plaintext HTTP. Use configured trusted private HTTPS or desktop-local/tunneled consent.'
    : 'Connect opens the existing provider consent flow. Exact callback configuration and a usable grant remain required.');
  const authority = connected ? '' : 'Last complete snapshot; not current. ';
  text('cpu-value', authority + sourceDescription(snapshot.sources.cpu, 'cpu', elapsed));
  text('weather-value', authority + sourceDescription(snapshot.sources.weather, 'weather', elapsed));
  const selector = byId('device-select');
  selector.replaceChildren();
  if (!snapshot.devices.length) selector.append(element('option', 'No device'));
  snapshot.devices.forEach((device) => {
    const option = element('option', `${device.deviceId} · ${reportStatus(device, elapsed)}`);
    option.value = device.deviceId;
    selector.append(option);
  });
  if (!snapshot.devices.some((device) => device.deviceId === selectedDevice)) selectedDevice = snapshot.devices[0]?.deviceId || '';
  selector.value = selectedDevice;
  renderDevice(elapsed);
  byId('history').replaceChildren();
  snapshot.notifications.forEach((card) => {
    byId('history').append(element('li', `${card.title || 'Untitled'} · ${card.body} · ${card.identity.origin} · ${card.identity.originalRelayEpoch}:${card.identity.seq} · ${card.remainingExpiryMs} ms waiting lifetime at capture`));
  });
  if (!snapshot.notifications.length) byId('history').append(element('li', 'No retained records.'));
  return elapsed;
}

function cardLabel(card) {
  const known = snapshot.notifications.find((item) => item.identity.originalRelayEpoch === card.originalRelayEpoch && item.identity.seq === card.seq);
  return `${known?.title || `Kind ${card.kind}`} · ${card.origin} · ${card.originalRelayEpoch}:${card.seq}`;
}

function renderDevice(elapsed = observationElapsedMs()) {
  const device = snapshot.devices.find((item) => item.deviceId === selectedDevice);
  const queue = byId('device-queue');
  queue.replaceChildren();
  if (!device) {
    text('device-detail', 'No device connected or retained.');
    text('transport-detail', 'Transport backlog unavailable.');
  } else {
    const status = reportStatus(device, elapsed);
    const current = connected && status === 'fresh';
    text('device-detail', `${device.deviceId} · ${device.firmwareVersion || 'firmware unknown'} · ${current ? 'fresh firmware observation' : `${status}; not a current queue`} · desired ${device.desiredDisplayRevision}, applied ${device.appliedDisplayRevision ?? 'unknown'} · ${device.baseScreen || 'base unknown'} / ${device.reason || 'reason unknown'} · age upper bound ${device.observationAgeUpperMs ?? 'unknown'} ms at capture.${device.active ? ' Base becomes visible after the active card finishes.' : ''}`);
    if (device.active) queue.append(element('li', `Active: ${cardLabel(device.active.identity)} · ${device.active.phase} · ${device.active.remainingMs} ms total occupancy estimate at observation${current ? '' : ' (frozen)'}`));
    if (device.pending === null) queue.append(element('li', 'Firmware FIFO unavailable; never inferred from ACK or relay history.'));
    else {
      device.pending.forEach((card, index) => queue.append(element('li', `Pending ${index + 1}: ${cardLabel(card)}`)));
      if (!device.pending.length && !device.active) queue.append(element('li', `Reported empty${current ? '' : ' (last-known observation)'}.`));
    }
    text('transport-detail', `Relay transport: ${device.transportQueuedFrames} queued frames; ${device.transportReservedEvents} cumulative reserved events. Not device queue depth. Pending capacity ${device.pendingCapacity}. Charts ${device.chartAvailable ? 'available' : 'unavailable beyond the chart limit'}.`);
  }
  renderCharts(device);
}

function chart(title, series, unit) {
  const node = element('article', '');
  node.className = 'chart';
  node.append(element('h3', title));
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 320 100');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', `${title}: ${unit}, unavailable buckets are gaps`);
  const maximum = Math.max(1, ...series.values.filter((value) => value !== null));
  let points = [];
  function flush() {
    if (points.length) {
      const line = document.createElementNS(svg.namespaceURI, 'polyline');
      line.setAttribute('points', points.join(' '));
      svg.append(line);
      points = [];
    }
  }
  series.values.forEach((value, index) => {
    if (value === null) flush();
    else points.push(`${5 + index * 310 / Math.max(1, series.values.length - 1)},${95 - value * 85 / maximum}`);
  });
  flush();
  node.append(svg);
  const table = element('table', '');
  table.append(element('caption', `${connected ? '' : 'Last complete snapshot; not current · '}${unit} · ${series.bucketMs} ms buckets · from ${series.startAt}`));
  const row = element('tr', '');
  row.append(element('th', 'Latest observed bucket'));
  row.append(element('td', series.values.at(-1) === null || !series.values.length ? 'Unavailable' : String(series.values.at(-1))));
  table.append(row);
  node.append(table);
  return node;
}

function renderCharts(device) {
  const charts = byId('charts');
  charts.replaceChildren(
    chart('Notification arrivals', snapshot.charts.notificationArrivals, 'accepted publications / bucket'),
    chart('Twitch viewers', snapshot.charts.viewers, 'viewers'),
    chart('Chat activity', snapshot.charts.chatRate, 'messages / minute'),
  );
  const selected = snapshot.charts.devices.find((item) => item.deviceId === device?.deviceId);
  if (selected) {
    charts.append(chart('Firmware pending depth', selected.queueDepth, 'pending cards'));
    ['expired', 'suppressed', 'refused'].forEach((key) => charts.append(chart(`Firmware ${key}`, selected[key], 'observed counter delta / bucket')));
  }
}

async function mutate(path, method, body) {
  if (busy) return;
  busy = true;
  document.querySelectorAll('button').forEach((button) => { button.disabled = true; });
  text('action-status', 'Submitting once…');
  try {
    await request(path, method, body);
    text('action-status', 'Relay accepted. Device application and physical pixels remain separate.');
  } catch (error) {
    text('action-status', `Outcome may be uncertain: ${error.message}. No automatic resend. Reconciling canonical state…`);
  } finally {
    try { await readSnapshot(); } catch (error) { text('action-status', `Canonical readback unavailable: ${error.message}. Do not assume unchanged state.`); }
    busy = false;
    document.querySelectorAll('button').forEach((button) => { button.disabled = false; });
  }
}

document.querySelectorAll('[data-display]').forEach((button) => button.addEventListener('click', () => {
  const choice = button.dataset.display;
  mutate('display', 'PATCH', choice === 'twitch' ? { twitchDisplayEnabled: true } : { twitchDisplayEnabled: false, preferredDashboard: choice });
}));
byId('monitor-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const body = { mode: byId('monitor-mode').value };
  const channel = byId('monitor-channel').value.trim();
  if (channel) body.channel = channel;
  mutate('twitch/monitoring', 'PATCH', body);
});
byId('device-select').addEventListener('change', (event) => { selectedDevice = event.target.value; renderDevice(); });
byId('test-card').addEventListener('click', () => mutate('notifications', 'POST', { type: 'info', title: 'Synthetic admin test', body: '' }));
window.addEventListener('offline', () => {
  ++feedGeneration;
  feed?.close();
  clearTimeout(retryTimer);
  markDisconnected('Feed disconnected · browser offline; observations frozen.');
});
window.addEventListener('online', openFeed);
window.addEventListener('pagehide', () => { ++feedGeneration; feed?.close(); clearTimeout(retryTimer); clearTimeout(freshnessTimer); clearTimeout(observationTimer); });
try { await readSnapshot(); } catch (error) { text('feed-status', `Initial snapshot unavailable: ${error.message}. Waiting for push recovery.`); }
openFeed();
