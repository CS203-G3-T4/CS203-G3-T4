// Run with: node --test src/test/js/recommendations-page.test.js
// CSDT4-40: the Recommendations page's tabs, snoozed section and mini chart data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const staticDir = path.join(__dirname, '../../main/resources/static/js');

function escapingDocument(nodes) {
  return {
    querySelector() { return null; },
    createElement() {
      let text = '';
      return {
        set textContent(value) { text = value; },
        get innerHTML() { return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
      };
    },
    getElementById(id) { return nodes ? nodes[id] || null : null; }
  };
}

function load(nodes) {
  const context = vm.createContext({ document: escapingDocument(nodes), Intl, Date,
    window: { location: { search: '?household=4', hash: '' } }, URLSearchParams });
  ['wattly.js', 'recommendations.js', 'recommendations-page.js'].forEach((file) =>
    vm.runInContext(fs.readFileSync(path.join(staticDir, file), 'utf8'), context));
  return { Wattly: vm.runInContext('Wattly', context), Page: vm.runInContext('WattlyRecommendationsPage', context) };
}

const soon = (minutes) => new Date(Date.now() + minutes * 60000).toISOString();
const rec = (id, extra) => ({ id, kind: 'SHIFT_START', applianceName: 'Dryer', reason: 'r', estSavingSgd: 1,
  suggestedStart: soon(300), usualStart: soon(600), deadline: soon(300), status: 'ACTIVE', runMinutes: 60,
  forecastSource: 'DEMO_FORECAST', ...extra });

test('each tab asks the API for its own status', () => {
  const { Page } = load();

  assert.equal(Page.tabUrl(4, 'ACCEPTED'), '/api/v1/households/4/recommendations?status=ACCEPTED');
  assert.deepEqual([...Page.TABS], ['ACTIVE', 'ACCEPTED', 'DISMISSED', 'HISTORY']);
});

test('the Active tab separates snoozed suggestions until their time comes', () => {
  const { Page } = load();
  const nowMs = Date.parse('2026-09-30T00:10:00Z');
  const split = Page.splitActive([rec(1), rec(2, { remindAt: '2026-09-30T01:00:00Z' }),
    rec(3, { remindAt: '2026-09-30T00:00:00Z' })], nowMs);

  assert.deepEqual(split.open.map((r) => r.id), [1, 3]);
  assert.deepEqual(split.snoozed.map((r) => r.id), [2]);
});

test('decided tabs say how many there are, or that there are none', () => {
  const { Page } = load();

  assert.equal(Page.tabNote('ACCEPTED', 0), 'Nothing accepted in the last 30 days.');
  assert.equal(Page.tabNote('DISMISSED', 2), '2 dismissed in the last 30 days, newest first.');
  assert.equal(Page.tabNote('HISTORY', 1), '1 suggestion in the last 30 days, newest first.');
});

test('the chart uses the demo forecast for demo suggestions and F3s latest forecast for F3 ones', async () => {
  const { Wattly, Page } = load();
  const calls = [];
  Wattly.api = async (method, url) => {
    calls.push(url);
    if (url === '/api/v1/recommendations/demo-forecast') {
      return { ok: true, data: { points: [{ periodStart: 'a', usepSgdPerMwh: 100 }] } };
    }
    return { ok: true, data: { run: { points: [
      { targetPeriod: 'b', predictedUsep: 250 }, { targetPeriod: 'c', predictedUsep: null }] } } };
  };

  const demoOnly = await Page.forecastPoints([rec(1)]);
  assert.deepEqual(calls, ['/api/v1/recommendations/demo-forecast']);
  assert.equal(demoOnly.DEMO_FORECAST[0].usepSgdPerMwh, 100);

  calls.length = 0;
  const f3 = await Page.forecastPoints([rec(1, { forecastSource: 'F3_FORECAST' })]);
  assert.deepEqual(calls, ['/api/v1/forecast/latest']);
  assert.equal(f3.F3_FORECAST.length, 1);
  assert.equal(f3.F3_FORECAST[0].periodStart, 'b');
  assert.equal(f3.F3_FORECAST[0].usepSgdPerMwh, 250);

  calls.length = 0;
  await Page.forecastPoints([]);
  assert.deepEqual(calls, []);
});

test('loading the Active tab shows open cards with charts and the snoozed ones read-only', async () => {
  const element = () => ({ textContent: '', innerHTML: '', hidden: false, attrs: {},
    setAttribute(name, value) { this.attrs[name] = value; }, tabIndex: 0 });
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source', 'recommendation-count',
    'recommendation-savings', 'snoozed-block', 'recommendation-snoozed', 'rec-tabpanel', 'realised-block',
    'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach((id) => { nodes[id] = element(); });
  const { Wattly, Page } = load(nodes);
  const points = [];
  for (let i = 0; i < 24; i++) points.push({ periodStart: soon(30 * i), usepSgdPerMwh: 300 });
  Wattly.api = async (method, url) => url.includes('demo-forecast')
    ? { ok: true, data: { points } }
    : { ok: true, data: { demoForecast: true, recommendations: [rec(1), rec(2, { remindAt: soon(60) })] } };

  await Page.loadTab('ACTIVE');

  assert.match(nodes['recommendation-list'].innerHTML, /data-id="1"/);
  assert.match(nodes['recommendation-list'].innerHTML, /<figure class="rec-chart">/);
  assert.match(nodes['recommendation-list'].innerHTML, /data-action="accept"/);
  assert.doesNotMatch(nodes['recommendation-list'].innerHTML, /data-id="2"/);
  assert.equal(nodes['snoozed-block'].hidden, false);
  assert.match(nodes['recommendation-snoozed'].innerHTML, /Snoozed: comes back at/);
  assert.equal(nodes['recommendation-count'].textContent, '1');
  assert.equal(nodes['tab-active'].attrs['aria-selected'], 'true');
  assert.equal(nodes['tab-history'].attrs['aria-selected'], 'false');
});

test('a decided tab shows read-only cards without charts or buttons', async () => {
  const element = () => ({ textContent: '', innerHTML: '', hidden: false, setAttribute() {}, tabIndex: 0 });
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source', 'snoozed-block', 'rec-tabpanel',
    'realised-block', 'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history']
    .forEach((id) => { nodes[id] = element(); });
  const { Wattly, Page } = load(nodes);
  const urls = [];
  Wattly.api = async (method, url) => {
    urls.push(url);
    return { ok: true, data: { recommendations: [rec(5, { status: 'DISMISSED', decidedAt: '2026-09-30T06:31:00Z' })] } };
  };

  await Page.loadTab('DISMISSED');

  assert.deepEqual(urls, ['/api/v1/households/4/recommendations?status=DISMISSED']);
  assert.match(nodes['recommendation-list'].innerHTML, /Dismissed\. You won’t see this suggestion again/);
  assert.doesNotMatch(nodes['recommendation-list'].innerHTML, /data-action=|rec-chart/);
  assert.equal(nodes['recommendation-note'].textContent, '1 dismissed in the last 30 days, newest first.');
  assert.equal(nodes['snoozed-block'].hidden, true);
  assert.equal(nodes['realised-block'].hidden, true);
});

