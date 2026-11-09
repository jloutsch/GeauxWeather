// Checks that the weather services the app depends on still answer with the fields it reads.
// Run: node scripts/check-apis.mjs   (exit 1 and a list of problems when something changed)
// The Open-Meteo requests are built by the app's own www/js/weather.js, so they can't drift from it.
import fs from 'node:fs';
import vm from 'node:vm';

const UA = { 'User-Agent': 'GeauxWeather API check (github.com/jloutsch/GeauxWeather)', Accept: 'application/json' };
const problems = [];
const ok = [];
const check = (name, cond, detail) => (cond ? ok.push(name) : problems.push(`${name}: ${detail}`));

async function getJson(url, headers) {
  const res = await fetch(url, { headers: headers || UA });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url.split('?')[0]}`);
  return res.json();
}

/** Every variable the app's weather.js asks Open-Meteo for must come back in its merged result. */
async function checkOpenMeteo(model) {
  const requested = {};
  const recordingFetch = (url) => {
    const u = new URL(url);
    if (u.hostname !== 'api.open-meteo.com') return fetch(url); // air quality is checked below
    for (const block of ['current', 'hourly', 'daily', 'minutely_15']) {
      if (u.searchParams.get(block)) requested[block] = u.searchParams.get(block).split(',');
    }
    return fetch(url);
  };
  // Load units.js + weather.js the way index.html does, with the model choice in localStorage
  const store = model ? { forecast_model: model } : {};
  const window = { localStorage: { getItem: (k) => store[k] ?? null, setItem: () => {} } };
  const sandbox = { window, fetch: recordingFetch, URLSearchParams, console };
  for (const f of ['www/js/units.js', 'www/js/weather.js']) vm.runInNewContext(fs.readFileSync(f, 'utf8'), sandbox);
  const data = await window.PureSkyWeather.fetchForecast(42.48, -71.1, 'imperial');

  const label = `open-meteo forecast (${model || 'best_match'})`;
  check(`${label} request seen`, Object.keys(requested).length >= 3, 'weather.js request not recorded');
  for (const [block, vars] of Object.entries(requested)) {
    for (const v of vars) {
      const val = data[block] && data[block][v];
      const present = Array.isArray(val) ? val.some((x) => x != null) : val != null;
      // ECMWF/GEM have no UV of their own: the app fills it from Best match, so it must be present here too
      check(`${label} ${block}.${v}`, present, 'missing or all null');
    }
  }
  for (const v of ['us_aqi', 'european_aqi', 'pm2_5', 'pm10']) {
    check(`${label} air_quality.${v}`, data.air_quality && data.air_quality[v] != null, 'missing');
  }
}

async function main() {
  for (const model of [null, 'ecmwf_ifs025', 'gfs_seamless', 'gem_seamless']) {
    try {
      await checkOpenMeteo(model);
    } catch (e) {
      problems.push(`open-meteo forecast (${model || 'best_match'}): ${e.message}`);
    }
  }

  const tasks = {
    'open-meteo air quality hourly (AQI warning)': async () => {
      const j = await getJson(
        'https://air-quality-api.open-meteo.com/v1/air-quality?latitude=42.48&longitude=-71.1&hourly=us_aqi,european_aqi&forecast_days=1&timezone=auto&timeformat=unixtime'
      );
      const h = j.hourly || {};
      check('air quality hourly.time', Array.isArray(h.time) && h.time.length >= 24, 'missing');
      check('air quality hourly.us_aqi', Array.isArray(h.us_aqi) && h.us_aqi.some((v) => v != null), 'missing or all null');
      check('air quality hourly.european_aqi', Array.isArray(h.european_aqi) && h.european_aqi.some((v) => v != null), 'missing or all null');
      check('air quality utc_offset_seconds', typeof j.utc_offset_seconds === 'number', 'missing');
    },
    'open-meteo geocoding': async () => {
      const j = await getJson('https://geocoding-api.open-meteo.com/v1/search?name=Boston&count=1');
      const r = j.results && j.results[0];
      check('open-meteo geocoding', r && r.latitude != null && r.longitude != null && r.name, 'results[0] lat/lon/name');
    },
    'nws alerts': async () => {
      const j = await getJson('https://api.weather.gov/alerts/active?status=actual&message_type=alert&severity=Severe,Extreme', {
        ...UA,
        Accept: 'application/geo+json',
      });
      check('nws alerts features[]', Array.isArray(j.features), 'no features array');
      const p = j.features && j.features[0] && j.features[0].properties;
      if (p) {
        for (const f of ['id', 'event', 'severity', 'headline', 'areaDesc', 'description']) {
          check(`nws alerts properties.${f}`, f in p, 'field missing');
        }
      }
    },
    'nhc current storms': async () => {
      const j = await getJson('https://www.nhc.noaa.gov/CurrentStorms.json');
      check('nhc activeStorms[]', Array.isArray(j.activeStorms), 'no activeStorms array');
      const s = j.activeStorms && j.activeStorms[0];
      if (s) {
        for (const f of ['id', 'name', 'classification', 'intensity', 'latitudeNumeric', 'longitudeNumeric']) {
          check(`nhc activeStorms[0].${f}`, f in s, 'field missing');
        }
      }
    },
    'environment canada alerts': async () => {
      const j = await getJson('https://api.weather.gc.ca/collections/weather-alerts/items?f=json&lang=en&limit=5');
      check('eccc features[]', Array.isArray(j.features), 'no features array');
      const p = j.features && j.features[0] && j.features[0].properties;
      if (p) {
        for (const f of ['alert_type', 'alert_name_en', 'feature_id', 'feature_name_en', 'alert_text_en', 'status_en', 'expiration_datetime']) {
          check(`eccc properties.${f}`, f in p, 'field missing');
        }
      }
    },
    'bright sky (dwd) alerts': async () => {
      const j = await getJson('https://api.brightsky.dev/alerts?lat=52.52&lon=13.40');
      check('brightsky alerts[] + location', Array.isArray(j.alerts) && j.location && 'warn_cell_id' in j.location, 'alerts/location.warn_cell_id');
      const a = j.alerts && j.alerts[0];
      if (a) {
        for (const f of ['event_code', 'event_en', 'severity', 'headline_en', 'description_en', 'expires', 'status']) {
          check(`brightsky alerts[0].${f}`, f in a, 'field missing');
        }
      }
    },
    'rainviewer': async () => {
      const j = await getJson('https://api.rainviewer.com/public/weather-maps.json');
      check('rainviewer host + radar.past[]', j.host && j.radar && Array.isArray(j.radar.past) && j.radar.past.length, 'host/radar.past');
    },
    'usgs rivers': async () => {
      const j = await getJson(
        'https://waterservices.usgs.gov/nwis/iv/?format=json&sites=07032000&parameterCd=00065,00060&siteStatus=active'
      );
      check('usgs value.timeSeries[]', j.value && Array.isArray(j.value.timeSeries), 'value.timeSeries');
    },
    'nominatim reverse': async () => {
      const j = await getJson('https://nominatim.openstreetmap.org/reverse?format=json&lat=42.48&lon=-71.1&zoom=10');
      check('nominatim address', j.address || j.display_name, 'no address/display_name');
    },
  };
  for (const [name, run] of Object.entries(tasks)) {
    try {
      await run();
    } catch (e) {
      problems.push(`${name}: ${e.message}`);
    }
  }

  console.log(`${ok.length} checks passed`);
  if (problems.length) {
    console.log(`${problems.length} problem(s):`);
    for (const p of problems) console.log(`- ${p}`);
    process.exit(1);
  }
}

await main();
