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
  return { context, Wattly: vm.runInContext('Wattly', context), Page: vm.runInContext('WattlyRecommendationsPage', context) };
}

const soon = (minutes) => new Date(Date.now() + minutes * 60000).toISOString();
const rec = (id, extra) => ({ id, kind: 'SHIFT_START', applianceName: 'Dryer', reason: 'r', estSavingSgd: 1,
  suggestedStart: soon(300), usualStart: soon(600), deadline: soon(300), status: 'ACTIVE', runMinutes: 60,
  forecastSource: 'DEMO_FORECAST', ...extra });

test('automatic refresh starts once, every minute and when returning to the page', async () => {
  const { context, Page } = load();
  const intervals = [], listeners = [], tabs = [];
  context.window.setInterval = (callback, milliseconds) => intervals.push({ callback, milliseconds });
  context.document.addEventListener = (event, callback) => listeners.push({ event, callback });
  context.document.querySelectorAll = () => [];
  Page.loadTab = async (tab) => tabs.push(tab);
  Page.startAutoRefresh();
  Page.startAutoRefresh();
  assert.equal(intervals.length, 1);
  assert.equal(intervals[0].milliseconds, 60000);
  assert.equal(listeners[0].event, 'visibilitychange');
  await intervals[0].callback();
  await listeners[0].callback();
  assert.deepEqual(tabs, ['ACTIVE', 'ACTIVE']);
});

test('automatic refresh preserves editing and pending decisions and avoids overlapping requests', async () => {
  const { context, Page } = load();
  const doc = context.document;
  doc.querySelectorAll = () => [];
  let calls = 0, release;
  Page.loadTab = () => { calls++; return new Promise((resolve) => { release = resolve; }); };
  doc.hidden = true;
  await Page.refreshAutomatically();
  doc.hidden = false;
  doc.activeElement = { matches: () => true };
  await Page.refreshAutomatically();
  doc.activeElement = null;
  doc.querySelector = () => ({ disabled: true });
  await Page.refreshAutomatically();
  doc.querySelector = () => null;
  doc.querySelectorAll = () => [{ value: '12:30', defaultValue: '13:00' }];
  await Page.refreshAutomatically();
  assert.equal(calls, 0);
  doc.querySelectorAll = () => [];
  const pending = Page.refreshAutomatically();
  await Page.refreshAutomatically();
  assert.equal(calls, 1);
  release(); await pending;
});

test('each tab asks the API for its own status', () => {
  const { Page } = load();

  assert.equal(Page.tabUrl(4, 'ACCEPTED'), '/api/v1/households/4/recommendations?status=ACCEPTED');
  assert.deepEqual([...Page.TABS], ['ACTIVE', 'ACCEPTED', 'DISMISSED', 'HISTORY']);
});

for (const stage of ['list', 'chart']) {
  for (const busy of ['focused input', 'unsaved time', 'pending decision']) {
    test('automatic refresh preserves ' + busy + ' begun during the ' + stage + ' request', async () => {
      const nodes = {};
      ['recommendation-list', 'recommendation-note', 'recommendation-source',
        'snoozed-block', 'recommendation-snoozed', 'rec-tabpanel', 'realised-block',
        'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach(id => {
          nodes[id] = { textContent: '', innerHTML: '', hidden: false, setAttribute() {} };
        });
      const { context, Wattly, Page } = load(nodes);
      context.document.querySelectorAll = () => [];
      nodes['recommendation-list'].innerHTML = '<div>Original card and custom start</div>';
      nodes['recommendation-note'].textContent = 'Original note';
      const response = { ok: true, data: { recommendations: [], message: 'New results' } };
      let release;
      const deferred = new Promise(resolve => { release = resolve; });
      Wattly.api = stage === 'list' ? () => deferred : async () => response;
      Page.forecastPoints = stage === 'chart' ? () => deferred : async () => ({});
      Page.loadAlerts = async () => {};

      const pending = Page.refreshAutomatically();
      await Promise.resolve();
      if (busy === 'focused input') context.document.activeElement = { matches: () => true };
      if (busy === 'unsaved time') context.document.querySelectorAll = () => [{ value: '12:30', defaultValue: '13:00' }];
      if (busy === 'pending decision') context.document.querySelector = () => ({ disabled: true });
      release(stage === 'list' ? response : {});
      await pending;
      assert.equal(nodes['recommendation-list'].innerHTML, '<div>Original card and custom start</div>');
      assert.equal(nodes['recommendation-note'].textContent, 'Original note');

      // Deferring a render must not permanently stop subsequent idle refreshes.
      context.document.activeElement = null;
      context.document.querySelector = () => null;
      context.document.querySelectorAll = () => [];
      Wattly.api = async () => response;
      Page.forecastPoints = async () => ({});
      await Page.refreshAutomatically();
      assert.equal(nodes['recommendation-list'].innerHTML, '');
      assert.match(nodes['recommendation-note'].textContent, /New results/);
    });
  }
}

