// Run with: node --test src/test/js/spend.test.js
// CSDT4-41: "Spent so far this month" on the Spending page, with each charge's source.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const staticDir = path.join(__dirname, '../../main/resources/static/js');

function load(nodes) {
  const document = {
    querySelector() { return null; },
    createElement() {
      let text = '';
      return {
        set textContent(value) { text = value; },
        get innerHTML() { return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;'); }
      };
    },
    getElementById(id) { return nodes ? nodes[id] || null : null; }
  };
  const context = vm.createContext({ document, Intl, Date, window: { location: { search: '?household=1' } },
    URLSearchParams });
  vm.runInContext(fs.readFileSync(path.join(staticDir, 'wattly.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(staticDir, 'spend.js'), 'utf8'), context);
  return { Wattly: vm.runInContext('Wattly', context), Spend: vm.runInContext('WattlySpend', context) };
}

const priceLinked = {
  planType: 'PRICE_LINKED', totalSgd: 38.21, energySgd: 25.1, gstSgd: 3.15, kwh: 190.5,
  halfHoursSoFar: 830, halfHoursCounted: 812, halfHoursWithoutPrice: 18,
  charges: [
    { code: 'TRANSMISSION_PEAK', charge: 'Transmission charge (low tension, peak 7am-11pm)', unit: 'SGD_PER_KWH',
      rate: 0.061, amountSgd: 8.5, source: 'SP Group fact sheet', sourceUrl: 'https://www.spgroup.com.sg/x.pdf' },
    { code: 'METER_READING', charge: 'Meter Reading and Data Management', unit: 'SGD_PER_MONTH', rate: 2.18,
      amountSgd: 1.22, source: 'SP Group fact sheet', sourceUrl: null },
    { code: 'GST', charge: 'GST (9%)', unit: 'PERCENT', rate: 9, amountSgd: 3.15, source: 'SP Group <fact sheet>',
      sourceUrl: 'https://www.spgroup.com.sg/x.pdf' }],
  notes: ['Use is modelled from your home type and appliances, not read from a meter.']
};

test('it asks the month-to-date endpoint for this household', () => {
  const { Spend } = load();

  assert.equal(Spend.url(1), '/api/v1/households/1/spend/month-to-date');
});

test('rates read like a bill: cents per kWh, dollars per month and a percentage', () => {
  const { Spend } = load();

  assert.equal(Spend.rateText({ unit: 'SGD_PER_KWH', rate: 0.061 }), '6.10¢/kWh');
  assert.equal(Spend.rateText({ unit: 'SGD_PER_KWH', rate: 0.001471 }), '0.15¢/kWh');
  assert.equal(Spend.rateText({ unit: 'SGD_PER_MONTH', rate: 2.18 }), 'S$2.18/month');
  assert.equal(Spend.rateText({ unit: 'PERCENT', rate: 9 }), '9%');
});

test('the line under the figure says what it includes and how much of the month was priced', () => {
  const { Spend } = load();

  assert.equal(Spend.detailText(priceLinked), 'Market price + bill charges + GST · 812 of 830 half-hours priced');
  assert.equal(Spend.detailText({ ...priceLinked, halfHoursWithoutPrice: 0 }),
    'Market price + bill charges + GST · all 830 half-hours priced');
  assert.equal(Spend.detailText({ planType: 'FIXED', fixedRateCentsPerKwh: 29.5, kwh: 190.46 }),
    'At your contracted 29.5¢/kWh · 190.5 kWh so far (modelled)');
});

test('the breakdown lists the market price, each charge with its source link, and the total', () => {
  const { Spend } = load();
  const html = Spend.breakdownHtml(priceLinked);

  assert.match(html, /<th scope="row">Market price \(USEP\)<\/th><td>Each half-hour’s price<\/td><td class="num">S\$25\.10/);
  assert.match(html, /Transmission charge \(low tension, peak 7am-11pm\)<\/th><td>6\.10¢\/kWh<\/td><td class="num">S\$8\.50/);
  assert.match(html, /<a href="https:\/\/www\.spgroup\.com\.sg\/x\.pdf" target="_blank" rel="noopener">SP Group fact sheet<\/a>/);
  assert.match(html, /S\$2\.18\/month<\/td><td class="num">S\$1\.22<\/td><td>SP Group fact sheet<\/td>/);
  assert.match(html, /SP Group &lt;fact sheet&gt;/);
  assert.match(html, /<tr class="total"><th scope="row">Spent so far<\/th><td><\/td><td class="num">S\$38\.21/);
});

test('loading fills in the figure, the breakdown and the notes from the API', async () => {
  const nodes = {};
  ['spent-to-date', 'energy-to-date', 'spend-breakdown-intro', 'spend-breakdown-rows', 'spend-notes'].forEach((id) => {
    nodes[id] = { textContent: '', innerHTML: '' };
  });
  nodes['spend-breakdown'] = { hidden: true };
  const { Wattly, Spend } = load(nodes);
  const urls = [];
  Wattly.api = async (method, url) => { urls.push(method + ' ' + url); return { ok: true, data: priceLinked }; };

  await Spend.load();

  assert.deepEqual(urls, ['GET /api/v1/households/1/spend/month-to-date']);
  assert.equal(nodes['spent-to-date'].textContent, 'S$38.21');
  assert.equal(nodes['spend-breakdown'].hidden, false);
  assert.match(nodes['spend-notes'].innerHTML, /<li>Use is modelled/);
  assert.match(nodes['spend-breakdown-intro'].textContent, /market price, plus the other charges on the bill and GST\.$/);
});

test('a fixed-rate home is explained without market prices or extra charges', () => {
  const { Spend } = load();

  assert.equal(Spend.introText({ planType: 'FIXED' }),
    'Every finished half-hour this month: your home’s modelled use × your contracted rate.');
  assert.match(Spend.breakdownHtml({ planType: 'FIXED', fixedRateCentsPerKwh: 29.5, energySgd: 79.31, totalSgd: 79.31,
    charges: [] }), /Your contracted rate<\/th><td>29\.5¢\/kWh<\/td><td class="num">S\$79\.31<\/td><td>Household Settings/);
});
