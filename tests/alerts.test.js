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
    window: { Capacitor: { isNativePlatform: () => true, Plugins: { GeauxWeatherNative: plugin } } },
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

test('in the app, no web polling even if the plugin methods are not attached yet at load', () => {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'hurricane-alerts.js'), 'utf8');
  const noop = () => {};
  const timers = [];
  const sandbox = {
    window: { Capacitor: { isNativePlatform: () => true, Plugins: {} } },
    console,
    setTimeout: (fn, ms) => timers.push(ms),
    setInterval: (fn, ms) => timers.push(ms),
    clearInterval: noop,
    localStorage: { getItem: (k) => (k === 'geauxweather_hurricane_alerts' ? 'on' : null), setItem: noop },
    document: { readyState: 'complete', getElementById: () => null, querySelector: () => null, addEventListener: noop },
  };
  vm.runInNewContext(src, sandbox);
  assert.deepEqual(timers.filter((ms) => ms === 4000 || ms === 15 * 60 * 1000), []);
});

// ── Alerts outside the U.S. (#4 item 2) ──────────────────────────────────────

const ECCC = {
  features: [
    { id: 'x1', properties: { alert_type: 'warning', alert_name_en: 'winter storm warning', feature_id: 'fea1-2302',
      feature_name_en: 'Haida Gwaii', alert_text_en: 'Snow 30 cm.', event_end_datetime: '2026-11-11T05:00:00Z', status_en: 'continued' } },
    { id: 'x2', properties: { alert_type: 'statement', alert_name_en: 'special weather statement', feature_id: 'fea1-9',
      feature_name_en: 'North Coast', status_en: 'new' } },
    { id: 'x3', properties: { alert_type: 'warning', alert_name_en: 'wind warning', feature_id: 'fea1-5', status_en: 'ended' } },
  ],
};
const BRIGHTSKY = {
  location: { name: 'Berl. - Mitte', district: 'Berlin', warn_cell_id: 711000101 },
  alerts: [
    { alert_id: 'a1', event_code: 22, event_en: 'frost', severity: 'minor', headline_en: 'Official WARNING of FROST',
      description_en: 'Risk of frost', instruction_en: 'Protect plants', expires: '2026-11-10T08:00:00+00:00', status: 'actual' },
    { alert_id: 'a2', event_code: 52, event_en: 'storm gusts', severity: 'severe', headline_en: 'Official WARNING of STORM GUSTS',
      status: 'actual' },
    { alert_id: 't', event_code: 1, event_en: 'test', severity: 'minor', status: 'test' },
  ],
};

test('Environment Canada alerts become NWS-style alerts; ended ones are dropped', () => {
  const out = A.fromEccc(ECCC);
  assert.equal(out.length, 2);
  assert.equal(out[0].event, 'Winter Storm Warning');
  assert.equal(out[0].severity, 'Severe');
  assert.equal(out[0].areaDesc, 'Haida Gwaii');
  assert.equal(out[0].ends, '2026-11-11T05:00:00Z');
  assert.equal(out[0].id, 'eccc:winter storm warning|fea1-2302'); // stable across re-issues
  assert.equal(out[1].severity, 'Minor'); // statements are skipped, like NWS Minor alerts
  assert.ok(A.shouldNotify(out[0], 'warnings'));
  assert.equal(A.shouldNotify(out[1], 'advisories'), false);
});

test('Bright Sky (DWD) alerts become NWS-style alerts; test messages are dropped', () => {
  const out = A.fromBrightSky(BRIGHTSKY);
  assert.equal(out.length, 2);
  assert.deepEqual(Array.from(out, (a) => a.event), ['Frost', 'Storm Gusts']);
  assert.deepEqual(Array.from(out, (a) => a.severity), ['Minor', 'Severe']);
  assert.equal(out[0].areaDesc, 'Berl. - Mitte');
  assert.equal(out[0].id, 'dwd:22|minor|711000101');
  assert.equal(A.shouldNotify(out[0], 'advisories'), false); // minor frost: skipped
  assert.ok(A.shouldNotify(out[1], 'warnings'));
});

