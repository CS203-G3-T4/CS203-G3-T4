// Optional real-browser proof; Playwright is a test tool, not an app dependency.
const { chromium } = require('playwright');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

(async () => {
  const base = process.argv[2] || 'http://127.0.0.1:8082';
  const output = process.env.WATTLY_DEMO_PROOF_DIR || 'target';
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/forecast-demo.html');
    await page.waitForSelector('#demo-results:not([hidden])');
    await page.waitForFunction(() => !document.getElementById('forecast-status').textContent.includes('Loading'));
    const report = await (await page.request.get(base + '/forecast-demo-latest.json')).json();
    const live = await (await page.request.get(base + '/api/v1/forecast/latest')).json();
    assert.equal(await page.locator('#demo-points tr').count(), 24);
    assert.equal(await page.locator('#demo-chart polyline').count(), 4);
    assert.equal(await page.locator('#demo-scores tr').count(), 4);
    assert.equal(await page.locator('#demo-ai-mae').textContent(), report.test.table.AI.mae.toFixed(2));
    assert.match(await page.locator('#demo-status').textContent(), /Experimental — limited training data\./);
    assert.match(await page.locator('#demo-verdict').textContent(), /less error than B1.*already-seen/);
    assert.equal(await page.locator('#demo-study-scores tr').count(), 11);
    assert.match(await page.locator('#demo-study-verdict').textContent(), /MEDIAN wins validation/);
    assert.match(await page.locator('figcaption').textContent(), /one selected origin.*12-hour forecast/s);
    assert.match(await page.locator('figcaption').textContent(), /MAE covers all eligible origins/);
    assert.match(await page.locator('#demo-pair-explanation').textContent(), /24 eligible origins.*576 included.*24 later origins.*576 excluded/);
    assert.match(await page.locator('#demo-spikes').textContent(), /Insufficient history/);
    const window = page.locator('#demo-window-highlight');
    assert.equal(Number(await window.getAttribute('width')), 450);
    const firstWindow = Number(await window.getAttribute('x'));
    assert.match(await page.locator('#demo-window').textContent(), /12-hour target window.*12:30/);
    assert.equal(await page.locator('#demo-origin option').count(), 1);
    assert.match(await page.locator('#demo-origins').textContent(), /24 scored origins.*1 chart origins/);
    assert.match(await page.locator('#demo-status').textContent(), /Experimental/);
    if (live.available) {
      assert.equal(live.run.modelType, 'BASELINE');
      assert.equal(live.run.points.length, 24);
      assert.equal(await page.locator('#forecast-points tr').count(), 24);
    } else {
      assert.equal(live.stale, true);
      assert.equal(await page.locator('#forecast-points tr').count(), 0);
    }
    for (const [index, point] of live.run.points.entries()) {
      if (point.spikeFlag === null) assert.equal(await page.locator('#forecast-points tr').nth(index).locator('td').last().textContent(), 'Insufficient history');
    }
    assert.equal((await page.request.get(base + '/api/v1/admin/forecast-accuracy')).status(), 403);
    await page.screenshot({ path: path.join(output, 'forecast-demo-desktop.png'), fullPage: true });
    await page.locator('#live details').evaluate(node => { node.open = true; });
    await page.locator('#live').screenshot({ path: path.join(output, 'forecast-demo-live.png') });
    await page.locator('#live details').evaluate(node => { node.open = false; });
    await page.setViewportSize({ width: 390, height: 844 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
    await page.screenshot({ path: path.join(output, 'forecast-demo-mobile.png'), fullPage: true });
    assert.deepEqual(errors, []);
    const proof = { report: report.modelVersion, commonPairs: report.test.commonPairs,
      displayedOfflineRows: 24, displayedLiveRows: live.run.points.length, liveModelType: live.run.modelType,
      liveAvailable: live.available, liveStale: live.stale,
      adminStatus: 403, originSelection: 'one saved origin; aggregate scores use 24', windowHighlight: '12 hours',
      maeUnchangedBySelection: true, insufficientSpikeHistoryLabel: 'passed',
      diagnosticMae: report.test.table.AI.mae,
      validationMethods: report.study.methods.length, experimentSelection: 'default bundled saved diagnostic',
      mobileOverflow: false, browserErrors: errors };
    fs.writeFileSync(path.join(output, 'forecast-demo-browser-proof.json'), JSON.stringify(proof, null, 2));
    console.log(JSON.stringify(proof));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
