// Run: npm test  (node --test tests/*.test.js)
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function loadEu() {
  const src = fs.readFileSync(path.join(__dirname, '..', 'www', 'js', 'eu-alerts.js'), 'utf8');
  const sandbox = { window: {}, console };
  vm.runInNewContext(src, sandbox);
  return sandbox.window.PureSkyEuAlerts;
}

const EU = loadEu();
const DATA = JSON.parse(
  fs.readFileSync(path.join(__dirname, '..', 'www', 'data', 'eu-alert-areas.json'), 'utf8')
);
const at = (lat, lon) => EU.areasAt(DATA, lat, lon);

test('cities resolve to their country feed and the area codes their warnings use', () => {
  const paris = at(48.8566, 2.3522);
  assert.equal(paris.feed, 'france');
  assert.ok(paris.codes.includes('NUTS3:FR101'), 'Météo-France tags Paris as NUTS 2013 FR101');

  const dublin = at(53.3498, -6.2603);
  assert.equal(dublin.feed, 'ireland');
  assert.ok(dublin.codes.includes('FIPS:EI07'), 'Met Éireann tags Dublin as FIPS EI07');

  const budapest = at(47.4979, 19.0402);
  assert.equal(budapest.feed, 'hungary');
  assert.ok(budapest.codes.includes('NUTS2:HU10'), '2013 code still used by the Hungarian feed');

  assert.ok(at(50.8503, 4.3517).codes.includes('NUTS2:BE10')); // Brussels
  assert.ok(at(44.4268, 26.1025).codes.includes('NUTS3:RO321')); // Bucharest
  assert.ok(at(42.6977, 23.3219).codes.includes('NUTS3:BG411')); // Sofia

  const rome = at(41.9028, 12.4964);
  assert.equal(rome.feed, 'italy');
  assert.ok(rome.codes.some((c) => c.startsWith('EMMA_ID:IT')));
});

test('coastal cities just outside a simplified outline still match', () => {
  for (const [name, lat, lon, feed] of [
    ['Lisbon', 38.7223, -9.1393, 'portugal'],
    ['Nice', 43.7102, 7.262, 'france'],
    ['Valletta', 35.8989, 14.5146, 'malta'],
    ['Palma', 39.5696, 2.6502, 'spain'],
  ]) {
    const m = at(lat, lon);
    assert.ok(m, name + ' should resolve');
    assert.equal(m.feed, feed, name);
  }
});

test('countries whose warnings carry their own outlines resolve with no area codes', () => {
  const stockholm = at(59.3293, 18.0686);
  assert.equal(stockholm.feed, 'sweden');
  assert.deepEqual([...stockholm.codes], []);
  assert.equal(at(52.3676, 4.9041).feed, 'netherlands'); // Amsterdam
});

test('places outside the EU feeds return null; Germany is left to DWD', () => {
  assert.equal(at(51.5074, -0.1278), null); // London
  assert.equal(at(59.9139, 10.7522), null); // Oslo
  assert.equal(at(52.52, 13.405), null); // Berlin
  assert.equal(at(35.6895, 139.6917), null); // Tokyo
  assert.equal(EU.inEuropeBox(35.6895, 139.6917), false);
  assert.equal(EU.inEuropeBox(48.8566, 2.3522), true);
});

// Shaped like the live feed (feeds.meteoalarm.org/api/v1/warnings/feeds-<country>)
function warning(over) {
  const o = Object.assign(
    {
      id: 'a1',
      msgType: 'Alert',
      status: 'Actual',
      sent: '2026-11-10T06:00:00+00:00',
      event: 'Yellow Wind',
      severity: 'Moderate',
      expires: '2026-11-11T06:00:00+00:00',
      area: { areaDesc: 'Dublin', geocode: [{ valueName: 'FIPS', value: 'EI07' }] },
    },
    over
  );
  const info = (language, event) => ({
    language: language,
    event: event,
    severity: o.severity,
    headline: event + ' warning for ' + o.area.areaDesc,
    description: 'Gusts of 90 km/h.',
    instruction: language === 'en-GB' ? 'Secure loose objects.' : 'Sécurisez les objets.',
    senderName: 'Met Éireann',
    effective: o.sent,
    expires: o.expires,
    web: 'https://www.met.ie/warnings',
    area: [o.area],
  });
  return {
    alert: {
      identifier: o.id,
      msgType: o.msgType,
      status: o.status,
      sent: o.sent,
      info: [info('fr-FR', 'Vent jaune'), info('en-GB', o.event)],
    },
  };
}
const NOW = Date.parse('2026-11-10T12:00:00Z');
const DUBLIN = { feed: 'ireland', codes: ['FIPS:EI07', 'EMMA_ID:IE001'] };