/** Loads hurricane-alerts.js with a fake PureSkyNet that answers by host. */
function loadWithNet(answers, euAlerts, owmKey) {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'hurricane-alerts.js'), 'utf8');
  const noop = () => {};
  const asked = [];
  const sandbox = {
    window: {
      PureSkyEuAlerts: euAlerts,
      PureSkyNet: {
        fetch: async (url) => {
          const host = new URL(url).hostname;
          asked.push(host);
          const a = answers[host];
          if (typeof a === 'number') throw new Error('HTTP ' + a);
          return a;
        },
      },
    },
    console, URL, setTimeout: noop, setInterval: noop, clearInterval: noop,
    localStorage: { getItem: (k) => (k === 'owm_key' && owmKey) || null, setItem: noop },
    document: { readyState: 'complete', getElementById: () => null, querySelector: () => null, addEventListener: noop },
  };
  vm.runInNewContext(src, sandbox);
  return { A: sandbox.window.PureSkyHurricaneAlerts, asked };
}

test('outside the U.S. (NWS 400) Canada uses Environment Canada', async () => {
  const { A: X, asked } = loadWithNet({ 'api.weather.gov': 400, 'api.weather.gc.ca': ECCC });
  const out = await X.fetchPointAlerts(53.25, -132.07); // Haida Gwaii
  assert.deepEqual(asked, ['api.weather.gov', 'api.weather.gc.ca']);
  assert.equal(out[0].event, 'Winter Storm Warning');
});

test('in Germany it uses Bright Sky; a 404 there means "not covered", not a failure', async () => {
  const de = loadWithNet({ 'api.weather.gov': 400, 'api.brightsky.dev': BRIGHTSKY });
  assert.equal((await de.A.fetchPointAlerts(52.52, 13.4)).length, 2);
  assert.deepEqual(de.asked, ['api.weather.gov', 'api.brightsky.dev']);
  const edge = loadWithNet({ 'api.weather.gov': 400, 'api.brightsky.dev': 404, 'api.weather.gc.ca': { features: [] } });
  assert.deepEqual(Array.from(await edge.A.fetchPointAlerts(47.5, 7.6)), []); // Basel, inside the box but Swiss
});

test('in the other EU countries it uses MeteoAlarm, also where the Germany box overlaps (Prague)', async () => {
  const calls = [];
  const eu = {
    fetchAlerts: async (lat, lon, net) => {
      calls.push([lat, lon]);
      return [{ id: 'ma:czechia|Yellow Wind|Moderate|EMMA_ID:CZ0100', event: 'Yellow Wind', severity: 'Moderate', source: 'MeteoAlarm' }];
    },
  };
  const cz = loadWithNet({ 'api.weather.gov': 400, 'api.brightsky.dev': 404 }, eu);
  const out = await cz.A.fetchPointAlerts(50.08, 14.42);
  assert.equal(out[0].source, 'MeteoAlarm');
  assert.deepEqual(cz.asked, ['api.weather.gov', 'api.brightsky.dev']);
  assert.deepEqual(calls, [[50.08, 14.42]]);
});

test('outside the MeteoAlarm countries (null) it falls through to Environment Canada', async () => {
  const eu = { fetchAlerts: async () => null };
  const ca = loadWithNet({ 'api.weather.gov': 400, 'api.weather.gc.ca': ECCC }, eu);
  assert.equal((await ca.A.fetchPointAlerts(53.25, -132.07))[0].event, 'Winter Storm Warning');
});

test('a MeteoAlarm feed failure is a failure (keeps the seen record), not an empty list', async () => {
  const eu = { fetchAlerts: async () => { throw new Error('HTTP 503'); } };
  const fr = loadWithNet({ 'api.weather.gov': 400 }, eu);
  await assert.rejects(fr.A.fetchPointAlerts(48.86, 2.35), /HTTP 503/);
});

