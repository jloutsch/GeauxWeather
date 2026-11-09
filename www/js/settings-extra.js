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
 * Settings this fork added (units, clock, forecast model, Today layout, forecast notifications,
 * status bar), kept out of app.js so upstream changes to app.js merge with fewer conflicts.
 * Loads before app.js; app.js calls beforeBoot() first thing in boot() and provides
 * window.__geauxReload() to refetch weather after a setting that changes the forecast data.
 */
(function () {
  'use strict';

  const U = window.GeauxUnits;
  const reload = function () {
    if (window.__geauxReload) window.__geauxReload();
  };

  const unitSelects = document.querySelectorAll('select[data-unit]');
  function showUnitPrefs() {
    const u = U.get();
    unitSelects.forEach(function (sel) {
      sel.value = u[sel.dataset.unit];
    });
  }

  /** Ask Android for its 24-hour switch; redraw if "Automatic" time changed since last launch. */
  async function syncPhoneClock() {
    try {
      const plugin = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.GeauxWeatherNative;
      if (!plugin || !plugin.is24HourFormat) return;
      const before = U.uses24h();
      U.setPhone24h((await plugin.is24HourFormat()).value === true);
      if (U.uses24h() !== before) reload();
    } catch (e) {
      console.warn('phone clock setting skipped', e);
    }
  }

  const S = window.GeauxSections;
  const sectionsBox = document.getElementById('today-sections-settings');
  /** Settings → Today screen: a switch per section, plus up/down for the movable ones. */
  function renderSectionSettings(list) {
    S.apply(list, document);
    if (!sectionsBox) return;
    const movable = list.filter((s) => !S.item(s.id).fixed);
    sectionsBox.innerHTML = list
      .map(function (s) {
        const it = S.item(s.id);
        const pos = movable.findIndex((m) => m.id === s.id);
        const arrows = it.fixed
          ? ''
          : '<span class="section-order">' +
            '<button type="button" data-id="' + s.id + '" data-d="-1" aria-label="Move ' + it.label + ' up"' +
            (pos === 0 ? ' disabled' : '') + '>↑</button>' +
            '<button type="button" data-id="' + s.id + '" data-d="1" aria-label="Move ' + it.label + ' down"' +
            (pos === movable.length - 1 ? ' disabled' : '') + '>↓</button></span>';
        return (
          '<div class="setting-row"><label><input type="checkbox" data-id="' + s.id + '"' +
          (s.on ? ' checked' : '') + ' /> ' + it.label + '</label>' + arrows + '</div>'
        );
      })
      .join('');
  }
  if (sectionsBox) {
    sectionsBox.addEventListener('change', function (e) {
      const id = e.target.dataset && e.target.dataset.id;
      if (!id) return;
      const list = S.load().map((s) => (s.id === id ? { id: id, on: e.target.checked } : s));
      S.save(list);
      renderSectionSettings(list);
    });
    sectionsBox.addEventListener('click', function (e) {
      const btn = e.target.closest('button[data-d]');
      if (!btn) return;
      const list = S.move(S.load(), btn.dataset.id, Number(btn.dataset.d));
      S.save(list);
      renderSectionSettings(list);
      // Keep focus on the moved row; its other arrow if this one just greyed out at the end
      const row = sectionsBox.querySelectorAll('button[data-id="' + btn.dataset.id + '"]');
      const next = Array.from(row).find((b) => b.dataset.d === btn.dataset.d && !b.disabled) ||
        Array.from(row).find((b) => !b.disabled);
      if (next) next.focus();
    });
  }

  const modelSelect = document.getElementById('forecast-model');
  if (modelSelect) {
    modelSelect.value = window.PureSkyWeather.forecastModel();
    modelSelect.addEventListener('change', async function () {
      localStorage.setItem('forecast_model', modelSelect.value);
      try {
        // Widget refresh and the tomorrow's-precipitation notification read it natively
        const prefs = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Preferences;
        if (prefs) await prefs.set({ key: 'geauxweather_model', value: modelSelect.value });
      } catch (e) {
        console.warn('model setting skipped', e);
      }
      reload();
    });
  }
  unitSelects.forEach(function (sel) {
    sel.addEventListener('change', async function () {
      U.set(sel.dataset.unit, sel.value);
      if (sel.dataset.unit === 'clock') {
        // Clock widget and native notifications read this from Preferences
        try {
          const plugins = window.Capacitor && window.Capacitor.Plugins;
          if (plugins && plugins.Preferences) {
            await plugins.Preferences.set({ key: 'geauxweather_clock', value: sel.value });
          }
          if (plugins && plugins.GeauxWeatherNative && plugins.GeauxWeatherNative.refreshChrome) {
            await plugins.GeauxWeatherNative.refreshChrome();
          }
        } catch (e) {
          console.warn('clock setting skipped', e);
        }
      }
      reload();
    });
  });

  /**
   * The once-a-day notifications (tomorrow's precipitation, UV, air quality) share one shape: a switch, a
   * level and an hour, saved to localStorage and Preferences (native reads them), then the plugin
   * re-queues or cancels the daily check.
   */
  function wireDaily(ids, keys, defaults, method, label) {
    const toggle = document.getElementById(ids[0]);
    const level = document.getElementById(ids[1]);
    const hour = document.getElementById(ids[2]);
    if (!toggle || !level || !hour) return;
    toggle.checked = localStorage.getItem(keys[0]) === 'on';
    level.value = localStorage.getItem(keys[1]) || defaults[0];
    hour.value = localStorage.getItem(keys[2]) || defaults[1];
    const save = async function () {
      const values = [toggle.checked ? 'on' : 'off', level.value, hour.value];
      try {
        const plugins = window.Capacitor && window.Capacitor.Plugins;
        for (let i = 0; i < keys.length; i++) {
          localStorage.setItem(keys[i], values[i]);
          if (plugins && plugins.Preferences) await plugins.Preferences.set({ key: keys[i], value: values[i] });
        }
        // Re-queues the daily check for the new time (or cancels it)
        if (plugins && plugins.GeauxWeatherNative && plugins.GeauxWeatherNative[method]) {
          await plugins.GeauxWeatherNative[method]({ enabled: toggle.checked });
        }
      } catch (e) {
        console.warn(label + ' notification settings skipped', e);
      }
    };
    [toggle, level, hour].forEach((el) => el.addEventListener('change', save));
  }

  wireDaily(
    ['precip-notify-toggle', 'precip-threshold', 'precip-hour'],
    ['geauxweather_precip_notify', 'geauxweather_precip_threshold', 'geauxweather_precip_hour'],
    ['70', '18'],
    'setPrecipNotify',
    'precipitation'
  );
  wireDaily(
    ['uv-notify-toggle', 'uv-level', 'uv-hour'],
    ['geauxweather_uv_notify', 'geauxweather_uv_level', 'geauxweather_uv_hour'],
    ['8', '8'],
    'setUvNotify',
    'UV'
  );
  wireDaily(
    ['aqi-notify-toggle', 'aqi-level', 'aqi-hour'],
    ['geauxweather_aqi_notify', 'geauxweather_aqi_level', 'geauxweather_aqi_hour'],
    ['1', '8'],
    'setAqiNotify',
    'air quality'
  );

  /** AQI levels read in the scale the app shows: US AQI for °F, European AQI for °C. */
  function labelAqiLevels() {
    const sel = document.getElementById('aqi-level');
    if (!sel) return;
    const us = localStorage.getItem('units') !== 'metric';
    const text = us
      ? ['101 · Unhealthy (sensitive)', '151 · Unhealthy', '201 · Very unhealthy']
      : ['EU 61 · Poor', 'EU 81 · Very poor', 'EU 101 · Extremely poor'];
    Array.from(sel.options).forEach((o, i) => (o.textContent = text[i]));
    sel.value = sel.value; // refresh the styled button's label (select-menu.js)
  }
  labelAqiLevels();

  // Rain or snow in the next hour: native NextHourNotifyHelper reads this from Preferences
  const nextHourToggle = document.getElementById('nexthour-notify-toggle');
  if (nextHourToggle) {
    nextHourToggle.checked = localStorage.getItem('geauxweather_nexthour_notify') === 'on';
    nextHourToggle.addEventListener('change', async function () {
      const value = nextHourToggle.checked ? 'on' : 'off';
      localStorage.setItem('geauxweather_nexthour_notify', value);
      try {
        const plugins = window.Capacitor && window.Capacitor.Plugins;
        if (plugins && plugins.Preferences) {
          await plugins.Preferences.set({ key: 'geauxweather_nexthour_notify', value: value });
        }
        if (plugins && plugins.GeauxWeatherNative && plugins.GeauxWeatherNative.setNextHourNotify) {
          await plugins.GeauxWeatherNative.setNextHourNotify({ enabled: nextHourToggle.checked });
        }
      } catch (e) {
        console.warn('next-hour notification setting skipped', e);
      }
    });
  }

  const statusNotifToggle = document.getElementById('status-notif-toggle');
  if (statusNotifToggle) {
    statusNotifToggle.addEventListener('change', async function () {
      const value = statusNotifToggle.checked ? 'on' : 'off';
      localStorage.setItem('geauxweather_status_notification', value);
      try {
        const plugins = window.Capacitor && window.Capacitor.Plugins;
        if (plugins && plugins.Preferences) {
          await plugins.Preferences.set({ key: 'geauxweather_status_notification', value: value });
        }
        // refreshChrome re-reads the pref: posts the notification when on, cancels it when off
        if (plugins && plugins.GeauxWeatherNative && plugins.GeauxWeatherNative.refreshChrome) {
          await plugins.GeauxWeatherNative.refreshChrome();
        }
      } catch (e) {
        console.warn('status notification toggle skipped', e);
      }
    });
  }

  /**
   * Settings the Android side acts on are saved twice: Capacitor Preferences (read by native code)
   * and localStorage (read by these screens). Preferences is the source of truth; copy it into
   * localStorage and the controls at startup, so a cleared WebView never shows a switch "off" that
   * is still on natively.
   */
  const NATIVE_SETTINGS = [
    // [Preferences key, localStorage key, control id]
    ['geauxweather_hurricane_alerts', 'geauxweather_hurricane_alerts', 'hurricane-alerts-toggle'],
    ['geauxweather_alert_level', 'geauxweather_alert_level', 'alert-level-select'],
    ['geauxweather_nexthour_notify', 'geauxweather_nexthour_notify', 'nexthour-notify-toggle'],
    ['geauxweather_precip_notify', 'geauxweather_precip_notify', 'precip-notify-toggle'],
    ['geauxweather_precip_threshold', 'geauxweather_precip_threshold', 'precip-threshold'],
    ['geauxweather_precip_hour', 'geauxweather_precip_hour', 'precip-hour'],
    ['geauxweather_uv_notify', 'geauxweather_uv_notify', 'uv-notify-toggle'],
    ['geauxweather_uv_level', 'geauxweather_uv_level', 'uv-level'],
    ['geauxweather_uv_hour', 'geauxweather_uv_hour', 'uv-hour'],
    ['geauxweather_aqi_notify', 'geauxweather_aqi_notify', 'aqi-notify-toggle'],
    ['geauxweather_aqi_level', 'geauxweather_aqi_level', 'aqi-level'],
    ['geauxweather_aqi_hour', 'geauxweather_aqi_hour', 'aqi-hour'],
    ['geauxweather_status_notification', 'geauxweather_status_notification', 'status-notif-toggle'],
    ['geauxweather_model', 'forecast_model', 'forecast-model'],
    ['geauxweather_clock', 'unit_clock', 'unit-clock'],
    ['geauxweather_widget_style', 'geauxweather_widget_style', null], // controls set by loadPrefs()
  ];

  async function syncSettingsFromNative() {
    const prefs = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Preferences;
    if (!prefs || !(window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform())) return;
    try {
      const values = await Promise.all(NATIVE_SETTINGS.map((s) => prefs.get({ key: s[0] })));
      NATIVE_SETTINGS.forEach(function (s, i) {
        const v = values[i] && values[i].value;
        if (v == null) return; // never set natively: keep what this screen has
        localStorage.setItem(s[1], v);
        const el = s[2] && document.getElementById(s[2]);
        if (!el) return;
        if (el.type === 'checkbox') el.checked = s[0] === 'geauxweather_status_notification' ? v !== 'off' : v === 'on';
        else el.value = v;
      });
    } catch (e) {
      console.warn('settings sync from Preferences skipped', e);
    }
  }

  // Initial state from this screen's saved values (beforeBoot then applies Preferences on top)
  if (!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform())) {
    // Notification and status-bar rows need the Android app; a browser can't act on them
    document.querySelectorAll('.app-only').forEach((el) => el.classList.add('user-hidden'));
  }
  renderSectionSettings(S.load());
  showUnitPrefs();
  const notifToggle = document.getElementById('status-notif-toggle');
  if (notifToggle) notifToggle.checked = localStorage.getItem('geauxweather_status_notification') !== 'off';

  // Units never picked follow °F/°C: refresh after app.js has saved the new temperature unit
  const unitsSelect = document.getElementById('units');
  if (unitsSelect) {
    unitsSelect.addEventListener('change', () =>
      setTimeout(function () {
        showUnitPrefs();
        labelAqiLevels(); // the AQI scale follows °F/°C
      }, 0)
    );
  }

  /** Settings → Background updates: when the background job last ran, from what it saves natively. */
  async function showBgStatus() {
    const el = document.getElementById('bg-status');
    const prefs = window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.Preferences;
    if (!el || !prefs) return;
    try {
      const get = async (k) => (await prefs.get({ key: k })).value;
      const s = U.bgStatus(
        Date.now(),
        Number(await get('geauxweather_bg_last_run')) || 0,
        Number(await get('geauxweather_bg_interval')) || 0,
        Number(await get('geauxweather_alert_last_check')) || 0,
        Number(await get('geauxweather_bg_since')) || 0
      );
      // Android also holds it while the phone sleeps (Doze) or is offline, so the advice is conditional
      const text = s.stale
        ? s.text + ' — overdue. Android waits while the phone is asleep or offline; if this stays while you use the phone, set GeauxWeather’s battery use to Unrestricted'
        : s.text;
      if (el.textContent !== text) el.textContent = text; // aria-live: announce changes only
      el.classList.toggle('bg-stale', s.stale);
    } catch (e) {
      console.warn('background status skipped', e);
    }
  }
  showBgStatus();
  document.querySelectorAll('[data-screen="settings"]').forEach((b) => b.addEventListener('click', showBgStatus));

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') {
      syncPhoneClock(); // 24-hour switch may have changed
      showBgStatus();
    }
  });

  window.GeauxSettingsExtra = {
    beforeBoot: async function () {
      await syncSettingsFromNative(); // before anything reads these settings (first forecast uses the model)
      syncPhoneClock();
    },
  };
})();
