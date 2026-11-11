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
  ['recommendation-list', 'recommendation-note', 'recommendation-source'].forEach((id) => { nodes[id] = element(); });
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
  assert.match(nodes['recommendation-list'].innerHTML, /data-id="/);
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
    'Past-day pattern: 1:00 PM was really cheaper than 7:45 PM on 23 of 26 past days (88%).');
  assert.equal(Recs.confidenceText({ ...dryer, confidencePercent: null }),
    'Past-day pattern: not enough price history yet to check this.');
  assert.match(Recs.itemHtml({ ...dryer, confidencePercent: null }), /class="rec-confidence">Past-day pattern: not enough/);
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

// Forecast chart helpers: half-hourly points from a fixed instant, with a price per index.
function forecast(startIso, count, price) {
  const points = [];
  for (let i = 0; i < count; i++) {
    points.push({ periodStart: new Date(at(startIso) + i * 1800000).toISOString(), usepSgdPerMwh: price(i) });
  }
  return points;
}
const xLabelsOf = (html) => [...html.match(/<div class="rec-chart-x[^"]*"[^>]*>(.*?)<\/div>/)[1]
  .matchAll(/<span(?: class="([^"]*)")? style="left:[^"]*">([^<]*)<\/span>/g)].map((m) => ({ cls: m[1] || '', text: m[2] }));