test('an automatic error response cannot erase an edit begun while waiting', async () => {
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source', 'snoozed-block', 'rec-tabpanel',
    'realised-block', 'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach(id => {
      nodes[id] = { textContent: '', innerHTML: '', hidden: false, setAttribute() {} };
    });
  const { context, Wattly, Page } = load(nodes);
  context.document.querySelectorAll = () => [];
  nodes['recommendation-list'].innerHTML = 'Unsaved custom start';
  let release;
  Wattly.api = () => new Promise(resolve => { release = resolve; });
  const pending = Page.refreshAutomatically();
  context.document.activeElement = { matches: () => true };
  release({ ok: false, data: { detail: 'Temporary error' } });
  await pending;
  assert.equal(nodes['recommendation-list'].innerHTML, 'Unsaved custom start');
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
      return { ok: true, data: { replayDay: '2026-09-26', points: [{ periodStart: 'a', usepSgdPerMwh: 100 }] } };
    }
    return { ok: true, data: { run: { asOf: '2026-09-30T06:05:00Z', points: [
      { targetPeriod: 'b', predictedUsep: 250 }, { targetPeriod: 'c', predictedUsep: null }] } } };
  };

  const demoOnly = await Page.forecastPoints([rec(1)]);
  assert.deepEqual(calls, ['/api/v1/recommendations/demo-forecast']);
  assert.deepEqual([...Object.keys(demoOnly)], ['DEMO_FORECAST']);
  assert.equal(demoOnly.DEMO_FORECAST.points[0].usepSgdPerMwh, 100);
  assert.equal(demoOnly.DEMO_FORECAST.label, 'Demo forecast: a replay of 26 Sep prices, not a live prediction.');

  calls.length = 0;
  const f3 = await Page.forecastPoints([rec(1, { forecastSource: 'F3_FORECAST' })]);
  assert.deepEqual(calls, ['/api/v1/forecast/latest']);
  assert.deepEqual([...Object.keys(f3)], ['F3_FORECAST']);
  assert.equal(f3.F3_FORECAST.points.length, 1);
  assert.equal(f3.F3_FORECAST.points[0].periodStart, 'b');
  assert.equal(f3.F3_FORECAST.points[0].usepSgdPerMwh, 250);
  assert.equal(f3.F3_FORECAST.label, 'AI price forecast (F3), made 2:05 PM.');

  calls.length = 0;
  const both = await Page.forecastPoints([rec(1), rec(2, { forecastSource: 'F3_FORECAST' })]);
  assert.deepEqual([...Object.keys(both)].sort(), ['DEMO_FORECAST', 'F3_FORECAST']);

  calls.length = 0;
  assert.deepEqual({ ...await Page.forecastPoints([]) }, {});
  assert.deepEqual(calls, []);
});

test('a failed forecast request gives no points and no label', async () => {
  const { Wattly, Page } = load();
  Wattly.api = async () => ({ ok: false, data: null });

  const failed = await Page.forecastPoints([rec(1), rec(2, { forecastSource: 'F3_FORECAST' })]);
  assert.equal(failed.DEMO_FORECAST.points.length, 0);
  assert.equal(failed.DEMO_FORECAST.label, undefined);
  assert.equal(failed.F3_FORECAST.points.length, 0);
  assert.equal(failed.F3_FORECAST.label, undefined);

  Wattly.api = async () => ({ ok: true, data: { run: null } });
  const noRun = await Page.forecastPoints([rec(1, { forecastSource: 'F3_FORECAST' })]);
  assert.equal(noRun.F3_FORECAST.points.length, 0);
  assert.equal(noRun.F3_FORECAST.label, undefined);
});

