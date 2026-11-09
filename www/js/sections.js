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
 * Today screen layout chosen in Settings: which sections show, and in what order.
 * Current conditions and the alerts list always stay on top (alerts can't be hidden).
 */
(function (global) {
  'use strict';

  const KEY = 'today_sections';
  /** Default order. `fixed` sections can be hidden but stay where they are. */
  const ITEMS = [
    { id: 'sun-times', label: 'Sunrise & sunset', fixed: true },
    { id: 'metrics-today', label: 'Details (wind, humidity, AQI…)' },
    { id: 'next-hour', label: 'Next hour rain' },
    { id: 'hourly-section', label: 'Hourly' },
  ];

  function item(id) {
    return ITEMS.find(function (i) {
      return i.id === id;
    });
  }

  /** Saved [{id, on}] → full list in display order: unknown ids dropped, sections added later appended. */
  function normalize(saved) {
    const out = [];
    (Array.isArray(saved) ? saved : []).forEach(function (s) {
      if (s && item(s.id) && !out.some((o) => o.id === s.id)) out.push({ id: s.id, on: s.on !== false });
    });
    ITEMS.forEach(function (i) {
      if (!out.some((o) => o.id === i.id)) out.push({ id: i.id, on: true });
    });
    return out;
  }

  function load() {
    try {
      return normalize(JSON.parse(global.localStorage.getItem(KEY)));
    } catch (_) {
      return normalize(null);
    }
  }

  function save(list) {
    try {
      global.localStorage.setItem(KEY, JSON.stringify(list));
    } catch (_) {}
  }

  /** Swap a movable section with the next movable one up (delta -1) or down (+1). */
  function move(list, id, delta) {
    const movable = list.filter((s) => !item(s.id).fixed);
    const i = movable.findIndex((s) => s.id === id);
    const j = i + delta;
    if (i < 0 || j < 0 || j >= movable.length) return list;
    const a = list.indexOf(movable[i]);
    const b = list.indexOf(movable[j]);
    const out = list.slice();
    out[a] = list[b];
    out[b] = list[a];
    return out;
  }

  /** Re-append movable sections to the Today screen in order; hide the ones switched off. */
  function apply(list, doc) {
    const screen = doc.getElementById('screen-today');
    list.forEach(function (s) {
      const el = doc.getElementById(s.id);
      if (!el) return;
      if (screen && !item(s.id).fixed) screen.appendChild(el);
      el.classList.toggle('user-hidden', !s.on);
    });
  }

  global.GeauxSections = { ITEMS: ITEMS, item: item, normalize: normalize, load: load, save: save, move: move, apply: apply };
})(typeof window !== 'undefined' ? window : globalThis);
