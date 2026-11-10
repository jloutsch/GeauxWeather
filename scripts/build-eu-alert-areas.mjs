#!/usr/bin/env node
// SPDX-License-Identifier: GPL-3.0-or-later
/**
 * Builds www/data/eu-alert-areas.json: simplified warning-area outlines for EU countries, so the app
 * can tell which MeteoAlarm warning areas contain a location without a network lookup.
 *
 * Run: node scripts/build-eu-alert-areas.mjs   (downloads ~80 MB, writes ~1-2 MB)
 *
 * Sources (pinned):
 * - MeteoAlarm geocodes (EMMA_ID areas), published for redistributors:
 *   https://gitlab.com/meteoalarm-pm-group/documents  MeteoAlarm_Geocodes_2026_07_31.json
 * - Eurostat GISCO NUTS boundaries 1:3M, 2013 and 2024 editions (© EuroGeographics for the
 *   administrative boundaries): https://gisco-services.ec.europa.eu/distribution/v2/nuts/
 *   Météo-France and Hungary still use 2013 codes; 2024 covers the rest and any newer codes.
 * - Ireland: Met Éireann tags warnings with FIPS 10-4 county codes; mapped to the MeteoAlarm county
 *   outlines by name (table from https://www.statoids.com/uie.html).
 *
 * Germany is left out: the app reads DWD warnings for Germany directly (Bright Sky).
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(ROOT, 'www', 'data', 'eu-alert-areas.json');

const GEOCODES_URL =
  'https://gitlab.com/meteoalarm-pm-group/documents/-/raw/master/MeteoAlarm_Geocodes_2026_07_31.json?inline=false';
const nutsUrl = (year, level) =>
  `https://gisco-services.ec.europa.eu/distribution/v2/nuts/geojson/NUTS_RG_03M_${year}_4326_LEVL_${level}.geojson`;

/** EU member states → MeteoAlarm feed name. NUTS uses EL for Greece. */
const FEEDS = {
  AT: 'austria', BE: 'belgium', BG: 'bulgaria', HR: 'croatia', CY: 'cyprus', CZ: 'czechia',
  DK: 'denmark', EE: 'estonia', FI: 'finland', FR: 'france', DE: 'germany', EL: 'greece',
  HU: 'hungary', IE: 'ireland', IT: 'italy', LV: 'latvia', LT: 'lithuania', LU: 'luxembourg',
  MT: 'malta', NL: 'netherlands', PL: 'poland', PT: 'portugal', RO: 'romania', SK: 'slovakia',
  SI: 'slovenia', ES: 'spain', SE: 'sweden',
};
/** Countries whose warnings name NUTS regions instead of EMMA_IDs (checked against the live feeds). */
const NUTS_COUNTRIES = { FR: [3], BG: [3], RO: [3], HR: [3], HU: [2], BE: [2] };
/** FIPS 10-4 Irish counties (traditional), plus the later GEC codes that map onto them. */
const IE_FIPS = {
  EI01: 'Carlow', EI02: 'Cavan', EI03: 'Clare', EI04: 'Cork', EI06: 'Donegal', EI07: 'Dublin',
  EI10: 'Galway', EI11: 'Kerry', EI12: 'Kildare', EI13: 'Kilkenny', EI14: 'Leitrim', EI15: 'Laois',
  EI16: 'Limerick', EI18: 'Longford', EI19: 'Louth', EI20: 'Mayo', EI21: 'Meath', EI22: 'Monaghan',
  EI23: 'Offaly', EI24: 'Roscommon', EI25: 'Sligo', EI26: 'Tipperary', EI27: 'Waterford',
  EI29: 'Westmeath', EI30: 'Wexford', EI31: 'Wicklow',
  EI32: 'Cork', EI33: 'Dublin', EI34: 'Dublin', EI35: 'Dublin', EI36: 'Galway', EI37: 'Limerick',
  EI38: 'Tipperary', EI39: 'Dublin', EI40: 'Tipperary', EI41: 'Waterford', EI42: 'Limerick',
  EI43: 'Tipperary', EI44: 'Waterford',
};

const AREA_TOLERANCE = 0.004; // degrees, about 400 m
const COUNTRY_TOLERANCE = 0.01;
const SCALE = 1000; // coordinates stored as integer thousandths of a degree (about 100 m)

async function getJson(url) {
  const res = await fetch(url, { headers: { 'User-Agent': 'GeauxWeather build script' } });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  return res.json();
}

