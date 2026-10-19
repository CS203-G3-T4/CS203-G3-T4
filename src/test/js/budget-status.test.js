// Run with: node --test src/test/js/budget-status.test.js
// CSDT4-42: the over-budget banner and projection wording shared by the Dashboard and the Spending page.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const staticDir = path.join(__dirname, '../../main/resources/static/js');

function load() {
  const context = vm.createContext({ document: { createElement() { return {}; }, getElementById() { return null; } },
    Intl, Date, window: { location: { search: '?household=1' } }, URLSearchParams });
  vm.runInContext(fs.readFileSync(path.join(staticDir, 'wattly.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(staticDir, 'budget-status.js'), 'utf8'), context);
  return { Wattly: vm.runInContext('Wattly', context), Budget: vm.runInContext('WattlyBudget', context) };
}

// The hand-worked example from BudgetStatusServiceTest: S$50 so far + S$5/day x 21.5 days = S$157.50.
const over = {
  monthlyBudgetSgd: 150, alertsEnabled: true, spentSoFarSgd: 50, averageDailySpendSgd: 5, daysInAverage: 7,
  daysLeft: 21.5, projectedMonthSgd: 157.5, remainingSgd: -7.5, overBudget: true, alert: true
};

test('the banner shows only when the projection is over the budget and alerts are on', () => {
  const { Budget } = load();

  assert.equal(Budget.bannerText(over), 'You’re on track to go over your budget: about S$157.50 this month ' +
    'against your S$150.00 budget (S$7.50 over). Cutting back now can still bring it down.');
  assert.equal(Budget.bannerText({ ...over, alert: false, alertsEnabled: false }), null);
  assert.equal(Budget.bannerText({ ...over, overBudget: false, alert: false, remainingSgd: 42.5 }), null);
  assert.equal(Budget.bannerText(null), null);
});

test('the projection says how it was worked out', () => {
  const { Budget } = load();

  assert.equal(Budget.projectionDetail(over), 'S$5.00/day over the last 7 days × 21.5 days left');
  assert.equal(Budget.projectionDetail({ projectedMonthSgd: null, message: 'Not enough price history.' }),
    'Not enough price history.');
});

test('the budget box says how far over or under the projection is', () => {
  const { Budget } = load();

  assert.equal(Budget.remainingText(over), 'Projected S$7.50 over');
  assert.equal(Budget.remainingText({ ...over, remainingSgd: 42.5 }), 'Projected S$42.50 left');
  assert.equal(Budget.remainingText({ monthlyBudgetSgd: null }), 'Set a budget to compare');
});

test('the Dashboard line uses the same figures as the Spending page boxes', () => {
  const { Budget } = load();
  const line = Budget.summaryText(over);

  assert.equal(line, 'This month: S$50.00 spent so far · projected S$157.50 · budget S$150.00');
  // Same numbers, formatted by the same function, as the Spending page's three boxes.
  [over.spentSoFarSgd, over.projectedMonthSgd, over.monthlyBudgetSgd].forEach((value) =>
    assert.ok(line.includes(Budget.money(value))));
});

test('showBanner fills in or hides the banner element', () => {
  const { Budget } = load();
  const element = { hidden: true, className: '', textContent: '' };

  Budget.showBanner(element, over);
  assert.equal(element.hidden, false);
  assert.equal(element.className, 'banner warn budget-alert');
  assert.match(element.textContent, /S\$157\.50/);

  Budget.showBanner(element, { ...over, alert: false });
  assert.equal(element.hidden, true);
  assert.equal(element.textContent, '');
});

test('it asks the budget-status endpoint for this household', async () => {
  const { Wattly, Budget } = load();
  const urls = [];
  Wattly.api = async (method, url) => { urls.push(method + ' ' + url); return { ok: true, data: over }; };

  const status = await Budget.load();

  assert.deepEqual(urls, ['GET /api/v1/households/1/budget-status']);
  assert.equal(status.projectedMonthSgd, 157.5);
});