test('the chart label says whether the forecast is a demo replay or the AI forecast, and when it was made', () => {
  const { Page } = load();

  assert.equal(Page.forecastLabel('DEMO_FORECAST', { replayDay: '2026-09-26' }),
    'Demo forecast: a replay of 26 Sep prices, not a live prediction.');
  assert.equal(Page.forecastLabel('DEMO_FORECAST', { replayDay: '2026-10-05' }),
    'Demo forecast: a replay of 5 Oct prices, not a live prediction.');
  assert.equal(Page.forecastLabel('DEMO_FORECAST', {}),
    'Demo forecast: a replay of past prices, not a live prediction.');
  assert.equal(Page.forecastLabel('DEMO_FORECAST', undefined),
    'Demo forecast: a replay of past prices, not a live prediction.');

  const replay = 'Demo replay: past prices, not a live prediction.';
  assert.equal(Page.forecastLabel('F3_FORECAST', { run: { mode: 'REPLAY', asOf: '2026-09-30T06:05:00Z' } }), replay);
  assert.equal(Page.forecastLabel('F3_FORECAST', { run: { modelType: 'DEMO', asOf: '2026-09-30T06:05:00Z' } }), replay);

  assert.equal(Page.forecastLabel('F3_FORECAST', { run: { mode: 'LIVE', modelType: 'LSTM', asOf: '2026-09-30T06:05:00Z' } }),
    'AI price forecast (F3), made 2:05 PM.');
  // asOf wins; generatedAt is the fallback.
  assert.equal(Page.forecastLabel('F3_FORECAST',
    { run: { asOf: '2026-09-30T06:05:00Z', generatedAt: '2026-09-30T08:00:00Z' } }), 'AI price forecast (F3), made 2:05 PM.');
  assert.equal(Page.forecastLabel('F3_FORECAST', { run: { generatedAt: '2026-09-30T08:00:00Z' } }),
    'AI price forecast (F3), made 4:00 PM.');
  assert.equal(Page.forecastLabel('F3_FORECAST', { stale: true, run: { asOf: '2026-09-30T06:05:00Z' } }),
    'AI price forecast (F3), made 2:05 PM. Out of date.');
  assert.equal(Page.forecastLabel('F3_FORECAST', { run: {} }), 'AI price forecast (F3).');
  assert.equal(Page.forecastLabel('F3_FORECAST', { stale: true, run: {} }), 'AI price forecast (F3). Out of date.');
});

test('loading the Active tab shows open cards with charts and the snoozed ones read-only', async () => {
  const element = () => ({ textContent: '', innerHTML: '', hidden: false, attrs: {},
    setAttribute(name, value) { this.attrs[name] = value; }, tabIndex: 0 });
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source',
    'snoozed-block', 'recommendation-snoozed', 'rec-tabpanel', 'realised-block',
    'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach((id) => { nodes[id] = element(); });
  const { Wattly, Page } = load(nodes);
  const points = [];
  for (let i = 0; i < 24; i++) points.push({ periodStart: soon(30 * i), usepSgdPerMwh: 300 });
  Wattly.api = async (method, url) => url.includes('demo-forecast')
    ? { ok: true, data: { replayDay: '2026-09-26', points } }
    : { ok: true, data: { demoForecast: true, recommendations: [rec(1), rec(2, { remindAt: soon(60) })] } };

  await Page.loadTab('ACTIVE');

  assert.match(nodes['recommendation-list'].innerHTML, /data-id="1"/);
  assert.match(nodes['recommendation-list'].innerHTML, /<figure class="rec-chart">/);
  assert.match(nodes['recommendation-list'].innerHTML,
    /<div class="rec-chart-source">Demo forecast: a replay of 26 Sep prices, not a live prediction\.<\/div>/);
  assert.match(nodes['recommendation-list'].innerHTML, /data-action="accept"/);
  assert.doesNotMatch(nodes['recommendation-list'].innerHTML, /data-id="2"/);
  assert.equal(nodes['snoozed-block'].hidden, false);
  assert.match(nodes['recommendation-snoozed'].innerHTML, /Snoozed: comes back at/);
  assert.equal(nodes['tab-active'].attrs['aria-selected'], 'true');
  assert.equal(nodes['tab-history'].attrs['aria-selected'], 'false');
});