// Regression: ISSUE-002 — Météo-France yellow "Moderate rain-flood warning" matched the U.S. "flood warning"
// always-notify rule, so it notified at the warnings-only level. Found by /qa on 2026-11-10.
test('MeteoAlarm alerts notify by their awareness level only, not by U.S. event keywords', () => {
  const yellow = { event: 'Moderate rain-flood warning', severity: 'Moderate', source: 'MeteoAlarm' };
  assert.equal(A.shouldNotify(yellow, 'warnings'), false);
  assert.ok(A.shouldNotify(yellow, 'advisories'));
  assert.ok(A.shouldNotify(Object.assign({}, yellow, { severity: 'Severe' }), 'warnings'));
  assert.ok(A.shouldNotify({ event: 'Flood Warning', severity: 'Moderate' }, 'warnings')); // NWS unchanged
});

test('MeteoAlarm alerts carry their source, sender and issue time into the alert detail', () => {
  const loc = { lat: 53.35, lon: -6.26, label: 'Dublin' };
  const props = [{ id: 'ma:ireland|Orange Wind|Severe|FIPS:EI07', event: 'Orange Wind', severity: 'Severe',
    areaDesc: 'Dublin', source: 'MeteoAlarm', sender: 'Met Éireann', sent: '2026-11-10T06:00:00+00:00' }];
  const d = A.diffAndCollect(loc, props, [], { listAll: true })[0].detail;
  assert.equal(d.source, 'MeteoAlarm');
  assert.equal(d.sender, 'Met Éireann');
  assert.equal(d.sent, '2026-11-10T06:00:00+00:00');
  assert.equal(d.layer, 'wind');
  const storm = A.diffAndCollect(loc, [Object.assign({}, props[0], { event: 'Red Thunderstorm' })], [], { listAll: true })[0];
  assert.notEqual(storm.detail.layer, 'tornado'); // the Tornado layer shows U.S. (NWS) alerts only
});

test('a real NWS failure is still a failure (keeps the seen record), not a switch to Canada', async () => {
  const { A: X, asked } = loadWithNet({ 'api.weather.gov': 503 });
  await assert.rejects(X.fetchPointAlerts(42.48, -71.1), /HTTP 503/);
  assert.deepEqual(asked, ['api.weather.gov']);
});

test('a Canadian tornado warning opens the radar, not the U.S.-only Tornado layer', () => {
  const tornado = A.fromEccc({
    features: [{ id: 't', properties: { alert_type: 'warning', alert_name_en: 'tornado warning', feature_id: 'f1', status_en: 'new' } }],
  });
  const loc = { lat: 49.9, lon: -97.1, label: 'Winnipeg' };
  const ca = A.diffAndCollect(loc, tornado, [], { listAll: true });
  assert.equal(ca[0].detail.layer, 'radar');
  const us = A.diffAndCollect(loc, [{ id: 'u', event: 'Tornado Warning', severity: 'Extreme' }], [], { listAll: true });
  assert.equal(us[0].detail.layer, 'tornado');
});

// ─── OpenWeatherMap (optional, the user's own key) ─────────────────────────

const NOW_S = Math.floor(Date.now() / 1000);
const OWM = {
  lat: -33.87,
  lon: 151.21,
  alerts: [
    {
      sender_name: 'Australian Government Bureau of Meteorology',
      event: 'Severe Thunderstorm Warning',
      start: NOW_S - 3600,
      end: NOW_S + 3600,
      description: 'Damaging winds and large hail.',
      tags: ['Thunderstorm'],
    },
    { sender_name: 'Met Office', event: 'Yellow warning of rain', start: NOW_S - 600, end: NOW_S + 7200, description: 'Rain.' },
    { sender_name: 'Old', event: 'Flood Warning', start: NOW_S - 9000, end: NOW_S - 60, description: 'Over.' },
  ],
};