test('MeteoAlarm warnings become NWS-style alerts in English with source and issue time', () => {
  const out = EU.fromMeteoAlarm({ warnings: [warning()] }, DUBLIN, 53.35, -6.26, NOW);
  assert.equal(out.length, 1);
  const a = out[0];
  assert.equal(a.event, 'Yellow Wind');
  assert.equal(a.severity, 'Moderate');
  assert.equal(a.instruction, 'Secure loose objects.');
  assert.equal(a.areaDesc, 'Dublin');
  assert.equal(a.ends, '2026-11-11T06:00:00+00:00');
  assert.equal(a.sent, '2026-11-10T06:00:00+00:00');
  assert.equal(a.source, 'MeteoAlarm');
  assert.equal(a.sender, 'Met Éireann');
  assert.ok(a.id.startsWith('ma:ireland|'));
});

test('only warnings for this location, still running, and real are kept', () => {
  const ws = [
    warning({ id: 'other-county', area: { areaDesc: 'Kerry', geocode: [{ valueName: 'FIPS', value: 'EI11' }] } }),
    warning({ id: 'same-value-other-type', area: { areaDesc: 'X', geocode: [{ valueName: 'NUTS3', value: 'EI07' }] } }),
    warning({ id: 'ended', expires: '2026-11-10T11:00:00+00:00' }),
    warning({ id: 'exercise', status: 'Exercise' }),
    warning({ id: 'cancel', msgType: 'Cancel' }),
    warning({ id: 'keep' }),
  ];
  const out = EU.fromMeteoAlarm({ warnings: ws }, DUBLIN, 53.35, -6.26, NOW);
  assert.deepEqual([...out.map((a) => a.identifier)], ['keep']);
});

test('warnings with their own outline match by point (Netherlands, Sweden)', () => {
  const nl = { feed: 'netherlands', codes: [] };
  const box = (lat0, lon0, lat1, lon1) =>
    [[lat0, lon0], [lat0, lon1], [lat1, lon1], [lat1, lon0], [lat0, lon0]].map((p) => p.join(',')).join(' ');
  const inside = warning({ id: 'in', area: { areaDesc: 'Utrecht', polygon: [box(51.9, 4.8, 52.2, 5.3)] } });
  const outside = warning({ id: 'out', area: { areaDesc: 'Groningen', polygon: [box(53.0, 6.3, 53.5, 7.2)] } });
  const out = EU.fromMeteoAlarm({ warnings: [inside, outside] }, nl, 52.09, 5.12, NOW);
  assert.deepEqual([...out.map((a) => a.identifier)], ['in']);
});

test('the same warning re-issued with new times keeps its id; escalating changes it', () => {
  const m = (over) => EU.fromMeteoAlarm({ warnings: [warning(over)] }, DUBLIN, 53.35, -6.26, NOW)[0].id;
  assert.equal(m({ id: 'v1' }), m({ id: 'v2', sent: '2026-11-10T09:00:00+00:00' }));
  assert.notEqual(m({}), m({ event: 'Orange Wind', severity: 'Severe' }));
});

test('a warning with no English text falls back to the first language', () => {
  const w = warning();
  w.alert.info = [w.alert.info[0]];
  const out = EU.fromMeteoAlarm({ warnings: [w] }, DUBLIN, 53.35, -6.26, NOW);
  assert.equal(out[0].event, 'Vent jaune');
});