test('an older refresh cannot overwrite newer advice on the same tab', async () => {
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source',
    'snoozed-block', 'recommendation-snoozed', 'rec-tabpanel', 'realised-block',
    'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach((id) => {
      nodes[id] = { textContent: '', innerHTML: '', hidden: false, setAttribute() {} };
    });
  const { Wattly, Page } = load(nodes);
  let resolveOlder;
  Wattly.api = () => new Promise((resolve) => { resolveOlder = resolve; });
  const older = Page.loadTab('ACTIVE');
  Wattly.api = async () => ({ ok: true, data: { recommendations: [], message: 'Newer forecast advice' } });
  await Page.loadTab('ACTIVE');
  resolveOlder({ ok: true, data: { recommendations: [], message: 'Older forecast advice' } });
  await older;
  assert.match(nodes['recommendation-note'].textContent, /Newer forecast advice/);
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

// The header shows this week's estimated and actual savings from accepted suggestions; both only go up.
test('the header cards show this week\'s estimated and actual savings', () => {
  const { Page } = load();

  const totals = Page.weekTotals({ totalSavedSgd: 0.49, items: [
    { estimatedSavingSgd: 0.45, state: 'REALISED' }, { estimatedSavingSgd: 0.12, state: 'PENDING' }] });

  assert.equal(totals.estimated, '$0.57');
  assert.equal(totals.actual, '$0.49');
  assert.deepEqual({ ...Page.weekTotals({ totalSavedSgd: 0, items: [] }) }, { estimated: '$0.00', actual: '$0.00' });
});

test('accepting a suggestion reloads the weekly totals so the estimate goes up', async () => {
  const element = () => ({ textContent: '', innerHTML: '', hidden: false, setAttribute() {}, addEventListener() {},
    focus() {}, click() {}, tabIndex: 0 });
  const nodes = {};
  ['recommendation-list', 'recommendation-note', 'recommendation-source', 'snoozed-block', 'recommendation-snoozed',
    'rec-tabpanel', 'realised-block', 'refresh-recommendations', 'estimated-week', 'actual-week', 'page-banner',
    'tab-active', 'tab-accepted', 'tab-dismissed', 'tab-history'].forEach((id) => { nodes[id] = element(); });
  const { Wattly, Page, context } = load(nodes);
  nodes['rec-tabs'] = element(); // added after loading, so the page doesn't start itself before the stubs
  context.window.addEventListener = () => {};
  context.window.setInterval = () => 0;
  context.document.addEventListener = () => {};
  context.history = { replaceState() {} };
  Wattly.keepHouseholdInLinks = () => {};
  Wattly.loadHouseholdSwitcher = () => {};
  Wattly.renderHouseholdCard = () => {};
  let estimate = 0.12;
  const savingsCalls = [];
  Wattly.api = async (method, url) => {
    if (url.includes('/savings')) {
      savingsCalls.push(url);
      return { ok: true, data: { totalSavedSgd: 0, items: estimate ? [{ estimatedSavingSgd: estimate }] : [] } };
    }
    return { ok: true, data: { recommendations: [] } };
  };

  Page.start();
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(nodes['estimated-week'].textContent, '$0.12');
  assert.equal(nodes['actual-week'].textContent, '$0.00');

  estimate = 0.54;
  vm.runInContext('WattlyRecommendations.onDecided({ status: "DISMISSED" })', context);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(nodes['estimated-week'].textContent, '$0.12', 'dismissing does not change the totals');
  vm.runInContext('WattlyRecommendations.onDecided({ status: "ACCEPTED" })', context);
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(nodes['estimated-week'].textContent, '$0.54');
  assert.ok(savingsCalls.every((url) => url === '/api/v1/households/4/savings?period=week'));
});

test('if the weekly savings can\'t load, the cards show a dash instead of $0', async () => {
  const nodes = { 'estimated-week': { textContent: '' }, 'actual-week': { textContent: '' } };
  const { Wattly, Page } = load(nodes);
  Wattly.api = async () => ({ ok: false, status: 500, data: null });

  await Page.loadWeekTotals();

  assert.equal(nodes['estimated-week'].textContent, '—');
  assert.equal(nodes['actual-week'].textContent, '—');
});