test('OpenWeatherMap alert names map to a severity: colour first, then the kind of message', () => {
  assert.equal(A.owmSeverity('Red warning of wind'), 'Extreme');
  assert.equal(A.owmSeverity('Amber warning of snow'), 'Severe');
  assert.equal(A.owmSeverity('Orange Thunderstorm warning'), 'Severe');
  assert.equal(A.owmSeverity('Yellow warning of rain'), 'Moderate');
  assert.equal(A.owmSeverity('Yellow Flood Warning'), 'Moderate'); // colour wins over the U.S. keyword
  assert.equal(A.owmSeverity('Severe Thunderstorm Warning'), 'Severe');
  assert.equal(A.owmSeverity('Heavy rain warning'), 'Severe');
  assert.equal(A.owmSeverity('Tropical Cyclone Advice'), 'Severe'); // storm keyword
  assert.equal(A.owmSeverity('Heavy rain advisory'), 'Moderate');
  assert.equal(A.owmSeverity('Hydrological information'), 'Minor');
});

test('OpenWeatherMap alerts become NWS-style alerts; ended ones are dropped', () => {
  const out = A.fromOwm(OWM);
  assert.equal(out.length, 2);
  assert.equal(out[0].event, 'Severe Thunderstorm Warning');
  assert.equal(out[0].severity, 'Severe');
  assert.equal(out[0].source, 'OpenWeatherMap');
  assert.equal(out[0].sender, 'Australian Government Bureau of Meteorology');
  assert.equal(out[0].description, 'Damaging winds and large hail.');
  assert.equal(out[0].sent, new Date((NOW_S - 3600) * 1000).toISOString());
  assert.equal(out[0].ends, new Date((NOW_S + 3600) * 1000).toISOString());
  // Same sender + event + start on every check → notifies once
  assert.equal(out[0].id, A.fromOwm(OWM)[0].id);
  assert.equal(A.fromOwm({}).length, 0);
});

test('OpenWeatherMap alerts notify by their mapped severity, not by U.S. event keywords', () => {
  const yellow = A.fromOwm({ alerts: [{ sender_name: 'Met Office', event: 'Yellow Flood Warning', start: NOW_S, end: NOW_S + 60 }] })[0];
  assert.equal(A.shouldNotify(yellow, 'warnings'), false);
  assert.ok(A.shouldNotify(yellow, 'advisories'));
});

test('with a key, places outside the U.S., Canada and the MeteoAlarm countries use OpenWeatherMap', async () => {
  const eu = { fetchAlerts: async () => null };
  const au = loadWithNet({ 'api.weather.gov': 400, 'api.openweathermap.org': OWM }, eu, 'k123');
  const out = await au.A.fetchPointAlerts(-33.87, 151.21); // Sydney
  assert.deepEqual(au.asked, ['api.weather.gov', 'api.openweathermap.org']);
  assert.equal(out[0].source, 'OpenWeatherMap');
});

test('without a key, those places stay on the keyless chain (Environment Canada, empty there)', async () => {
  const eu = { fetchAlerts: async () => null };
  const au = loadWithNet({ 'api.weather.gov': 400, 'api.weather.gc.ca': { features: [] } }, eu);
  assert.deepEqual(Array.from(await au.A.fetchPointAlerts(-33.87, 151.21)), []);
  assert.deepEqual(au.asked, ['api.weather.gov', 'api.weather.gc.ca']);
});

test('with a key, Canada stays on Environment Canada and the EU on MeteoAlarm', async () => {
  const none = { fetchAlerts: async () => null };
  const ca = loadWithNet({ 'api.weather.gov': 400, 'api.weather.gc.ca': ECCC }, none, 'k123');
  assert.equal((await ca.A.fetchPointAlerts(53.25, -132.07))[0].event, 'Winter Storm Warning');
  assert.deepEqual(ca.asked, ['api.weather.gov', 'api.weather.gc.ca']);

  const ma = { fetchAlerts: async () => [{ id: 'ma:x', event: 'Yellow Wind', severity: 'Moderate', source: 'MeteoAlarm' }] };
  const fr = loadWithNet({ 'api.weather.gov': 400 }, ma, 'k123');
  assert.equal((await fr.A.fetchPointAlerts(48.86, 2.35))[0].source, 'MeteoAlarm');
  assert.deepEqual(fr.asked, ['api.weather.gov']);
});

