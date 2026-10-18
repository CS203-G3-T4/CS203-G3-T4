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
      if (selector === 'input[type="time"]') return { value: '15:10' };
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
      if (selector === 'input[type="time"]') return { value: '20:30' };
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
  assert.match(html, /data-action="remind-later">Remind me later</);
  assert.doesNotMatch(html, /data-action="accept"/);
});

test('Remind me later is not offered once the usual start is under an hour away', () => {
  const { Recs } = load();

  assert.equal(Recs.canRemindLater(warning, at('2026-09-30T10:44:00Z')), true);
  assert.equal(Recs.canRemindLater(warning, at('2026-09-30T10:45:00Z')), false);
  assert.doesNotMatch(Recs.itemHtml(warning, at('2026-09-30T11:00:00Z')), /remind-later/);
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
