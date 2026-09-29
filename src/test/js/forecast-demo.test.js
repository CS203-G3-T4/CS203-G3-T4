const assert = require('node:assert/strict');
const { test } = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');

class Element {
  constructor() { this.children = []; this.textContent = ''; this.hidden = true; this.value = ''; }
  appendChild(child) { this.children.push(child); }
  replaceChildren() { this.children = []; }
  setAttribute() {}
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
  await vm.runInContext('WattlyDemo.live()', context);
  assert.match(nodes['demo-live-price'].textContent, new RegExp(price.freshness));
  price = { ...price, price: -10, freshness: 'STALE' };
  await vm.runInContext('WattlyDemo.live()', context);
  assert.match(nodes['demo-live-price'].textContent, /STALE · -10.00 SGD\/MWh · -1.00 cents\/kWh/);
  context.Wattly.api = async () => ({ ok: false });
  await vm.runInContext('WattlyDemo.live()', context);
  assert.match(nodes['demo-live-price'].textContent, /unavailable/);
});