test('a rejected key (401) falls back to the keyless chain; other OpenWeatherMap failures are failures', async () => {
  const eu = { fetchAlerts: async () => null };
  const bad = loadWithNet({ 'api.weather.gov': 400, 'api.openweathermap.org': 401, 'api.weather.gc.ca': { features: [] } }, eu, 'bad');
  assert.deepEqual(Array.from(await bad.A.fetchPointAlerts(-33.87, 151.21)), []);
  assert.deepEqual(bad.asked, ['api.weather.gov', 'api.openweathermap.org', 'api.weather.gc.ca']);

  const down = loadWithNet({ 'api.weather.gov': 400, 'api.openweathermap.org': 503 }, eu, 'k123');
  await assert.rejects(down.A.fetchPointAlerts(-33.87, 151.21), /HTTP 503/);
});

test('OpenWeatherMap alerts carry their sender into the alert detail and open the radar, not the Tornado layer', () => {
  const loc = { lat: -33.87, lon: 151.21, label: 'Sydney' };
  const tornado = A.fromOwm({ alerts: [{ sender_name: 'BoM', event: 'Tornado Warning', start: NOW_S, end: NOW_S + 60 }] });
  const d = A.diffAndCollect(loc, tornado, [], { listAll: true })[0].detail;
  assert.equal(d.source, 'OpenWeatherMap');
  assert.equal(d.sender, 'BoM');
  assert.equal(d.layer, 'radar');
});

// Regression: ISSUE-002 — OpenWeatherMap alerts have no area name, so the place showed twice ("Sydney · near Sydney")
// Found by /qa on 2026-11-15
// Report: .gstack/qa-reports/qa-report-owm-key-2026-11-15.md
test('an alert without an area name shows the place once', () => {
  const loc = { lat: -33.87, lon: 151.21, label: 'Sydney' };
  const props = A.fromOwm({ alerts: [{ sender_name: 'BoM', event: 'Severe Thunderstorm Warning', start: NOW_S, end: NOW_S + 60 }] });
  const a = A.diffAndCollect(loc, props, [], { listAll: true })[0];
  assert.equal(a.body, 'Severe · near Sydney');
  assert.equal(a.detail.area, '');
  const nws = A.diffAndCollect(loc, [{ id: 'n', event: 'Flood Warning', severity: 'Severe', areaDesc: 'Middlesex, MA' }], [], { listAll: true })[0];
  assert.equal(nws.body, 'Severe · Middlesex, MA · near Sydney');
});

// Regression: ISSUE-004 — Brazil's INMET names alerts in Portuguese ("Tempestade"); unmatched names fell to Minor and never notified
// Found by /qa on 2026-11-15 (live OpenWeatherMap key, São Paulo)
// Report: .gstack/qa-reports/qa-report-owm-key-2026-11-15.md
test('OpenWeatherMap alert names the rules do not recognise default to Moderate, not Minor', () => {
  const inmet = A.fromOwm({ alerts: [{
    sender_name: 'Instituto Nacional de Meteorologia', event: 'Tempestade', start: NOW_S - 600, end: NOW_S + 3600,
    description: 'Chuva entre 20 e 30 mm/h ou até 50 mm/dia, ventos intensos (40-60 km/h), e queda de granizo. Baixo risco de corte de energia elétrica.',
    tags: ['Wind'],
  }] })[0];
  assert.equal(inmet.severity, 'Moderate');
  assert.equal(A.shouldNotify(inmet, 'warnings'), false);
  assert.ok(A.shouldNotify(inmet, 'advisories'));
  assert.equal(A.owmSeverity('Alerta roja por lluvias'), 'Extreme');
  assert.equal(A.owmSeverity('Aviso laranja de chuva'), 'Severe');
  assert.equal(A.owmSeverity('Alerta naranja'), 'Severe');
  assert.equal(A.owmSeverity('Aviso amarelo'), 'Moderate');
  assert.equal(A.owmSeverity('Coastal flood statement'), 'Minor');
  assert.equal(A.owmSeverity('Fire weather outlook'), 'Minor');
});