/** Douglas-Peucker on one ring of [lon, lat] points. */
function simplify(points, tol) {
  if (points.length <= 4) return points;
  const keep = new Uint8Array(points.length);
  keep[0] = keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  while (stack.length) {
    const [a, b] = stack.pop();
    const [ax, ay] = points[a];
    const [bx, by] = points[b];
    const dx = bx - ax;
    const dy = by - ay;
    const len = Math.hypot(dx, dy);
    let maxD = 0;
    let idx = -1;
    for (let i = a + 1; i < b; i++) {
      // A closed ring starts and ends on the same point: measure from that point instead of a line
      const d = len
        ? Math.abs(dy * points[i][0] - dx * points[i][1] + bx * ay - by * ax) / len
        : Math.hypot(points[i][0] - ax, points[i][1] - ay);
      if (d > maxD) {
        maxD = d;
        idx = i;
      }
    }
    if (maxD > tol && idx > 0) {
      keep[idx] = 1;
      stack.push([a, idx], [idx, b]);
    }
  }
  return points.filter((_, i) => keep[i]);
}

/** GeoJSON geometry → polygons of flat integer rings; drops rings that collapse below a triangle. */
function encode(geometry, tol) {
  const polys = geometry.type === 'Polygon' ? [geometry.coordinates] : geometry.coordinates;
  const out = [];
  for (const poly of polys) {
    const rings = [];
    for (const ring of poly) {
      const flat = [];
      let px = null;
      let py = null;
      for (const [lon, lat] of simplify(ring, tol)) {
        const x = Math.round(lon * SCALE);
        const y = Math.round(lat * SCALE);
        if (x === px && y === py) continue;
        flat.push(x, y);
        px = x;
        py = y;
      }
      if (flat.length >= 8) rings.push(flat); // closed ring: at least 3 distinct points + closing point
    }
    if (rings.length) out.push(rings);
  }
  return out;
}

function bbox(polys) {
  const b = [Infinity, Infinity, -Infinity, -Infinity];
  for (const poly of polys)
    for (let i = 0; i < poly[0].length; i += 2) {
      b[0] = Math.min(b[0], poly[0][i]);
      b[1] = Math.min(b[1], poly[0][i + 1]);
      b[2] = Math.max(b[2], poly[0][i]);
      b[3] = Math.max(b[3], poly[0][i + 1]);
    }
  return b;
}

const iso = (cc) => (cc === 'GR' ? 'EL' : cc);

async function main() {
  const countries = [];
  const areas = [];
  const add = (code, type, cc, geometry) => {
    const p = encode(geometry, AREA_TOLERANCE);
    if (p.length) areas.push([code, type, cc, bbox(p), p]);
  };

  const nuts0 = await getJson(nutsUrl(2024, 0));
  for (const f of nuts0.features) {
    const cc = f.properties.CNTR_CODE;
    if (!FEEDS[cc] || cc === 'DE') continue;
    const p = encode(f.geometry, COUNTRY_TOLERANCE);
    countries.push([cc, FEEDS[cc], bbox(p), p]);
  }

  const geocodes = await getJson(GEOCODES_URL);
  const ieByName = {};
  for (const f of geocodes.features) {
    const cc = iso(f.properties.country);
    if (!FEEDS[cc] || cc === 'DE' || !f.geometry) continue;
    add(f.properties.code, 'EMMA_ID', cc, f.geometry);
    if (cc === 'IE') ieByName[f.properties.name.replace(/^County\s+/i, '')] = f.geometry;
  }
  for (const [fips, county] of Object.entries(IE_FIPS)) {
    if (!ieByName[county]) throw new Error('No MeteoAlarm outline for County ' + county);
    add(fips, 'FIPS', 'IE', ieByName[county]);
  }

  for (const year of [2024, 2013]) {
    for (const level of [2, 3]) {
      const fc = await getJson(nutsUrl(year, level));
      for (const f of fc.features) {
        const cc = f.properties.CNTR_CODE;
        const type = 'NUTS' + level;
        if (!(NUTS_COUNTRIES[cc] || []).includes(level)) continue;
        if (areas.some((a) => a[0] === f.properties.NUTS_ID && a[1] === type)) continue; // 2024 wins
        add(f.properties.NUTS_ID, type, cc, f.geometry);
      }
    }
  }

  const data = {
    version: 1,
    scale: SCALE,
    sources:
      'MeteoAlarm geocodes 2026-07-31 (EUMETNET); NUTS 2013/2024 © EuroGeographics for the administrative boundaries',
    countries,
    areas,
  };
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  fs.writeFileSync(OUT, JSON.stringify(data));
  const kb = Math.round(fs.statSync(OUT).size / 1024);
  console.log(`${countries.length} countries, ${areas.length} areas, ${kb} KB → ${path.relative(ROOT, OUT)}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
