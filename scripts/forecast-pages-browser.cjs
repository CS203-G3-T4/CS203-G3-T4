// Actual Spring pages in Chromium. Test fixtures and real-data proofs remain labelled separately.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
(async()=>{
  const base=process.argv[2],output=process.argv[3]||'target';fs.mkdirSync(output,{recursive:true});
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try{
    const anonymous=await browser.newContext();
    assert.equal((await anonymous.request.get(base+'/api/v1/admin/forecast-accuracy')).status(),403);
    assert.equal((await anonymous.request.get(base+'/admin-forecast.html')).status(),401);
    const context=await browser.newContext({httpCredentials:{username:process.env.WATTLY_ADMIN_USER,password:process.env.WATTLY_ADMIN_PASSWORD,origin:base,send:'always'}});
    const page=await context.newPage(); const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base+'/forecast.html',{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>!document.getElementById('forecast-status').textContent.includes('Loading'));
    const live=await (await page.request.get(base+'/api/v1/forecast/latest')).json();
    const today=await (await page.request.get(base+'/api/v1/forecast/today')).json();
    if(live.available){
      assert.equal(await page.locator('#forecast-points tr').count(),24);
      await page.waitForSelector('#live-price-chart circle');
      assert.match(await page.locator('#forecast-metadata').textContent(),/SGT/);
    }
    assert.equal(await page.locator('#actual-points tr').count(),today.prices.length);
    await page.screenshot({path:path.join(output,'resident-forecast.png'),fullPage:true});
    await page.setViewportSize({width:390,height:844});
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),true);
    await page.screenshot({path:path.join(output,'resident-forecast-mobile.png'),fullPage:true});
    await page.setViewportSize({width:1400,height:1000});
    await page.goto(base+'/admin-forecast.html',{waitUntil:'domcontentloaded'});
    await page.waitForFunction(()=>!document.getElementById('accuracy-status').textContent.includes('Loading'));
    assert.doesNotMatch(await page.locator('#accuracy-status').textContent(),/ADMIN access required|unavailable/);
    const response=await context.request.get(base+'/api/v1/admin/forecast-accuracy?days=7&horizon=1');
    assert.equal(response.status(),200); const accuracy=await response.json();
    if(process.env.WATTLY_ADMIN_EXPECT_SERIES==='true'){
      assert.ok(accuracy.series.length>0 && accuracy.commonPairs>0);
      await page.waitForSelector('#accuracy-chart circle');
      assert.ok(await page.locator('#accuracy-points tr').count()>0);
    }
    assert.equal(await page.locator('#accuracy-scores tr').count(),accuracy.commonScores.length);
    assert.match(await page.locator('#accuracy-context').textContent(),/PROVISIONAL_FIRST_EVALUATION/);
    await page.selectOption('#accuracy-horizon','24');
    await page.waitForFunction(()=>document.getElementById('accuracy-context').textContent.includes('horizon 24 half-hours'));
    await page.screenshot({path:path.join(output,'admin-forecast.png'),fullPage:true});
    assert.deepEqual(errors,[]);
    fs.writeFileSync(path.join(output,'browser-proof.json'),JSON.stringify({
      source:process.env.WATTLY_BROWSER_PROOF_SOURCE||'ISOLATED_TEST_ONLY',
      actualPageRequests:true,anonymousApiStatus:403,anonymousPageStatus:401,adminAuthorized:true,
      residentAvailable:live.available,residentMethod:live.run?.selectedModel,residentForecastRows:live.run?.points.length||0,
      actualPeriods:today.prices.length,adminSeriesPairs:accuracy.series.length,adminCommonPairs:accuracy.commonPairs,
      horizonSelector:true,mobileOverflow:false,browserErrors:errors},null,2));
    await context.close();await anonymous.close();
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