const yLabelsOf = (html) => [...html.match(/<div class="rec-chart-y"[^>]*>(.*?)<\/div>/)[1]
  .matchAll(/<span style="top:[^"]*">([^<]*)<\/span>/g)].map((m) => m[1]);
const tooltipsOf = (html) => [...html.matchAll(/<title>([^<]*)<\/title>/g)].map((m) => m[1]);
const bandOf = (html, kind) => {
  const m = html.match(new RegExp('<rect class="rec-band ' + kind + '" x="([\\d.]+)" y="0" width="([\\d.]+)"'));
  return m && { x: Number(m[1]), width: Number(m[2]) };
};
const EMPTY_CHART = '<p class="rec-chart-empty">Forecast chart not available right now.</p>';

test('shortTime gives Singapore chart labels like 2pm, 1:30pm and 12am', () => {
  const { Recs } = load();

  assert.equal(Recs.shortTime(at('2026-09-30T06:00:00Z')), '2pm');
  assert.equal(Recs.shortTime(at('2026-09-30T05:30:00Z')), '1:30pm');
  assert.equal(Recs.shortTime(at('2026-09-30T16:00:00Z')), '12am');
  assert.equal(Recs.shortTime(at('2026-09-30T04:00:00Z')), '12pm');
  assert.equal(Recs.shortTime(at('2026-09-30T11:45:00Z')), '7:45pm');
  assert.equal(Recs.shortTime(at('2026-09-30T16:15:00Z')), '12:15am');
  assert.equal(Recs.shortTime(at('2026-09-30T01:00:00Z')), '9am');
});

// 1:00 PM to 9:00 PM Singapore time: 300 SGD/MWh (30¢), cheap 1:00-2:00 PM (2¢), 4000 spike at 8:00 PM.
const afternoon = () => forecast('2026-09-30T05:00:00Z', 16, (i) => (i < 2 ? 20 : i === 14 ? 4000 : 300));
const dryerRun = { ...dryer, runMinutes: 60 };

test('the chart is a line with a dot per half-hour and hour labels along the bottom', () => {
  const { Recs } = load();
  const html = Recs.chartHtml(dryerRun, afternoon());

  assert.match(html, /<svg viewBox="0 0 1000 100" preserveAspectRatio="none" role="img"/);
  assert.equal((html.match(/<path class="rec-line"/g) || []).length, 1);
  assert.equal((html.match(/class="rec-dot/g) || []).length, 16);
  assert.doesNotMatch(html, /rec-bar/);
  assert.deepEqual(xLabelsOf(html).map((l) => l.text), ['1pm', '2pm', '3pm', '4pm', '5pm', '6pm', '7pm', '8pm', '9pm']);
  // On a narrow chart (a phone) only every third hour shows: 3pm, 6pm and 9pm; the rest are minor.
  assert.deepEqual(xLabelsOf(html).map((l) => l.cls),
    ['start minor', 'minor', '', 'minor', 'minor', '', 'minor', 'minor', 'end']);
  assert.equal((html.match(/class="rec-chart-grid hour"/g) || []).length, 9);
  assert.doesNotMatch(html, /tomorrow/);
  assert.doesNotMatch(html, /rec-chart-source/);
});

test('the suggested and usual runs are shaded from their exact start for the length of the run', () => {
  const { Recs } = load();
  const html = Recs.chartHtml(dryerRun, afternoon());

  // 8 hours across 1000 units: 125 per hour. Suggested 1pm-2pm starts at 0; usual 7:45pm is 6.75 hours in.
  assert.deepEqual(bandOf(html, 'suggested'), { x: 0, width: 125 });
  assert.deepEqual(bandOf(html, 'usual'), { x: 843.8, width: 125 });
  assert.equal((html.match(/class="rec-band /g) || []).length, 2);
  // A 30-minute run is half as wide.
  assert.deepEqual(bandOf(Recs.chartHtml({ ...dryer, runMinutes: 30 }, afternoon()), 'suggested'), { x: 0, width: 62.5 });
});

test('hovering a half-hour says its time range and price, and marks the spike as off the scale', () => {
  const { Recs } = load();
  const html = Recs.chartHtml(dryerRun, afternoon());
  const tips = tooltipsOf(html);

  assert.equal(tips.length, 16);
  assert.equal(tips[0], '1pm–1:30pm: 2¢/kWh');
  assert.equal(tips[2], '2pm–2:30pm: 30¢/kWh');
  assert.equal(tips[14], '8pm–8:30pm: 400¢/kWh (off the scale)');
  assert.equal(tips.filter((t) => t.includes('off the scale')).length, 1);
  assert.equal((html.match(/class="rec-dot off-scale"/g) || []).length, 1);
  assert.equal((html.match(/class="rec-dot"/g) || []).length, 15);
  assert.match(html, /spikes above 40¢ marked at the top/);
  assert.deepEqual(yLabelsOf(html), ['0¢', '10¢', '20¢', '30¢', '40¢']);
});

test('the legend and screen-reader summary name both runs and the forecast range', () => {
  const { Recs } = load();
  const html = Recs.chartHtml(dryerRun, afternoon());

  assert.match(html, /<span><i class="rec-swatch suggested"><\/i>Suggested 1pm–2pm<\/span>/);
  assert.match(html, /<span><i class="rec-swatch usual"><\/i>Your usual 7:45pm–8:45pm<\/span>/);
  assert.match(html, /aria-label="Forecast price from 1pm to 9pm, 2¢ to 400¢\/kWh\. Suggested 1pm–2pm shaded teal, your usual 7:45pm–8:45pm shaded blue\."/);
  assert.doesNotMatch(html, /outside this forecast/);
});

test('an AVOID warning says run at and avoid instead of suggested and your usual', () => {
  const { Recs } = load();
  const html = Recs.chartHtml({ ...dryerRun, kind: 'AVOID' }, afternoon());

  assert.match(html, /<i class="rec-swatch suggested"><\/i>Run at 1pm–2pm<\/span>/);
  assert.match(html, /<i class="rec-swatch usual"><\/i>Avoid 7:45pm–8:45pm<\/span>/);
  assert.doesNotMatch(html, /Your usual|Suggested/);
  assert.match(html, /Run at 1pm–2pm shaded teal, avoid 7:45pm–8:45pm shaded blue/);
});

test('the chart says which forecast it shows, escaping the label, and omits the caption without one', () => {
  const { Recs } = load();
  const labelled = Recs.chartHtml(dryerRun, afternoon(), 'Demo <b>"replay"</b> & more');

  assert.match(labelled, /<div class="rec-chart-source">Demo &lt;b&gt;&quot;replay&quot;&lt;\/b&gt; &amp; more<\/div>/);
  assert.doesNotMatch(labelled, /<b>/);
  assert.match(Recs.chartHtml(dryerRun, afternoon(), 'AI price forecast (F3), made 2:05 PM.'),
    /<div class="rec-chart-source">AI price forecast \(F3\), made 2:05 PM\.<\/div>/);
  assert.doesNotMatch(Recs.chartHtml(dryerRun, afternoon(), ''), /rec-chart-source/);
  assert.doesNotMatch(Recs.chartHtml(dryerRun, afternoon(), undefined), /rec-chart-source/);
});

test('a forecast running past Singapore midnight says tomorrow and labels 12am', () => {
  const { Recs } = load();
  // 10:00 PM to 2:00 AM.
  const night = forecast('2026-09-30T14:00:00Z', 8, () => 300);
  const html = Recs.chartHtml({ ...dryer, suggestedStart: '2026-09-30T16:00:00Z', usualStart: '2026-09-30T14:00:00Z', runMinutes: 30 }, night);

  assert.deepEqual(xLabelsOf(html).map((l) => l.text), ['10pm', '11pm', '12am', '1am', '2am']);
  assert.match(html, /Forecast price from 10pm to 2am tomorrow, 30¢ to 30¢\/kWh\./);
  // The same forecast within one day has no "tomorrow".
  assert.doesNotMatch(Recs.chartHtml(dryerRun, afternoon()), /tomorrow/);
});

test('a 48-point forecast labels every second hour so the axis fits on a phone', () => {
  const { Recs } = load();
  // 12:00 AM to 12:00 AM next day, in 48 half-hours.
  const day = forecast('2026-09-29T16:00:00Z', 48, (i) => 200 + i);
  const html = Recs.chartHtml({ ...dryer, suggestedStart: '2026-09-29T20:00:00Z', usualStart: '2026-09-30T10:00:00Z', runMinutes: 60 }, day);
  const labels = xLabelsOf(html).map((l) => l.text);

  assert.deepEqual(labels, ['12am', '2am', '4am', '6am', '8am', '10am', '12pm', '2pm', '4pm', '6pm', '8pm', '10pm', '12am']);
  assert.equal(xLabelsOf(html)[0].cls, 'start');
  assert.equal(xLabelsOf(html)[12].cls, 'end');
  assert.match(html, /<div class="rec-chart-x two-hourly" aria-hidden="true">/);
  // A narrow chart keeps every 6 hours: 12am, 6am, 12pm, 6pm, 12am.
  assert.deepEqual(xLabelsOf(html).filter((l) => !l.cls.includes('minor')).map((l) => l.text),
    ['12am', '6am', '12pm', '6pm', '12am']);
  assert.equal((html.match(/class="rec-chart-grid hour"/g) || []).length, 25); // gridlines stay hourly
  assert.equal((html.match(/class="rec-dot/g) || []).length, 48);
  assert.match(html, /Forecast price from 12am to 12am tomorrow/);
  assert.ok(bandOf(html, 'suggested') && bandOf(html, 'usual'));
});

test('a missing half-hour breaks the line instead of drawing across the gap', () => {
  const { Recs } = load();
  const whole = forecast('2026-09-30T05:00:00Z', 6, () => 300);
  const gappy = whole.filter((p, i) => i !== 3);
  const pathOf = (html) => html.match(/<path class="rec-line" d="([^"]*)"/)[1];

  assert.equal((pathOf(Recs.chartHtml(dryerRun, whole)).match(/M/g) || []).length, 1);
  assert.equal((pathOf(Recs.chartHtml(dryerRun, whole)).match(/L/g) || []).length, 5);
  const broken = pathOf(Recs.chartHtml(dryerRun, gappy));
  assert.equal((broken.match(/M/g) || []).length, 2);
  assert.equal((broken.match(/L/g) || []).length, 3);
  assert.equal((Recs.chartHtml(dryerRun, gappy).match(/class="rec-dot/g) || []).length, 5);
  // Points given out of order are drawn in time order.
  assert.equal(pathOf(Recs.chartHtml(dryerRun, whole.slice().reverse())), pathOf(Recs.chartHtml(dryerRun, whole)));
});

test('a run outside the forecast gets no band and the legend says so', () => {
  const { Recs } = load();
  // Forecast 1:00-9:00 PM; the usual run is at 11:45 PM.
  const html = Recs.chartHtml({ ...dryerRun, usualStart: '2026-09-30T15:45:00Z' }, afternoon());

  assert.equal(bandOf(html, 'usual'), null);
  assert.ok(bandOf(html, 'suggested'));
  assert.equal((html.match(/class="rec-band /g) || []).length, 1);
  assert.match(html, /Your usual 11:45pm–12:45am \(outside this forecast\)<\/span>/);
  assert.match(html, /shaded blue \(outside this forecast\)\./);
  assert.doesNotMatch(html, /Suggested 1pm–2pm \(outside/);
  // And the same for a suggestion before the forecast starts.
  const early = Recs.chartHtml({ ...dryerRun, suggestedStart: '2026-09-30T00:00:00Z' }, afternoon());
  assert.equal(bandOf(early, 'suggested'), null);
  assert.match(early, /Suggested 8am–9am \(outside this forecast\)/);
});

test('negative prices get a 0¢ gridline and a negative label', () => {
  const { Recs } = load();
  // -50, 100, 200 SGD/MWh = -5¢, 10¢, 20¢.
  const html = Recs.chartHtml(dryerRun, forecast('2026-09-30T05:00:00Z', 3, (i) => [-50, 100, 200][i]));

  assert.equal((html.match(/class="rec-chart-grid zero"/g) || []).length, 1);
  assert.ok(yLabelsOf(html).includes('0¢'));
  assert.deepEqual(yLabelsOf(html), ['-10¢', '0¢', '10¢', '20¢']); // round steps, lowest first drawn at the bottom
  assert.equal(tooltipsOf(html)[0], '1pm–1:30pm: -5¢/kWh');
  assert.match(html, /Forecast price from 1pm to 2:30pm, -5¢ to 20¢\/kWh\./);
  // Prices that stay positive have the zero line at the bottom of the chart.
  assert.match(Recs.chartHtml(dryerRun, afternoon()), /class="rec-chart-grid zero" x1="0" x2="1000" y1="100\.0"/);
});

test('empty, missing or unusable points give the not-available paragraph', () => {
  const { Recs } = load();

  assert.equal(Recs.chartHtml(dryer, []), EMPTY_CHART);
  assert.equal(Recs.chartHtml(dryer, undefined), EMPTY_CHART);
  assert.equal(Recs.chartHtml(dryer, null), EMPTY_CHART);
  assert.equal(Recs.chartHtml(dryer, [{ periodStart: '2026-09-30T05:00:00Z', usepSgdPerMwh: null },
    { periodStart: '2026-09-30T05:30:00Z', usepSgdPerMwh: 'abc' }, { periodStart: '2026-09-30T06:00:00Z' },
    { periodStart: 'not a time', usepSgdPerMwh: 100 }]), EMPTY_CHART);
  // Unusable points are skipped, the rest still chart.
  const mixed = Recs.chartHtml(dryerRun, [{ periodStart: '2026-09-30T05:00:00Z', usepSgdPerMwh: null },
    { periodStart: '2026-09-30T05:30:00Z', usepSgdPerMwh: 100 }, { periodStart: '2026-09-30T06:00:00Z', usepSgdPerMwh: 'abc' },
    { periodStart: 'nope', usepSgdPerMwh: 100 }, { periodStart: '2026-09-30T06:30:00Z', usepSgdPerMwh: '200' }]);
  assert.equal((mixed.match(/class="rec-dot/g) || []).length, 2);
  assert.deepEqual(tooltipsOf(mixed), ['1:30pm–2pm: 10¢/kWh', '2:30pm–3pm: 20¢/kWh']);
});


test('live savings ranges disclose warm-up and preserve negative lower bounds', () => {
  const { Recs } = load();
  const rec = { ...dryer, forecastSource: 'F3_FORECAST' };
  assert.match(Recs.rangeHtml(rec), /collecting live results \(0\/20 completed days\)/);
  assert.match(Recs.rangeHtml({ ...rec, savingsEvidence: { range: { available: true, lowerSgd: -.12, upperSgd: .42, completedDays: 22 } } }),
    /-\$0\.12 to \$0\.42.*90% target, not a guarantee/);
  assert.equal(Recs.rangeHtml({ ...rec, demoForecast: true }), '');
  assert.equal(Recs.rangeHtml({ ...rec, realisedSavingSgd: .1 }), '');
});

test('savings evidence belongs to its matching card and stale evidence is not shown as ready', () => {
  const { Recs } = load();
  const data = { recommendations: [dryer, { ...dryer, id: 9 }], savingsEvidence: { 5: { range: { available: false, reason: 'LIVE_ERROR_HISTORY_STALE' } } } };
  const rows = Recs.withSavingsEvidence(data);
  assert.match(Recs.rangeHtml({ ...rows[0], forecastSource: 'F3_FORECAST' }), /recent completed live results are missing/);
  assert.equal(rows[1].savingsEvidence, undefined);
  assert.equal(dryer.savingsEvidence, undefined);
});