// CSDT4-53: realised savings on the Accepted tab.
test('the Accepted tab shows this week and this month at actual prices, and says accepted counts as followed',
  async () => {
    const element = () => ({ textContent: '', innerHTML: '', hidden: true, setAttribute() {}, tabIndex: 0 });
    const nodes = {};
    ['recommendation-list', 'recommendation-note', 'recommendation-source', 'snoozed-block', 'rec-tabpanel',
      'realised-block', 'saved-week', 'saved-month', 'realised-note',
      'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach((id) => { nodes[id] = element(); });
    const { Wattly, Page } = load(nodes);
    const followed = 'We count an accepted suggestion as followed: we assume you ran the appliance at the time you chose '
      + 'instead of your usual time.';
    const urls = [];
    Wattly.api = async (method, url) => {
      urls.push(url);
      if (url.endsWith('period=week')) {
        return { ok: true, data: { totalSavedSgd: 0.41, pendingCount: 1, notes: [followed, 'At actual prices.'] } };
      }
      if (url.endsWith('period=month')) {
        return { ok: true, data: { totalSavedSgd: 1.5, pendingCount: 2, notes: [followed, 'At actual prices.'] } };
      }
      return { ok: true, data: { recommendations: [rec(5, { status: 'ACCEPTED', acceptedStart: soon(-600),
        suggestedStart: soon(-600), decidedAt: soon(-900), realisedSavingSgd: 0.41, savingEstimated: false })] } };
    };

    await Page.loadTab('ACCEPTED');
    await new Promise((resolve) => setTimeout(resolve, 0));

    assert.ok(urls.includes('/api/v1/households/4/savings?period=week'));
    assert.ok(urls.includes('/api/v1/households/4/savings?period=month'));
    assert.equal(nodes['realised-block'].hidden, false);
    assert.equal(nodes['saved-week'].textContent, '$0.41');
    assert.equal(nodes['saved-month'].textContent, '$1.50');
    assert.equal(nodes['realised-note'].textContent,
      followed + ' At actual prices. 2 accepted runs this month are not counted yet.');
    assert.match(nodes['recommendation-list'].innerHTML, /class="rec-saving">Saved \$0\.41</);
  });

test('realised totals can be negative, and a failed load says so instead of showing $0', async () => {
  const { Page } = load();
  assert.equal(Page.savedText(-0.12), '-$0.12');
  assert.equal(Page.savedText('0'), '$0.00');
  assert.equal(Page.savingsUrl(4, 'month'), '/api/v1/households/4/savings?period=month');
  assert.equal(Page.realisedNote({ notes: ['a'] }, { notes: ['a', 'b'], pendingCount: 1 }),
    'a b 1 accepted run this month is not counted yet.');

  const element = () => ({ textContent: '', innerHTML: '', hidden: true, setAttribute() {}, tabIndex: 0 });
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source', 'snoozed-block', 'rec-tabpanel',
    'realised-block', 'saved-week', 'saved-month', 'realised-note',
    'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach((id) => { nodes[id] = element(); });
  const page = load(nodes);
  page.Wattly.api = async (method, url) => url.includes('/savings')
    ? { ok: false, data: { detail: 'You do not have access to this household.' } }
    : { ok: true, data: { recommendations: [] } };

  await page.Page.loadTab('ACCEPTED');
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(nodes['saved-week'].textContent, '—');
  assert.equal(nodes['realised-note'].textContent, 'You do not have access to this household.');
});
