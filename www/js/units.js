/*
 * GeauxWeather
 * Copyright (C) 2026 TuneFriend / James
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Unit and clock preferences. Temperature stays in the original 'units' key
 * ('imperial' = °F, 'metric' = °C); the others default from it until the user
 * picks one, so existing installs see no change.
 */
(function (global) {
  'use strict';

  const KEYS = {
    wind: 'unit_wind', // mph | kmh | ms | kn (Open-Meteo wind_speed_unit values)
    precip: 'unit_precip', // inch | mm (Open-Meteo precipitation_unit values)
    pressure: 'unit_pressure', // hPa | inHg
    distance: 'unit_distance', // mi | km
    clock: 'unit_clock', // auto | 12 | 24
  };
  const PHONE_CLOCK_KEY = 'unit_clock_phone'; // '24' | '12', the phone's switch, saved by app.js
  const DEFAULTS = {
    imperial: { wind: 'mph', precip: 'inch', pressure: 'hPa', distance: 'mi', clock: 'auto' },
    metric: { wind: 'kmh', precip: 'mm', pressure: 'hPa', distance: 'km', clock: 'auto' },
  };
  const WIND_LABEL = { mph: 'mph', kmh: 'km/h', ms: 'm/s', kn: 'kn' };
  const FROM_MPH = { mph: 1, kmh: 1.609344, ms: 0.44704, kn: 0.868976 };

  function read(key) {
    try {
      return global.localStorage ? global.localStorage.getItem(key) : null;
    } catch (_) {
      return null;
    }
  }

  function get() {
    const temp = read('units') === 'metric' ? 'metric' : 'imperial';
    const base = DEFAULTS[temp];
    const u = { temp: temp };
    for (const k in KEYS) u[k] = read(KEYS[k]) || base[k];
    if (!WIND_LABEL[u.wind]) u.wind = base.wind;
    return u;
  }

  function set(name, value) {
    try {
      global.localStorage.setItem(KEYS[name], value);
    } catch (_) {}
  }

  function windLabel() {
    return WIND_LABEL[get().wind];
  }

  /** Map layers sample wind in mph; convert for display. */
  function fromMph(mph) {
    return Number(mph) * FROM_MPH[get().wind];
  }

  function isMm() {
    return get().precip === 'mm';
  }

  function precipLabel() {
    return isMm() ? 'mm' : 'in';
  }

  /** Open-Meteo returns snowfall in cm with precipitation_unit=mm, inches with inch. */
  function snowLabel() {
    return isMm() ? 'cm' : 'in';
  }

  function formatPressure(hPa) {
    if (hPa == null || isNaN(Number(hPa))) return '—';
    return get().pressure === 'inHg'
      ? (Number(hPa) * 0.0295300).toFixed(2) + ' inHg'
      : Math.round(Number(hPa)) + ' hPa';
  }

  function formatVisibility(meters) {
    if (meters == null) return '—';
    if (get().distance === 'km') {
      return meters >= 1000 ? (meters / 1000).toFixed(1) + ' km' : Math.round(meters) + ' m';
    }
    const miles = meters / 1609.34;
    return miles >= 10 ? Math.round(miles) + ' mi' : miles.toFixed(1) + ' mi';
  }

  /** Automatic follows the phone's 24-hour switch, like the clock widget. */
  function setPhone24h(is24) {
    try {
      global.localStorage.setItem(PHONE_CLOCK_KEY, is24 ? '24' : '12');
    } catch (_) {}
  }

  /** true / false for 12 / 24-hour; undefined (phone switch not known yet) lets the locale decide. */
  function hour12() {
    let c = get().clock;
    if (c !== '12' && c !== '24') c = read(PHONE_CLOCK_KEY);
    return c === '12' ? true : c === '24' ? false : undefined;
  }

  /** Adds the clock preference to toLocale*String options. */
  function timeOpts(opts) {
    const h12 = hour12();
    const o = Object.assign({}, opts);
    if (h12 !== undefined) o.hour12 = h12;
    return o;
  }

  function uses24h() {
    const h12 = hour12();
    if (h12 !== undefined) return !h12;
    try {
      const hc = new Intl.DateTimeFormat([], { hour: 'numeric' }).resolvedOptions().hourCycle;
      return hc === 'h23' || hc === 'h24';
    } catch (_) {
      return false;
    }
  }

  /** Compact hour label for the hourly strip: "3p" or "15". */
  function hourLabel(date) {
    const h = date.getHours();
    if (uses24h()) return String(h).padStart(2, '0');
    return (h % 12 || 12) + (h < 12 ? 'a' : 'p');
  }

  /**
   * Settings → Background updates. The job runs every intervalMin (15/30/60); more than twice that plus
   * 15 minutes without a run (since sinceMs, when it was switched on, if it never ran) means it is overdue.
   * @returns {{text: string, stale: boolean}}
   */
  function bgStatus(nowMs, lastRunMs, intervalMin, alertsAtMs, sinceMs) {
    if (!intervalMin) return { text: 'Off (no widget, next-hour or alert checks)', stale: false };
    const when = (ms) => {
      const d = new Date(ms);
      const sameDay = new Date(nowMs).toDateString() === d.toDateString();
      return d.toLocaleString([], timeOpts(sameDay ? { hour: 'numeric', minute: '2-digit' } : { weekday: 'short', hour: 'numeric', minute: '2-digit' }));
    };
    const parts = [lastRunMs ? 'last ran ' + when(lastRunMs) : 'not run yet', 'every ' + intervalMin + ' min'];
    if (alertsAtMs) parts.push('alerts checked ' + when(alertsAtMs));
    const from = lastRunMs || sinceMs;
    const stale = !!from && nowMs - from > (2 * intervalMin + 15) * 60000;
    return { text: parts.join(' · '), stale: stale };
  }

  global.GeauxUnits = {
    bgStatus: bgStatus,
    KEYS: KEYS,
    get: get,
    set: set,
    windLabel: windLabel,
    fromMph: fromMph,
    isMm: isMm,
    precipLabel: precipLabel,
    snowLabel: snowLabel,
    formatPressure: formatPressure,
    formatVisibility: formatVisibility,
    hour12: hour12,
    setPhone24h: setPhone24h,
    timeOpts: timeOpts,
    uses24h: uses24h,
    hourLabel: hourLabel,
  };
})(typeof window !== 'undefined' ? window : globalThis);
