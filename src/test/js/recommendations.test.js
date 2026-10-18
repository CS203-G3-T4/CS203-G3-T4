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
  assert.match(html, /Save about \$2\.22/);
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
