// Run: npm test  (node --test tests/*.test.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadWeather() {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'weather.js'), 'utf8');
  const sandbox = { window: {} };
  vm.runInNewContext(src, sandbox);
  return sandbox.window.PureSkyWeather;
}

const W = loadWeather();

test('day icons are unchanged when isDay is omitted or true', () => {
  assert.equal(W.codeToCondition(0).icon, '☀️');
  assert.equal(W.codeToCondition(0, true).icon, '☀️');
  assert.equal(W.codeToCondition(2, true).icon, '⛅');
});

test('clear and mainly clear nights show a moon', () => {
  assert.equal(W.codeToCondition(0, false).icon, '🌙');
  assert.equal(W.codeToCondition(1, false).icon, '🌙');
});

test('no sun icons at night', () => {
  const sunny = ['☀️', '🌤', '⛅', '🌦'];
  for (const code of [0, 1, 2, 3, 45, 48, 51, 53, 55, 61, 63, 65, 71, 73, 75, 80, 81, 82, 95, 96, 99]) {
    const icon = W.codeToCondition(code, false).icon;
    assert.ok(!sunny.includes(icon), `code ${code} shows ${icon} at night`);
  }
});

test('night keeps the condition text', () => {
  assert.equal(W.codeToCondition(0, false).text, 'Clear');
  assert.equal(W.codeToCondition(80, false).text, 'Rain showers');
});

test('unknown code falls back to a cloud day or night', () => {
  assert.deepEqual({ ...W.codeToCondition(12345, false) }, { text: '—', icon: '☁️' });
});

test('a chosen model supplies hourly and daily; Best match supplies the next hour', () => {
  const data = {
    current: { temperature_2m: 55 },
    hourly: { time: ['t0'], temperature_2m_best_match: [50], temperature_2m_ecmwf_ifs025: [52] },
    daily: {
      time: ['d0', 'd1', 'd2'],
      temperature_2m_max_best_match: [60, 61, 62],
      temperature_2m_max_gem_seamless: [58, 59, null], // GEM stops short of the last day
    },
    minutely_15: { time: ['m0'], precipitation_best_match: [0.2], precipitation_gem_seamless: [0] },
  };
  const out = W.applyModel(JSON.parse(JSON.stringify(data)), 'gem_seamless');
  assert.deepEqual(Array.from(out.daily.temperature_2m_max), [58, 59]);
  assert.deepEqual(Array.from(out.daily.time), ['d0', 'd1']);
  assert.deepEqual(Array.from(out.minutely_15.precipitation), [0.2]);
  assert.equal(out.current.temperature_2m, 55);
  assert.equal(W.applyModel(data, 'best_match'), data); // default: untouched
  const ec = W.applyModel(JSON.parse(JSON.stringify(data)), 'ecmwf_ifs025');
  assert.deepEqual(Array.from(ec.hourly.temperature_2m), [52]);
});

test("a model's missing values come from Best match, but its missing days are dropped", () => {
  const data = {
    daily: {
      time: ['d0', 'd1', 'd2'],
      temperature_2m_max_best_match: [60, 61, 62],
      temperature_2m_max_gem_seamless: [58, 59, null],
      uv_index_max_best_match: [3, 4, 5],
      uv_index_max_gem_seamless: [null, null, null], // GEM has no UV
    },
  };
  const out = W.applyModel(data, 'gem_seamless');
  assert.deepEqual(Array.from(out.daily.uv_index_max), [3, 4]);
  assert.deepEqual(Array.from(out.daily.temperature_2m_max), [58, 59]);
});

test('unknown saved model falls back to Best match', () => {
  assert.equal(W.forecastModel(), 'best_match'); // no localStorage in this sandbox
});

test('UV categories follow the WHO bands', () => {
  assert.deepEqual(
    [0, 2.4, 3, 5.6, 6, 7.4, 8, 10.4, 11, 13].map((v) => W.uvLabel(v).text),
    ['Low', 'Low', 'Moderate', 'High', 'High', 'High', 'Very high', 'Very high', 'Extreme', 'Extreme']
  );
});

