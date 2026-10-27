// Run with: node --test src/test/js/admin-overview.test.js
// CSDT4-55 admin overview; CSDT4-49: totals that include simulated households say so.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = path.join(__dirname, '../../main/resources/static/js/admin-overview.js');
const IDS = ['status', 'error', 'households-monitored', 'price-linked', 'savings-total', 'forecast-mae',
  'households-split', 'price-linked-split', 'savings-split', 'simulated-note', 'household-rows'];

class FakeNode {
  constructor() { this.children = []; this.textContent = ''; this.innerHTML = ''; this.hidden = false; }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren() { this.children = []; }
}

// Loads the real script with a fake page; `fetchImpl` stands in for the admin API.
function load(fetchImpl) {
  const nodes = Object.fromEntries(IDS.map((id) => [id, new FakeNode()]));
  const context = vm.createContext({
    document: { getElementById: (id) => nodes[id], createElement: () => new FakeNode() },
    fetch: fetchImpl || (async () => ({ ok: false, status: 500 })), Date, Number, String
  });
  vm.runInContext(fs.readFileSync(script, 'utf8'), context);
  return { nodes, Overview: vm.runInContext('WattlyAdminOverview', context) };
}

const OVERVIEW = {
  asOf: '2026-10-25T07:00:00Z', householdsMonitored: 4, priceLinkedHouseholds: 3,
  totalSavingsThisMonthSgd: 2.5, forecastMaeSgdPerMwh: 12.345, forecastAccuracyAvailable: true,
  households: [
    { id: 1, name: 'The Tan Household', plan: 'Price-linked (USEP)', savingsThisMonthSgd: 1.2,
      status: 'ON_TRACK', simulated: false },
    { id: 7, name: 'Simulated household 001', plan: 'Price-linked (USEP)', savingsThisMonthSgd: 0.8,
      status: 'NO_BUDGET', simulated: true },
    { id: 8, name: 'Simulated household 002', plan: 'Price-linked (USEP)', savingsThisMonthSgd: 0.5,
      status: 'OVER_PACE', simulated: true },
    { id: 9, name: 'Simulated household 003', plan: 'Not exposed (fixed rate)', savingsThisMonthSgd: 0,
      status: 'NOT_EXPOSED', simulated: true }]
};

test('it counts how much of each total comes from simulated households', () => {
  const { Overview } = load();
  const share = Overview.simulatedShare(OVERVIEW);

  assert.equal(share.households, 3);
  assert.equal(share.priceLinked, 2);
  assert.equal(Number(share.savingsSgd.toFixed(2)), 1.3);
});

test('each total that includes simulated households shows the split under it', () => {
  const { nodes, Overview } = load();

  Overview.render(OVERVIEW);

  assert.equal(nodes['households-monitored'].textContent, 4);
  assert.equal(nodes['households-split'].textContent, '3 simulated · 1 real');
  assert.equal(nodes['households-split'].hidden, false);
  assert.equal(nodes['price-linked-split'].textContent, '2 simulated · 1 real');
  assert.equal(nodes['savings-split'].textContent, 'S$1.30 from simulated households');
  assert.equal(nodes['forecast-mae'].textContent, '12.35 SGD/MWh');
});

test('a note under the totals says in words that they include simulated households', () => {
  const { nodes, Overview } = load();

  Overview.render(OVERVIEW);

  assert.equal(nodes['simulated-note'].hidden, false);
  assert.match(nodes['simulated-note'].textContent, /^These totals include 3 simulated households, made up for the demo/);
  assert.match(nodes['simulated-note'].textContent, /not real homes/);
});

test('with no simulated households the splits and the note stay hidden', () => {
  const { nodes, Overview } = load();

  Overview.render({ ...OVERVIEW, householdsMonitored: 1, priceLinkedHouseholds: 1,
    households: [OVERVIEW.households[0]] });

  ['households-split', 'price-linked-split', 'savings-split', 'simulated-note'].forEach((id) => {
    assert.equal(nodes[id].hidden, true, id);
    assert.equal(nodes[id].textContent, '', id);
  });
});

test('simulated rows in the table are still marked "(Simulated)"', () => {
  const { nodes, Overview } = load();

  Overview.render(OVERVIEW);

  const rows = nodes['household-rows'].children.map((row) => row.innerHTML);
  assert.doesNotMatch(rows[0], /Simulated\)/);
  assert.match(rows[1], /Simulated household 001 <span class="simulated">\(Simulated\)<\/span>/);
});

test('the note uses the singular for one simulated household', () => {
  const { Overview } = load();

  assert.match(Overview.simulatedNote({ households: 1 }), /^These totals include 1 simulated household,/);
  assert.equal(Overview.simulatedNote({ households: 0 }), '');
});

test('loading fills the page from the admin API', async () => {
  const urls = [];
  const { nodes, Overview } = load(async (url) => {
    urls.push(url);
    return { ok: true, json: async () => OVERVIEW };
  });

  await Overview.load(); // the page also loads itself once on start-up

  assert.ok(urls.length >= 1);
  assert.ok(urls.every((url) => url === '/api/v1/admin/overview'));
  assert.equal(nodes['households-split'].textContent, '3 simulated · 1 real');
  assert.match(nodes.status.textContent, /^Updated /);
});

test('a 403 shows that admin access is needed', async () => {
  const { nodes, Overview } = load(async () => ({ ok: false, status: 403 }));

  await Overview.load();

  assert.equal(nodes.error.hidden, false);
  assert.equal(nodes.error.textContent, 'Admin access required.');
});
