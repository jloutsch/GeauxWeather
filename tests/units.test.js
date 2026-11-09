// Run: npm test  (node --test tests/*.test.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'units.js'), 'utf8');

/** Loads units.js with the given saved settings; returns { U, store }. */
function load(saved) {
  const store = Object.assign({}, saved);
  const localStorage = {
    getItem: (k) => (k in store ? store[k] : null),
    setItem: (k, v) => {
      store[k] = String(v);
    },
  };
  const sandbox = { window: { localStorage } };
  vm.runInNewContext(SRC, sandbox);
  return { U: sandbox.window.GeauxUnits, store };
}

test('existing installs keep their units: defaults follow the °F/°C setting', () => {
  const imp = load({}).U.get();
  assert.equal(imp.wind, 'mph');
  assert.equal(imp.precip, 'inch');
  assert.equal(imp.distance, 'mi');
  assert.equal(imp.pressure, 'hPa'); // pressure was always hPa
  const met = load({ units: 'metric' }).U.get();
  assert.equal(met.wind, 'kmh');
  assert.equal(met.precip, 'mm');
  assert.equal(met.distance, 'km');
});

test('a chosen unit overrides the default and survives a °F/°C change', () => {
  const { U, store } = load({ units: 'imperial' });
  U.set('wind', 'kn');
  store.units = 'metric';
  assert.equal(U.get().wind, 'kn');
  assert.equal(U.get().precip, 'mm'); // not chosen, so it follows °C
  assert.equal(U.windLabel(), 'kn');
});

test('unknown saved wind unit falls back to the default', () => {
  assert.equal(load({ unit_wind: 'furlongs' }).U.get().wind, 'mph');
});

test('map wind converts from mph', () => {
  const { U } = load({ unit_wind: 'kmh' });
  assert.equal(Math.round(U.fromMph(10)), 16);
  assert.equal(Math.round(load({ unit_wind: 'ms' }).U.fromMph(10) * 10) / 10, 4.5);
  assert.equal(Math.round(load({ unit_wind: 'kn' }).U.fromMph(10) * 10) / 10, 8.7);
});

test('pressure in hPa or inHg', () => {
  assert.equal(load({}).U.formatPressure(1013.25), '1013 hPa');
  assert.equal(load({ unit_pressure: 'inHg' }).U.formatPressure(1013.25), '29.92 inHg');
  assert.equal(load({}).U.formatPressure(null), '—');
});

test('visibility follows the distance unit, not temperature', () => {
  assert.equal(load({ units: 'metric', unit_distance: 'mi' }).U.formatVisibility(16093.4), '10 mi');
  assert.equal(load({ unit_distance: 'km' }).U.formatVisibility(500), '500 m');
});

test('rain in mm means snow in cm', () => {
  const { U } = load({ unit_precip: 'mm' });
  assert.equal(U.precipLabel(), 'mm');
  assert.equal(U.snowLabel(), 'cm');
  assert.equal(load({}).U.snowLabel(), 'in');
});

test('hour labels and time options follow the clock setting', () => {
  const afternoon = new Date(2026, 10, 9, 15, 0);
  const morning = new Date(2026, 10, 9, 7, 0);
  const h24 = load({ unit_clock: '24' }).U;
  assert.equal(h24.hourLabel(afternoon), '15');
  assert.equal(h24.hourLabel(morning), '07');
  assert.equal(h24.timeOpts({ hour: 'numeric' }).hour12, false);
  const h12 = load({ unit_clock: '12' }).U;
  assert.equal(h12.hourLabel(afternoon), '3p');
  assert.equal(h12.hourLabel(new Date(2026, 10, 9, 0, 0)), '12a');
  assert.equal(h12.timeOpts({}).hour12, true);
  assert.equal('hour12' in load({}).U.timeOpts({}), false); // automatic, phone switch unknown: locale decides
});

test('automatic follows the phone 24-hour switch; an explicit choice wins', () => {
  const afternoon = new Date(2026, 10, 9, 15, 0);
  const { U } = load({});
  U.setPhone24h(true);
  assert.equal(U.hourLabel(afternoon), '15');
  assert.equal(U.timeOpts({}).hour12, false);
  U.setPhone24h(false);
  assert.equal(U.hourLabel(afternoon), '3p');
  U.set('clock', '24');
  assert.equal(U.hourLabel(afternoon), '15');
});

test('background status: off, not yet run, normal and stale', () => {
  const { U } = load({ unit_clock: '24' });
  const now = new Date(2026, 10, 10, 7, 0).getTime();
  const min = 60000;
  assert.equal(U.bgStatus(now, 0, 0, 0).text, 'Off (no widget, next-hour or alert checks)');
  assert.equal(U.bgStatus(now, 0, 30, 0).text, 'not run yet · every 30 min');
  const ok = U.bgStatus(now, now - 12 * min, 30, now - 40 * min);
  assert.equal(ok.text, 'last ran 06:48 · every 30 min · alerts checked 06:20');
  assert.equal(ok.stale, false);
  assert.equal(U.bgStatus(now, now - 75 * min, 30, 0).stale, false); // 2 × 30 + 15 = 75 min allowed
  assert.equal(U.bgStatus(now, now - 76 * min, 30, 0).stale, true);
  assert.match(U.bgStatus(now, now - 10 * 60 * min, 30, 0).text, /last ran \S+ 21:00/); // yesterday shows the weekday
});
