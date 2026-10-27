// Run with: node --test src/test/js/about-data.test.js
// CSDT4-50: the "About our data" page says what is measured and what is estimated, lists our
// assumptions, and links every data source.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/about-data.html'), 'utf8');
const text = html.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ');
const links = [...html.matchAll(/<a\s[^>]*href="(https?:[^"]+)"[^>]*>/g)].map((match) => match[0]);
const hrefs = links.map((tag) => tag.match(/href="([^"]+)"/)[1]);

test('it explains live vs provisional prices and that the live source is unofficial', () => {
  assert.match(text, /Live prices are provisional/);
  assert.match(text, /unofficial community service/);
  assert.match(text, /final prices/);
});

test('it explains that household use is modelled from official averages, not metered', () => {
  assert.match(text, /Modelled, not metered/);
  assert.match(text, /EMA's average monthly use/);
});

test('it lists our assumptions', () => {
  assert.match(text, /an accepted suggestion is treated as followed/);
  assert.match(text, /daily pattern is our assumption/);
  assert.match(text, /Projected this month is our assumption/);
  assert.match(text, /demo assumptions/);
});

test('every data source we use is linked', () => {
  [
    'https://nems.sn.sg/',
    'https://www.nems.emcsg.com/nems-prices',
    'https://www.mse.gov.sg/',
    'https://www.spgroup.com.sg/',
    'https://www.mom.gov.sg/',
    'https://data.gov.sg/',
    'https://api-open.data.gov.sg/v2/real-time/api/two-hr-forecast'
  ].forEach((source) => assert.ok(hrefs.some((href) => href.startsWith(source)), 'missing link to ' + source));
});

test('external links are https and open safely in a new tab', () => {
  const external = links.filter((tag) => !/fonts\.googleapis\.com/.test(tag));
  assert.ok(external.length >= 10);
  external.forEach((tag) => {
    assert.match(tag, /href="https:/, tag);
    assert.match(tag, /target="_blank"/, tag);
    assert.match(tag, /rel="noopener"/, tag);
  });
});
