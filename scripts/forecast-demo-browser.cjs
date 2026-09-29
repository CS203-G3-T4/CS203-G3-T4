// Optional real-browser proof; Playwright is a test tool, not an app dependency.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');

(async () => {
  const base = process.argv[2] || 'http://127.0.0.1:8082';
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/forecast-demo.html');
    await page.waitForSelector('#demo-results:not([hidden])');
    await page.waitForFunction(() => !document.getElementById('forecast-status').textContent.includes('Loading'));
    const report = await (await page.request.get(base + '/forecast-demo.json')).json();
    const live = await (await page.request.get(base + '/api/v1/forecast/latest')).json();
    assert.equal(await page.locator('#demo-points tr').count(), 24);
    assert.equal(await page.locator('#demo-chart polyline').count(), 4);
    assert.equal(await page.locator('#demo-scores tr').count(), 4);
    assert.equal(await page.locator('#demo-ai-mae').textContent(), report.test.table.AI.mae.toFixed(2));
    assert.match(await page.locator('#demo-status').textContent(), /Experimental — limited training data\./);
    assert.match(await page.locator('#demo-verdict').textContent(), /more error than B1/);
    const first = await page.locator('#demo-points tr').first().textContent();
    await page.selectOption('#demo-origin', '1');
    assert.notEqual(await page.locator('#demo-points tr').first().textContent(), first);
    await page.selectOption('#demo-origin', '0');
    assert.equal(live.run.modelType, 'BASELINE');
    assert.equal(live.run.points.length, 24);
    assert.equal(await page.locator('#forecast-points tr').count(), 24);
    assert.equal((await page.request.get(base + '/api/v1/admin/forecast-accuracy')).status(), 403);
    await page.screenshot({ path: 'target/forecast-demo-desktop.png', fullPage: true });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.screenshot({ path: 'target/forecast-demo-mobile.png', fullPage: true });
    assert.deepEqual(errors, []);
    const proof = { report: report.modelVersion, commonPairs: report.test.commonPairs,
      displayedOfflineRows: 24, displayedLiveRows: 24, liveModelType: live.run.modelType,
      adminStatus: 403, originSelection: 'passed', mobileOverflow: false, browserErrors: errors };
    fs.writeFileSync('target/forecast-demo-browser-proof.json', JSON.stringify(proof, null, 2));
    console.log(JSON.stringify(proof));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
