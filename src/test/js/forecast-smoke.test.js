// Native Node smoke: actual page JS, optional live Spring endpoints, minimal DOM.
const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor() { this.children = []; this.textContent = ''; this.hidden = false; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
}

function fixture() {
  return { available: true, actionable: false, stale: false, mode: 'REPLAY', run: {
    asOf: '2026-01-08T16:03:00Z', modelType: 'BASELINE', selectedModel: 'B1', modelVersion: 'B1-v1',
    fallbackReason: 'PYTHON_UNAVAILABLE_OR_INVALID', qualityFlags: ['BASELINE_RANKING_UNRANKED'],
    points: Array.from({ length: 24 }, (_, i) => ({ horizon: i + 1,
      targetPeriod: new Date(Date.parse('2026-01-08T16:30:00Z') + i * 1800000).toISOString(),
      predictedUsep: -10, spikeThreshold: null, spikeFlag: null }))
  }};
}

test('saved endpoint response renders 24 rows, provenance, stale, empty and outage states', async () => {
  const base = process.env.WATTLY_FORECAST_BASE_URL;
  let view = fixture();
  let assessment = { available: false, actualPrice: 20, sampleCount: 8, stale: false };
  let source = fs.readFileSync('src/main/resources/static/js/forecast.js', 'utf8');
  if (base) {
    const response = await fetch(base + '/api/v1/forecast/latest');
    assert.equal(response.status, 200); view = await response.json();
    assessment = await (await fetch(base + '/api/v1/forecast/current-assessment')).json();
    const page = await (await fetch(base + '/forecast.html')).text();
    assert.match(page, /id="forecast-points"/);
    source = await (await fetch(base + '/js/forecast.js')).text();
  }
  const nodes = Object.fromEntries(['forecast-status', 'forecast-metadata', 'forecast-points',
    'forecast-table', 'current-assessment'].map(id => [id, new Element()]));
  const context = vm.createContext({ document: {
    getElementById: id => nodes[id], createElement: () => new Element()
  }, Intl, Date, setInterval: () => {}, Wattly: {
    keepHouseholdInLinks: () => {}, api: async (_method, url) => ({ ok: true,
      data: url.endsWith('/latest') ? view : assessment })
  }});
  vm.runInContext(source, context);
  await vm.runInContext('WattlyForecast.refresh()', context);
  assert.equal(nodes['forecast-points'].children.length, 24);
  assert.equal(nodes['forecast-table'].hidden, false);
  assert.ok(nodes['forecast-metadata'].textContent.startsWith(
    view.run.modelType === 'AI' ? 'AI · ' : 'Baseline ' + view.run.selectedModel + ' · '));
  assert.match(nodes['forecast-metadata'].textContent, /SGT/);
  assert.equal(nodes['forecast-points'].children[0].children.length, 4);
  if (process.env.WATTLY_UI_PROOF) {
    fs.writeFileSync(process.env.WATTLY_UI_PROOF, JSON.stringify({
      endpoint: base, renderedRows: nodes['forecast-points'].children.length,
      modelType: view.run.modelType, modelVersion: view.run.modelVersion, asOf: view.run.asOf
    }));
  }
  view = fixture(); view.run.fallbackReason = 'MODEL_MANIFEST_CONFLICT';
  await vm.runInContext('WattlyForecast.refresh()', context);
  assert.match(nodes['forecast-metadata'].textContent, /AI unavailable; baseline fallback/);
  view = { ...view, stale: true };
  await vm.runInContext('WattlyForecast.refresh()', context);
  assert.match(nodes['forecast-status'].textContent, /Stale forecast/);
  view = { available: false, reason: 'INSUFFICIENT_HISTORY' };
  await vm.runInContext('WattlyForecast.refresh()', context);
  assert.equal(nodes['forecast-points'].children.length, 0);
  assert.equal(nodes['forecast-table'].hidden, true);
  context.Wattly.api = async () => ({ ok: false });
  await vm.runInContext('WattlyForecast.refresh()', context);
  assert.match(nodes['forecast-status'].textContent, /Cannot load/);
});
