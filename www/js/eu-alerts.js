/**
 * GeauxWeather
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * eu-alerts.js — MeteoAlarm warnings for EU countries (Germany stays on DWD via Bright Sky).
 *
 * MeteoAlarm names warning areas by code (EMMA_ID, NUTS2/3, FIPS) or, in some countries, gives an
 * outline. data/eu-alert-areas.json (scripts/build-eu-alert-areas.mjs) holds simplified outlines for
 * the codes, so the app finds a location's areas offline, then reads that country's live feed.
 * The matched codes are saved for the native background check, which then needs no map data.
 *
 * Terms (meteoalarm.org, section 5): show the national service and issue time, link to
 * meteoalarm.org and show its delay disclaimer; see the alert sheet in hurricane-alerts.js.
 */
(function (global) {
  'use strict';

  const DATA_URL = 'data/eu-alert-areas.json';
  const FEED_URL = 'https://feeds.meteoalarm.org/api/v1/warnings/feeds-';
  const AREA_KEY = 'geauxweather_eu_area'; // read by HurricaneAlertHelper.java
  const NEAR_DEG = 0.05; // about 5 km: coast and borders lost in simplification still match

  let dataPromise = null;

  /** Cheap check before loading the area file: mainland EU plus the Canaries, Azores and Cyprus. */
  function inEuropeBox(lat, lon) {
    return lat >= 27 && lat <= 72 && lon >= -32 && lon <= 45;
  }

  /** Ray casting on a flat [x0, y0, x1, y1, ...] ring. */
  function inRing(ring, x, y) {
    let inside = false;
    for (let i = 0, j = ring.length - 2; i < ring.length; j = i, i += 2) {
      const xi = ring[i];
      const yi = ring[i + 1];
      const xj = ring[j];
      const yj = ring[j + 1];
      if (yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  }

  /** Inside the outer ring and not in a hole, for any of the polygons. */
  function inPolys(polys, x, y) {
    return polys.some(function (rings) {
      return inRing(rings[0], x, y) && !rings.slice(1).some((hole) => inRing(hole, x, y));
    });
  }

  /** Distance in degrees (longitude scaled to the latitude) from a point to the nearest edge. */
  function edgeDistance(polys, x, y, kx) {
    let best = Infinity;
    for (const rings of polys) {
      for (const r of rings) {
        for (let i = 0; i + 3 < r.length; i += 2) {
          const ax = r[i] * kx;
          const ay = r[i + 1];
          const dx = r[i + 2] * kx - ax;
          const dy = r[i + 3] - ay;
          const len2 = dx * dx + dy * dy;
          const t = len2 ? Math.max(0, Math.min(1, ((x * kx - ax) * dx + (y - ay) * dy) / len2)) : 0;
          best = Math.min(best, Math.hypot(x * kx - (ax + t * dx), y - (ay + t * dy)));
        }
      }
    }
    return best;
  }

  /** Entries are [..., bbox, polys] with bbox/polys in data.scale units; returns those containing the point, else those within NEAR_DEG. */
  function containing(entries, data, lat, lon) {
    const s = data.scale;
    const x = lon * s;
    const y = lat * s;
    const near = NEAR_DEG * s;
    const kx = Math.cos((lat * Math.PI) / 180);
    const candidates = entries.filter(function (e) {
      const b = e[e.length - 2];
      return x >= b[0] - near && x <= b[2] + near && y >= b[1] - near && y <= b[3] + near;
    });
    const hits = candidates.filter((e) => inPolys(e[e.length - 1], x, y));
    if (hits.length) return hits;
    return candidates.filter((e) => edgeDistance(e[e.length - 1], x, y, kx) <= near);
  }

  /**
   * The MeteoAlarm feed and warning-area codes ("TYPE:value") for a point, or null outside the
   * covered countries. Countries whose warnings carry outlines get an empty code list.
   */
  function areasAt(data, lat, lon) {
    const countries = containing(data.countries, data, lat, lon);
    if (!countries.length) return null;
    const cc = countries[0][0];
    const codes = containing(
      data.areas.filter((a) => a[2] === cc),
      data,
      lat,
      lon
    ).map((a) => a[1] + ':' + a[0]);
    return { country: cc, feed: countries[0][1], codes: codes };
  }

  /** CAP polygon: one string of "lat,lon" pairs separated by spaces. */
  function inCapPolygon(poly, lat, lon) {
    const flat = [];
    String(poly)
      .trim()
      .split(/\s+/)
      .forEach(function (pair) {
        const p = pair.split(',');
        flat.push(Number(p[1]), Number(p[0]));
      });
    return flat.length >= 8 && inRing(flat, lon, lat);
  }

  function areaMatches(area, match, lat, lon) {
    const outlines = area.polygon || [];
    // No outlines in the area data for this country (Luxembourg, Sweden, Estonia): a warning area without
    // its own outline can't be placed, so treat it as covering the whole country rather than never showing it
    if (!match.codes.length && !outlines.length) return true;
    const byCode = (area.geocode || []).some((g) => match.codes.includes(g.valueName + ':' + g.value));
    return byCode || (area.polygon || []).some((p) => inCapPolygon(p, lat, lon));
  }

  function capitalize(s) {
    s = String(s || '');
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  const LEVEL_SEVERITY = { 2: 'Moderate', 3: 'Severe', 4: 'Extreme' };

  /**
   * Awareness level from the "awareness_level" parameter ("2; yellow; Moderate"): 1 green (no particular
   * awareness needed, so not a warning) to 4 red. Without it, CAP Minor counts as green. The CAP
   * severity field sometimes disagrees with the level, so severity is taken from the level.
   */
  function awarenessLevel(info) {
    const p = (info.parameter || []).find((x) => x.valueName === 'awareness_level');
    const n = p ? parseInt(String(p.value), 10) : NaN;
    if (n >= 1 && n <= 4) return n;
    return info.severity === 'Minor' ? 1 : 0;
  }

  /** Feed JSON → NWS-style alert properties for the warnings covering this point. */
  function fromMeteoAlarm(json, match, lat, lon, now) {
    now = now == null ? Date.now() : now;
    const out = [];
    ((json && json.warnings) || []).forEach(function (w) {
      const a = w && w.alert;
      if (!a || a.status !== 'Actual' || a.msgType === 'Cancel') return;
      const infos = a.info || [];
      const info = infos.find((i) => /^en/i.test(i.language || '')) || infos[0];
      if (!info) return;
      if (info.expires && Date.parse(info.expires) <= now) return;
      const level = awarenessLevel(info);
      if (level === 1) return;
      const area = (info.area || []).find((ar) => areaMatches(ar, match, lat, lon));
      if (!area) return;
      const event = capitalize(info.event || 'Weather warning');
      const severity = LEVEL_SEVERITY[level] || info.severity || 'Unknown';
      const where = (area.geocode || []).map((g) => g.valueName + ':' + g.value)[0] || area.areaDesc || '';
      out.push({
        // Re-issued warnings get new identifiers; event + severity + area notifies again only when it escalates
        id: 'ma:' + match.feed + '|' + event + '|' + severity + '|' + where,
        identifier: a.identifier || '',
        event: event,
        severity: severity,
        headline: info.headline || '',
        areaDesc: area.areaDesc || '',
        description: info.description || '',
        instruction: info.instruction || '',
        ends: info.expires || '',
        sent: a.sent || info.effective || '',
        source: 'MeteoAlarm',
        sender: info.senderName || '',
        web: info.web || '',
      });
    });
    return out;
  }

  function loadData() {
    if (!dataPromise) {
      dataPromise = fetch(DATA_URL)
        .then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + DATA_URL);
          return r.json();
        })
        .catch(function (e) {
          dataPromise = null; // retry next time
          throw e;
        });
    }
    return dataPromise;
  }

  /** Saved for the native background check; cleared outside the covered countries. */
  async function saveMatch(lat, lon, match) {
    const value = match ? JSON.stringify({ lat: lat, lon: lon, feed: match.feed, codes: match.codes }) : '';
    try {
      const Prefs = global.Capacitor && global.Capacitor.Plugins && global.Capacitor.Plugins.Preferences;
      if (Prefs) {
        if (value) await Prefs.set({ key: AREA_KEY, value: value });
        else await Prefs.remove({ key: AREA_KEY });
      }
    } catch (_) {}
  }

  /**
   * Alerts for a point in a covered EU country, or null when MeteoAlarm doesn't cover it.
   * @param {(url: string, as: string) => Promise<any>} netFetch throws on HTTP errors
   */
  async function fetchAlerts(lat, lon, netFetch) {
    if (!inEuropeBox(lat, lon)) return null;
    const match = areasAt(await loadData(), lat, lon);
    await saveMatch(lat, lon, match);
    if (!match) return null;
    return fromMeteoAlarm(await netFetch(FEED_URL + match.feed, 'json'), match, lat, lon);
  }

  global.PureSkyEuAlerts = {
    inEuropeBox: inEuropeBox,
    areasAt: areasAt,
    fromMeteoAlarm: fromMeteoAlarm,
    fetchAlerts: fetchAlerts,
    AREA_KEY: AREA_KEY,
  };
})(window);
