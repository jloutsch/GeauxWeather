// Run: npm test  (node --test tests/*.test.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadAlerts(storage) {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'hurricane-alerts.js'), 'utf8');
  const noop = () => {};
  const sandbox = {
    window: {},
    console,
    setTimeout: noop,
    setInterval: noop,
    clearInterval: noop,
    localStorage: storage || { getItem: () => null, setItem: noop },
    document: {
      readyState: 'complete',
      getElementById: () => null,
      querySelector: () => null,
      addEventListener: noop,
    },
  };
  vm.runInNewContext(src, sandbox);
  return sandbox.window.PureSkyHurricaneAlerts;
}

const A = loadAlerts();

test('original storm events notify regardless of severity', () => {
  assert.ok(A.shouldNotify({ event: 'Tornado Warning', severity: 'Extreme' }, 'warnings'));
  assert.ok(A.shouldNotify({ event: 'Tornado Watch', severity: 'Moderate' }, 'warnings'));
  assert.ok(A.shouldNotify({ event: 'Hurricane Watch', severity: 'Unknown' }, 'warnings'));
});

test('warnings level adds every Extreme and Severe alert', () => {
  assert.ok(A.shouldNotify({ event: 'Winter Storm Warning', severity: 'Severe' }, 'warnings'));
  assert.ok(A.shouldNotify({ event: 'High Wind Warning', severity: 'Severe' }, 'warnings'));
  assert.ok(A.shouldNotify({ event: 'Excessive Heat Warning', severity: 'Extreme' }, 'warnings'));
});

test('advisories (Moderate) only when opted in', () => {
  const wind = { event: 'Wind Advisory', severity: 'Moderate' };
  assert.equal(A.shouldNotify(wind, 'warnings'), false);
  assert.ok(A.shouldNotify(wind, 'advisories'));
});

test('Minor alerts never notify', () => {
  assert.equal(A.shouldNotify({ event: 'Frost Advisory', severity: 'Minor' }, 'advisories'), false);
  assert.equal(A.shouldNotify({ event: 'Small Craft Advisory', severity: 'Minor' }, 'advisories'), false);
});

test('new event types open a fitting map layer instead of tornado', () => {
  assert.equal(A.mapLayerForEvent('Winter Storm Warning'), 'radar');
  assert.equal(A.mapLayerForEvent('High Wind Warning'), 'wind');
  assert.equal(A.mapLayerForEvent('Dense Fog Advisory'), 'fog');
  assert.equal(A.mapLayerForEvent('Storm Surge Warning'), 'hurricane');
  assert.equal(A.mapLayerForEvent('Tornado Warning'), 'tornado');
  assert.equal(A.mapLayerForEvent('Flash Flood Warning'), 'lightning');
});

test('in-app list shows already-notified alerts and never touches the seen record', () => {
  const key = 'nws:urn:test:1';
  const seen = JSON.stringify({ [key]: { event: 'Winter Storm Warning', headline: '' } });
  const writes = [];
  const B = loadAlerts({
    getItem: (k) => (k === 'geauxweather_severe_seen' ? seen : null),
    setItem: (k, v) => writes.push(k),
  });
  const loc = { lat: 42.48, lon: -71.1, label: 'Stoneham' };
  const nws = [{ id: 'urn:test:1', event: 'Winter Storm Warning', severity: 'Severe', areaDesc: 'Middlesex', ends: '2026-11-09T18:00:00-05:00' }];
  const items = B.collectForList(loc, nws, []);
  assert.equal(items.length, 1);
  assert.equal(items[0].detail.event, 'Winter Storm Warning');
  assert.equal(items[0].detail.place, 'Stoneham');
  assert.deepEqual(writes, []);
});

test('in-app list puts the most severe alert first', () => {
  const loc = { lat: 27.56, lon: -97.28, label: 'Corpus Christi' };
  const nws = [
    { id: 'a', event: 'Rip Current Statement', severity: 'Moderate' },
    { id: 'b', event: 'Flood Watch', severity: 'Severe' },
  ];
  assert.deepEqual(Array.from(A.collectForList(loc, nws, []), (x) => x.detail.event), ['Flood Watch', 'Rip Current Statement']);
});

test('a failed NWS request leaves the current alert list on screen', async () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'hurricane-alerts.js'), 'utf8');
  const noop = () => {};
  const list = { innerHTML: 'Tornado Warning', classList: { toggle: noop, contains: () => false }, querySelectorAll: () => [] };
  const sandbox = {
    window: {
      PureSkyLocation: { loadSavedLocation: async () => ({ lat: 35, lon: -97, label: 'Moore' }) },
      PureSkyNet: { fetch: async () => { throw new Error('HTTP 500'); } },
    },
    console: { warn: noop, log: noop },
    setTimeout: noop, setInterval: noop, clearInterval: noop,
    localStorage: { getItem: () => null, setItem: noop },
    document: { readyState: 'complete', getElementById: (id) => (id === 'alerts-list' ? list : null), querySelector: () => null, addEventListener: noop },
  };
  vm.runInNewContext(src, sandbox);
  await sandbox.window.PureSkyHurricaneAlerts.renderActiveList();
  assert.equal(list.innerHTML, 'Tornado Warning');
});

test('a failed NWS check keeps the seen record, so the next check neither re-notifies nor goes quiet', () => {
  const key = 'nws:urn:active:1';
  let saved = null;
  const B = loadAlerts({
    getItem: (k) => (k === 'geauxweather_severe_seen' ? JSON.stringify({ [key]: { event: 'Tornado Warning', headline: '' } }) : null),
    setItem: (k, v) => { if (k === 'geauxweather_severe_seen') saved = JSON.parse(v); },
  });
  B.diffAndCollect({ lat: 35, lon: -97, label: 'Moore' }, [], [], { keepNws: true });
  assert.ok(saved && saved[key], 'seen record lost the active warning after a failed request');
});

test('in the app, checkNow leaves notifications to the native check (#23)', async () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'hurricane-alerts.js'), 'utf8');
  const noop = () => {};
  const calls = [];
  const plugin = {
    checkHurricaneAlerts: async (o) => calls.push(['native', o]),
    showHurricaneAlert: async () => calls.push(['notify']),
  };
  const sandbox = {
    window: { Capacitor: { Plugins: { GeauxWeatherNative: plugin } } },
    console,
    setTimeout: noop,
    setInterval: () => calls.push(['poll']),
    clearInterval: noop,
    fetch: async () => {
      calls.push(['fetch']);
      throw new Error('no network in tests');
    },
    localStorage: { getItem: (k) => (k === 'geauxweather_hurricane_alerts' ? 'on' : null), setItem: noop },
    document: { readyState: 'complete', getElementById: () => null, querySelector: () => null, addEventListener: noop },
  };
  vm.runInNewContext(src, sandbox);
  const res = await sandbox.window.PureSkyHurricaneAlerts.checkNow();
  assert.equal(res.native, true);
  assert.deepEqual(calls.map((c) => c[0]), ['native']); // no web fetch, no web notification, no polling
});