test('moon phase names match well-known moon events', () => {
  const at = (iso, lat) => W.moonPhase(new Date(iso), lat);
  // 2024-04-08 total solar eclipse (new moon), 2025-03-14 total lunar eclipse (full moon)
  assert.equal(at('2024-04-08T18:21:00Z').name, 'New moon');
  assert.ok(at('2024-04-08T18:21:00Z').illumination <= 2);
  assert.equal(at('2025-03-14T06:55:00Z').name, 'Full moon');
  assert.ok(at('2025-03-14T06:55:00Z').illumination >= 98);
  assert.equal(at('2024-04-15T19:13:00Z').name, 'First quarter');
  assert.equal(at('2025-03-22T11:29:00Z').name, 'Last quarter');
  assert.equal(at('2024-04-11T12:00:00Z').name, 'Waxing crescent');
  assert.equal(at('2025-03-17T12:00:00Z').name, 'Waning gibbous');
});

test('southern hemisphere sees the lit side mirrored', () => {
  const crescent = '2024-04-11T12:00:00Z';
  assert.equal(W.moonPhase(new Date(crescent), 42.5).icon, '🌒');
  assert.equal(W.moonPhase(new Date(crescent), -33.9).icon, '🌘'); // Sydney
  assert.equal(W.moonPhase(new Date('2025-03-14T06:55:00Z'), -33.9).icon, '🌕'); // full looks the same
});

test('moon phase matches the U.S. Naval Observatory for November 2026', () => {
  // aa.usno.navy.mil/api/moon/phases: new 11-09 07:02, first quarter 11-17 11:48, full 11-24 14:53, last quarter 12-01 06:08 UT
  const name = (iso) => W.moonPhase(new Date(iso)).name;
  assert.equal(name('2026-11-09T07:02:00Z'), 'New moon');
  assert.equal(name('2026-11-17T11:48:00Z'), 'First quarter');
  assert.equal(name('2026-11-24T14:53:00Z'), 'Full moon');
  assert.equal(name('2026-12-01T06:08:00Z'), 'Last quarter');
});

// Sunrise and sunset in UTC from the Open-Meteo archive API (the app's own data source).
// Checked 5 minutes either side so a 1-2 minute difference in the solar formula can't flip a result.
const SUN_TIMES = [
  { place: 'Boston, June solstice', lat: 42.36, lon: -71.06, rise: '2025-06-21T09:07Z', set: '2025-06-22T00:24Z' },
  { place: 'Boston, December solstice', lat: 42.36, lon: -71.06, rise: '2025-12-21T12:10Z', set: '2025-12-21T21:15Z' },
  { place: 'Sydney, June solstice', lat: -33.87, lon: 151.21, rise: '2025-06-20T20:59Z', set: '2025-06-21T06:53Z' },
  { place: 'Baton Rouge, March equinox', lat: 30.45, lon: -91.19, rise: '2025-03-20T12:07Z', set: '2025-03-21T00:16Z' },
];

test('isDaytime switches at sunrise and sunset', () => {
  const at = (iso, mins) => new Date(Date.parse(iso) + mins * 60000);
  for (const s of SUN_TIMES) {
    assert.equal(W.isDaytime(at(s.rise, -5), s.lat, s.lon), false, s.place + ': before sunrise');
    assert.equal(W.isDaytime(at(s.rise, 5), s.lat, s.lon), true, s.place + ': after sunrise');
    assert.equal(W.isDaytime(at(s.set, -5), s.lat, s.lon), true, s.place + ': before sunset');
    assert.equal(W.isDaytime(at(s.set, 5), s.lat, s.lon), false, s.place + ': after sunset');
  }
});

test('isDaytime handles polar night and midnight sun', () => {
  // Tromsø: Open-Meteo gives 0 s of daylight on 2025-12-21 and 86400 s on 2025-06-21
  assert.equal(W.isDaytime(new Date('2025-12-21T11:00Z'), 69.65, 18.96), false); // local noon
  assert.equal(W.isDaytime(new Date('2025-06-21T23:00Z'), 69.65, 18.96), true); // local 1 AM
});
