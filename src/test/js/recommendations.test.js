// Run with: node --test src/test/js/recommendations.test.js
// Covers the F4 dashboard card: what a suggestion shows, which request each button sends,
// and that a decision updates the card in place (CSDT4-20: "update without a page reload").
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const staticDir = path.join(__dirname, '../../main/resources/static/js');

// Just enough DOM for Wattly.escape, which uses a div's textContent -> innerHTML.
function escapingDocument() {
  return {
    querySelector() { return null; },
    createElement() {
      let text = '';
      return {
        set textContent(value) { text = value; },
        get innerHTML() { return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
      };
    }
  };
}

function load(extra) {
  const context = vm.createContext(Object.assign({ document: escapingDocument(), Intl, Date }, extra));
  vm.runInContext(fs.readFileSync(path.join(staticDir, 'wattly.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(staticDir, 'recommendations.js'), 'utf8'), context);
  return { Wattly: vm.runInContext('Wattly', context), Recs: vm.runInContext('WattlyRecommendations', context), context };
}

const dryer = {
  id: 5, applianceName: 'Tumble <Dryer>', reason: 'Start at 13:00 "cheaper"', estSavingSgd: 2.2232,
  suggestedStart: '2026-09-30T05:00:00Z', usualStart: '2026-09-30T11:45:00Z', status: 'ACTIVE'
};

test('a suggestion shows Singapore times, the saving, and escapes names', () => {
  const { Recs } = load();
  const html = Recs.itemHtml(dryer);

  assert.match(html, /Start at <strong>1:00 PM<\/strong> instead of 7:45 PM/);
  assert.match(html, /Estimated saving \$2\.22/);
  assert.match(html, /Tumble &lt;Dryer&gt;/);
  assert.doesNotMatch(html, /<Dryer>/);
  assert.match(html, /&quot;cheaper&quot;/);
  assert.match(html, /type="time" step="60" value="13:00"/);
});

test('each button sends the request the API expects', () => {
  const { Recs } = load();

  assert.deepEqual({ ...Recs.requestFor(5, 'accept', null) }, { url: '/api/v1/recommendations/5/accept', body: undefined });
  assert.deepEqual({ ...Recs.requestFor(5, 'accept-at', '15:10') }.body.startTime, '15:10');
  assert.equal(Recs.requestFor(5, 'dismiss', null).url, '/api/v1/recommendations/5/dismiss');
  assert.equal(Recs.requestFor(5, 'dismiss', null).body, undefined);
});

test('decided text says whose time was used and that nothing runs automatically', () => {
  const { Recs } = load();

  assert.match(Recs.decidedText({ ...dryer, status: 'ACCEPTED', acceptedStart: dryer.suggestedStart }),
    /^Accepted: run it at 1:00 PM\. Wattly won/);
  assert.match(Recs.decidedText({ ...dryer, status: 'ACCEPTED', acceptedStart: '2026-09-30T07:10:00Z' }),
    /3:10 PM \(your time\)/);
  assert.match(Recs.decidedText({ ...dryer, status: 'DISMISSED' }), /^Dismissed/);
});

test('clicking Accept posts the decision and updates the card without reloading', async () => {
  const calls = [];
  const toasts = [];
  let clickHandler;

  const status = { hidden: true, textContent: '', className: '' };
  let actionsRemoved = false;
  const buttons = [{ disabled: false }, { disabled: false }];
  const item = {
    dataset: { id: '5' },
    classList: { added: [], add(name) { this.added.push(name); } },
    querySelector(selector) {
      if (selector === '.rec-status') return status;
      if (selector === '.rec-actions') return { remove() { actionsRemoved = true; } };
      if (selector === '.rec-override input[type="time"]') return { value: '15:10' };
      return null;
    },
    querySelectorAll() { return buttons; }
  };
  const list = { addEventListener(type, handler) { clickHandler = handler; } };
  const document = escapingDocument();
  document.getElementById = (id) => (id === 'recommendation-list' ? list : null);

  const { Wattly, Recs } = load({ document });
  Wattly.api = async (method, url, body) => {
    calls.push({ method, url, body });
    return { ok: true, status: 200, data: { ...dryer, status: 'ACCEPTED', acceptedStart: '2026-09-30T07:10:00Z' } };
  };
  Wattly.toast = (message) => toasts.push(message);
  Recs.init();

  const button = { dataset: { action: 'accept-at' }, closest: (selector) => (selector === '.rec-item' ? item : null) };
  clickHandler({ target: { closest: () => button } });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(calls.length, 1);
  assert.equal(calls[0].method, 'POST');
  assert.equal(calls[0].url, '/api/v1/recommendations/5/accept');
  assert.equal(calls[0].body.startTime, '15:10');
  assert.equal(status.hidden, false);
  assert.match(status.textContent, /3:10 PM \(your time\)/);
  assert.ok(actionsRemoved);
  assert.deepEqual([...item.classList.added], ['decided']);
  assert.deepEqual(toasts, ['Suggestion accepted']);
});

test('a rejected override shows the server message and re-enables the buttons', async () => {
  let clickHandler;
  const status = { hidden: true, textContent: '', className: '' };
  const buttons = [{ disabled: false }];
  const item = {
    dataset: { id: '5' },
    classList: { add() {} },
    querySelector(selector) {
      if (selector === '.rec-status') return status;
      if (selector === '.rec-override input[type="time"]') return { value: '20:30' };
      return { remove() { throw new Error('actions must stay'); } };
    },
    querySelectorAll() { return buttons; }
  };
  const document = escapingDocument();
  document.getElementById = () => ({ addEventListener(type, handler) { clickHandler = handler; } });
  const { Wattly, Recs } = load({ document });
  Wattly.api = async () => ({ ok: false, status: 400,
    data: { errors: { startTime: 'Pick a time from 07:00 to 20:00 that hasn\'t passed yet' } } });
  Recs.init();

  const button = { dataset: { action: 'accept-at' }, closest: () => item };
  clickHandler({ target: { closest: () => button } });
  await new Promise((resolve) => setImmediate(resolve));

  assert.match(status.textContent, /07:00 to 20:00/);
  assert.equal(status.className, 'rec-status error');
  assert.equal(buttons[0].disabled, false);
});

test('the page only reads suggestions; it never asks the server to recompute them', async () => {
  const calls = [];
  const nodes = {};
  const element = () => ({ textContent: '', innerHTML: '', hidden: false });
  ['recommendation-list', 'recommendation-note', 'recommendation-source',
    'recommendation-count', 'recommendation-savings'].forEach((id) => { nodes[id] = element(); });
  const document = escapingDocument();
  document.getElementById = (id) => nodes[id] || null;
  const { Wattly, Recs, context } = load({ document, window: { location: { search: '?household=4' } }, URLSearchParams });
  Wattly.api = async (method, url) => {
    calls.push(method + ' ' + url);
    return { ok: true, status: 200, data: { demoForecast: true, recommendations: [dryer],
      refreshedAt: '2026-09-30T06:31:00Z' } };
  };

  await Recs.load();

  assert.deepEqual(calls, ['GET /api/v1/households/4/recommendations']);
  assert.match(nodes['recommendation-note'].textContent, /demo forecast/);
  assert.match(nodes['recommendation-note'].textContent, /Updated 2:31 PM\.$/);
  assert.equal(nodes['recommendation-count'].textContent, '1');
  assert.ok(context);
});

test('before the first background recompute there is no updated time', () => {
  const { Recs } = load();

  assert.equal(Recs.updatedText(null), '');
});

// CSDT4-38: price spike warnings with Got it / Remind me later.
const warning = {
  id: 7, kind: 'AVOID', applianceName: 'Tumble Dryer', reason: 'A price spike is forecast from 20:00',
  estSavingSgd: 3.375, usualStart: '2026-09-30T11:45:00Z', suggestedStart: '2026-09-30T05:00:00Z',
  spikeStart: '2026-09-30T12:00:00Z', status: 'ACTIVE'
};
const at = (iso) => Date.parse(iso);

test('a spike warning says which time to avoid, the alternative and the cost avoided', () => {
  const { Recs } = load();
  const html = Recs.itemHtml(warning, at('2026-09-30T00:10:00Z'));

  assert.match(html, /class="rec-item rec-warning"/);
  assert.match(html, /Price spike expected/);
  assert.match(html, /Avoid <strong>7:45 PM<\/strong>\. Run at 1:00 PM instead/);
  assert.match(html, /Estimated saving \$3\.38/);
  assert.match(html, /data-action="got-it">Got it</);
  assert.match(html, /<summary>Remind me later<\/summary>/);
  assert.doesNotMatch(html, /data-action="accept"/);
});

test('Got it and Remind me later post to their own endpoints and say what happens next', () => {
  const { Recs } = load();

  assert.deepEqual({ ...Recs.requestFor(7, 'got-it', null) }, { url: '/api/v1/recommendations/7/got-it', body: undefined });
  assert.equal(Recs.requestFor(7, 'remind-later', null).url, '/api/v1/recommendations/7/remind-later');
  assert.match(Recs.decidedText({ ...warning, status: 'ACKNOWLEDGED' }), /^Got it\. Wattly won’t warn you again/);
  assert.equal(Recs.decidedText({ ...warning, remindAt: '2026-09-30T01:10:00Z' }), 'OK, we’ll remind you at 9:10 AM.');
  assert.equal(Recs.toastFor({ status: 'ACKNOWLEDGED' }), 'Warning noted');
  assert.equal(Recs.toastFor({ status: 'ACTIVE' }), 'We’ll remind you later');
  assert.equal(Recs.toastFor({ status: 'ACCEPTED' }), 'Suggestion accepted');
});

// CSDT4-39: confidence from past prices and an "estimated" saving.
test('confidence says how often the suggested time was really cheaper, or that history is missing', () => {
  const { Recs } = load();

  assert.equal(Recs.confidenceText({ ...dryer, confidenceCheaperDays: 23, confidenceDaysChecked: 26, confidencePercent: 88 }),
    'How sure: 1:00 PM was really cheaper than 7:45 PM on 23 of 26 past days (88%).');
  assert.equal(Recs.confidenceText({ ...dryer, confidencePercent: null }),
    'How sure: not enough price history yet to check this.');
  assert.match(Recs.itemHtml({ ...dryer, confidencePercent: null }), /class="rec-confidence">How sure: not enough/);
  assert.match(Recs.itemHtml(warning, at('2026-09-30T00:10:00Z')), /class="rec-confidence">/);
});

test('the saving is labelled estimated until actual prices are known', () => {
  const { Recs } = load();

  assert.equal(Recs.savingText({ estSavingSgd: 2.2232, savingEstimated: true }), 'Estimated saving $2.22');
  assert.equal(Recs.savingText({ estSavingSgd: 2.2232 }), 'Estimated saving $2.22');
  assert.equal(Recs.savingText({ estSavingSgd: 2.2232, savingEstimated: false }), 'Saved $2.22');
});

test('an accepted card shows what it really saved once actual prices are in (CSDT4-53)', () => {
  const { Recs } = load();

  assert.equal(Recs.savingText({ estSavingSgd: 0.30, realisedSavingSgd: 0.41, savingEstimated: false }), 'Saved $0.41');
  assert.equal(Recs.savingText({ estSavingSgd: 0.30, realisedSavingSgd: -0.12, savingEstimated: false }),
    'Cost $0.12 more');
  assert.equal(Recs.savingText({ estSavingSgd: 0.30, realisedSavingSgd: 0, savingEstimated: false }), 'Saved $0.00');
  assert.equal(Recs.savingText({ estSavingSgd: 0.30, realisedSavingSgd: null }), 'Estimated saving $0.30');
});

// CSDT4-40: Remind me later until a chosen time, read-only cards for the tabs, and the mini chart.
test('Remind me later offers a time picker until 15 minutes before the suggestion runs out', () => {
  const { Recs } = load();
  const rec = { ...dryer, deadline: '2026-09-30T05:00:00Z' }; // runs out at 1:00 PM

  assert.equal(Recs.canSnooze(rec, at('2026-09-30T04:44:00Z')), true);
  assert.equal(Recs.canSnooze(rec, at('2026-09-30T04:45:00Z')), false);
  // An hour from 8:12 AM is 9:12, rounded down to 9:10; near the end it's 15 minutes before 1:00 PM.
  assert.equal(Recs.defaultSnoozeTime(rec, at('2026-09-30T00:12:00Z')), '09:10');
  assert.equal(Recs.defaultSnoozeTime(rec, at('2026-09-30T04:30:00Z')), '12:45');
  assert.match(Recs.itemHtml(rec, at('2026-09-30T00:12:00Z')),
    /<details class="rec-snooze"><summary>Remind me later<\/summary><input type="time" step="60" value="09:10"/);
  assert.doesNotMatch(Recs.itemHtml(rec, at('2026-09-30T04:50:00Z')), /rec-snooze/);
  const snooze = Recs.requestFor(5, 'snooze', '15:30');
  assert.equal(snooze.url, '/api/v1/recommendations/5/snooze');
  assert.equal(snooze.body.until, '15:30');
});

test('cards on the decided tabs say what happened and have no buttons', () => {
  const { Recs } = load();
  const nowMs = at('2026-09-30T00:12:00Z');
  const accepted = { ...dryer, status: 'ACCEPTED', acceptedStart: dryer.suggestedStart, decidedAt: '2026-09-30T06:31:00Z' };
  const html = Recs.itemHtml(accepted, { nowMs, readOnly: true });

  assert.match(html, /class="rec-item decided"/);
  assert.doesNotMatch(html, /data-action=/);
  assert.match(html, /Accepted: run it at 1:00 PM\. Wattly won’t start anything for you\. \(30 Sep, 2:31 PM\)/);
  assert.equal(Recs.statusText({ ...dryer, status: 'SUPERSEDED', decidedAt: '2026-09-30T06:31:00Z' }, nowMs),
    'Replaced by newer advice (30 Sep, 2:31 PM).');
  assert.match(Recs.statusText({ ...dryer, status: 'EXPIRED', decidedAt: '2026-09-30T06:31:00Z' }, nowMs), /^Expired/);
  assert.equal(Recs.statusText({ ...dryer, status: 'ACTIVE', remindAt: '2026-09-30T07:30:00Z' }, nowMs),
    'Snoozed: comes back at 3:30 PM.');
});

test('the mini chart marks the usual run in blue and the suggested run in teal, from real prices', () => {
  const { Recs } = load();
  // 1:00 PM to 8:30 PM Singapore time: 300 SGD/MWh, cheap 1:00-2:00 PM, 4000 spike at 8:00 PM.
  const points = [];
  for (let i = 0; i < 16; i++) {
    const start = new Date(at('2026-09-30T05:00:00Z') + i * 1800000).toISOString();
    points.push({ periodStart: start, usepSgdPerMwh: i < 2 ? 20 : i === 14 ? 4000 : 300 });
  }
  const html = Recs.chartHtml({ ...dryer, runMinutes: 60 }, points);

  assert.equal((html.match(/class="rec-bar suggested"/g) || []).length, 2); // 1:00 and 1:30 PM
  assert.equal((html.match(/class="rec-bar usual"/g) || []).length, 3);     // 7:30, 8:00, 8:30 PM (7:45-8:45)
  assert.match(html, /<title>1:00 PM: 2\.0¢\/kWh<\/title>/);
  assert.match(html, /<title>8:00 PM: 400¢\/kWh \(off the scale\)<\/title>/);
  assert.match(html, /Your usual 7:45 PM/);
  assert.match(html, /Suggested 1:00 PM/);
  assert.match(html, /role="img" aria-label="Forecast price from 1:00 PM to 8:30 PM/);
  assert.doesNotMatch(html, /tomorrow/);
  assert.equal(Recs.chartHtml(dryer, []), '<p class="rec-chart-empty">Forecast chart not available right now.</p>');
  // A forecast running past midnight says so, and a very cheap suggested bar is still visible.
  const overnight = [{ periodStart: '2026-09-30T15:00:00Z', usepSgdPerMwh: 1 },
    { periodStart: '2026-09-30T16:00:00Z', usepSgdPerMwh: 900 }];
  const night = Recs.chartHtml({ ...dryer, suggestedStart: '2026-09-30T15:00:00Z', runMinutes: 30 }, overnight);
  assert.match(night, /<span>12:00 AM tomorrow<\/span>/);
  assert.match(night, /class="rec-bar suggested" x="0" y="52\.0" width="4" height="4\.0"/);
});
