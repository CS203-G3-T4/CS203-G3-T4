const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor() { this.children = []; this.attributes = {}; this.textContent = ''; this.hidden = true; this.value = ''; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
  setAttribute(key, value) { this.attributes[key] = value; }
  addEventListener() {}
}
function fixture() {
  const time = '2026-09-28T00:00:00+08:00';
  const score = { mae: 5, eligibleOrigins: 24, eligiblePairs: 576 };
  const points = Array.from({ length: 24 }, (_, i) => ({ horizon: i + 1,
    targetPeriod: new Date(Date.parse(time) + (i + 1) * 1800000).toISOString(), actual: -10, AI: -5, B1: -10, B2: -20, B3: null }));
  return { schemaVersion: 1, actionable: false, productionApproved: false, label: 'Experimental — limited training data.',
    protocol: { validationSelectedBaseline: 'B1', trainingStart: time, trainingCutoff: time,
      validationStart: time, validationEnd: time, testStart: time, testEnd: '2026-09-29T00:00:00+08:00' },
    test: { commonPairs: 576, table: { AI: score, B1: { ...score, mae: 0 }, B2: score } },
    allMethods: { commonPairs: 0, table: { AI: score, B1: score, B2: score, B3: { eligibleOrigins: 0, eligiblePairs: 0 } } },
    exclusions: Object.fromEntries(['AI', 'B1', 'B2', 'B3'].map(m => [m, { PURGED_SPLIT_BOUNDARY: 576 }])),
    quality: { periodCount: 289, from: time, to: time, revisionRows: 11, missingHalfHours: 3, parsingFailures: [] },
    limitations: ['One day only'], origins: [{ asOf: time, points }], actuals: points };
}

test('presentation shows paired metrics, AI losses, 24 points and distinct live states', async () => {
  let report = fixture();
  let source = fs.readFileSync('src/main/resources/static/js/forecast-demo.js', 'utf8');
  let price = { price: -10, freshness: 'LIVE', intervalStart: report.protocol.testStart, sourceUpdatedAt: report.protocol.testStart };
  const base = process.env.WATTLY_DEMO_BASE_URL;
  if (base) {
    const response = await fetch(base + '/forecast-demo.json'); assert.equal(response.status, 200); report = await response.json();
    source = await (await fetch(base + '/js/forecast-demo.js')).text();
    price = await (await fetch(base + '/api/v1/market-prices/latest')).json();
    const page = await (await fetch(base + '/forecast-demo.html')).text();
    assert.match(page, /Experimental — limited training data\./);
  }
  const nodes = {};
  const context = vm.createContext({ document: { getElementById: id => nodes[id] ||= new Element(),
    createElement: () => new Element(), createElementNS: () => new Element() }, Intl, Date, setInterval: () => {},
    WattlyForecast: { refresh: () => {} }, Wattly: { api: async (_method, path) => ({ ok: true,
      data: path.endsWith('.json') ? report : price }) } });
  vm.runInContext(source, context);
  await vm.runInContext('WattlyDemo.load()', context);
  assert.equal(nodes['demo-results'].hidden, false);
  assert.equal(nodes['demo-points'].children.length, 24);
  assert.equal(nodes['demo-scores'].children.length, 4);
  assert.ok(nodes['demo-chart'].children.length > 15);
  assert.match(nodes['demo-verdict'].textContent, /more error than B1/);
  assert.equal(nodes['demo-ai-mae'].textContent, report.test.table.AI.mae.toFixed(2));
  assert.match(nodes['demo-window'].textContent, /12-hour target window.*end exclusive/);
  assert.match(nodes['demo-pair-explanation'].textContent, /576 included pairs.*576 excluded pairs/);
  assert.match(nodes['demo-spikes'].textContent, /Insufficient history/);
  const highlight = nodes['demo-chart'].children.find(n => n.attributes.id === 'demo-window-highlight');
  assert.equal(highlight.attributes.width, 450); // Half of the 24-hour chart, including the final target interval.
  const firstStart = Date.parse(report.origins[0].points[0].targetPeriod);
  assert.equal(highlight.attributes.x, 72 + (firstStart - Date.parse(report.protocol.testStart)) / 86400000 * 900);
  await vm.runInContext('WattlyDemo.live()', context);
  assert.match(nodes['demo-live-price'].textContent, new RegExp(price.freshness));
  price = { ...price, price: -10, freshness: 'STALE' };
  await vm.runInContext('WattlyDemo.live()', context);
  assert.match(nodes['demo-live-price'].textContent, /STALE · -10.00 SGD\/MWh · -1.00 cents\/kWh/);
  context.Wattly.api = async () => ({ ok: false });
  await vm.runInContext('WattlyDemo.live()', context);
  assert.match(nodes['demo-live-price'].textContent, /unavailable/);
  const latest = JSON.parse(fs.readFileSync('src/main/resources/static/forecast-demo-latest.json', 'utf8'));
  context.Wattly.api = async (_method, path) => ({ ok: true, data: path === '/forecast-demo-latest.json' ? latest : report });
  nodes['demo-experiment'].value = '/forecast-demo-latest.json';
  await vm.runInContext('WattlyDemo.load()', context);
  assert.equal(nodes['demo-ai-mae'].textContent, '224.41');
  assert.match(nodes['demo-model'].textContent, /HGB_MAE/);
  assert.match(nodes['demo-verdict'].textContent, /less error than B1.*already-seen/);
  assert.equal(nodes['demo-study'].hidden, false);
  assert.equal(nodes['demo-study-scores'].children.length, 11);
  assert.match(nodes['demo-study-verdict'].textContent, /MEDIAN wins validation/);
  assert.match(nodes['demo-study-coverage'].textContent, /1152 identical pairs.*72 \/ 120 training/);
  assert.equal(nodes['demo-points'].children[0].children[2].textContent, latest.origins[0].points[0].AI.toFixed(2));
  assert.equal(nodes['demo-report-download'].attributes.href, '/forecast-demo-latest.json');
  nodes['demo-experiment'].value = '/forecast-demo.json';
  await vm.runInContext('WattlyDemo.load()', context);
  assert.equal(nodes['demo-ai-mae'].textContent, report.test.table.AI.mae.toFixed(2));
  assert.equal(nodes['demo-study'].hidden, true);
  assert.match(nodes['demo-model'].textContent, /Original AI/);
});
