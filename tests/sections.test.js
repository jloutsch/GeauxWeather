// Run: npm test  (node --test tests/*.test.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const SRC = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'sections.js'), 'utf8');

function load() {
  const store = {};
  const sandbox = {
    window: { localStorage: { getItem: (k) => (k in store ? store[k] : null), setItem: (k, v) => (store[k] = v) } },
  };
  vm.runInNewContext(SRC, sandbox);
  return { S: sandbox.window.GeauxSections, store };
}

const ids = (list) => Array.from(list, (s) => s.id).join(',');

test('nothing saved: default order, everything shown', () => {
  const { S } = load();
  const list = S.load();
  assert.equal(ids(list), 'sun-times,metrics-today,next-hour,hourly-section');
  assert.ok(list.every((s) => s.on));
});

test('saved order survives; unknown ids dropped; sections added later appear, shown', () => {
  const { S } = load();
  const list = S.normalize([{ id: 'hourly-section', on: false }, { id: 'gone' }, { id: 'hourly-section' }]);
  assert.equal(ids(list), 'hourly-section,sun-times,metrics-today,next-hour');
  assert.equal(list[0].on, false);
  assert.ok(list.slice(1).every((s) => s.on));
});

test('corrupt saved value falls back to the default', () => {
  const { S, store } = load();
  store.today_sections = '{not json';
  assert.equal(ids(S.load()), 'sun-times,metrics-today,next-hour,hourly-section');
});

test('moving swaps movable neighbors and never moves the fixed section', () => {
  const { S } = load();
  let list = S.load();
  list = S.move(list, 'hourly-section', -1);
  assert.equal(ids(list), 'sun-times,metrics-today,hourly-section,next-hour');
  list = S.move(list, 'metrics-today', -1); // already the first movable: unchanged
  assert.equal(ids(list), 'sun-times,metrics-today,hourly-section,next-hour');
  list = S.move(list, 'next-hour', 1); // already last: unchanged
  assert.equal(ids(list), 'sun-times,metrics-today,hourly-section,next-hour');
  assert.equal(ids(S.move(list, 'sun-times', 1)), ids(list)); // fixed: unchanged
});

test('apply re-appends movable sections in order and hides switched-off ones', () => {
  const { S } = load();
  const appended = [];
  const els = {};
  const el = (id) =>
    (els[id] = els[id] || { id, hidden: false, classList: { toggle: (c, on) => (els[id].hidden = on) } });
  const doc = {
    getElementById: (id) => (id === 'screen-today' ? { appendChild: (e) => appended.push(e.id) } : el(id)),
  };
  S.apply(S.normalize([{ id: 'next-hour' }, { id: 'sun-times', on: false }, { id: 'hourly-section' }]), doc);
  assert.deepEqual(appended, ['next-hour', 'hourly-section', 'metrics-today']);
  assert.equal(els['sun-times'].hidden, true);
  assert.equal(els['next-hour'].hidden, false);
});
