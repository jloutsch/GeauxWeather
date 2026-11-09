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
