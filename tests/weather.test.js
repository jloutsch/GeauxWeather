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
