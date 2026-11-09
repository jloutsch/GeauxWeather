// Run: npm test  (node --test tests/*.test.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadAlerts() {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'hurricane-alerts.js'), 'utf8');
  const noop = () => {};
  const sandbox = {
    window: {},
    console,
    setTimeout: noop,
    setInterval: noop,
    clearInterval: noop,
    localStorage: { getItem: () => null, setItem: noop },
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
