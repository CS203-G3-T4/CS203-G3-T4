// Run only against the isolated acceptance app; saving a budget verifies V11 persistence.
const {chromium}=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
(async()=>{
  const base=process.argv[2],output=process.argv[3];fs.mkdirSync(output,{recursive:true});
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']});
  try{
    const page=await browser.newPage({viewport:{width:1400,height:1000}});
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.goto(base+'/dashboard.html?household=1');
    await page.waitForFunction(()=>!document.getElementById('price-status').textContent.includes('Loading'));
    assert.notEqual(await page.locator('#price-value').textContent(),'—');
    assert.equal(await page.locator('a[href^="/forecast.html"]').count(),1);
    await page.screenshot({path:path.join(output,'dashboard.png'),fullPage:true});
    await page.goto(base+'/recommendations.html?household=1');
    await page.waitForFunction(()=>!document.getElementById('recommendation-note').textContent.includes('Checking'));
    const recommendations=await page.request.get(base+'/api/v1/households/1/recommendations');
    assert.equal(recommendations.status(),200);
    await page.screenshot({path:path.join(output,'recommendations.png'),fullPage:true});
    await page.goto(base+'/spending.html?household=1');
    await page.waitForFunction(()=>!document.getElementById('save-budget').disabled);
    await page.fill('#monthly-budget','125.50');
    await page.fill('#planning-rate','29.50');
    await Promise.all([page.waitForResponse(r=>r.url().endsWith('/households/1/budget')&&r.request().method()==='PUT'),page.click('#save-budget')]);
    await page.reload();
    await page.waitForFunction(()=>document.getElementById('monthly-budget').value!=='');
    const response=await page.request.get(base+'/api/v1/households/1/budget');
    assert.equal(response.status(),200);const budget=await response.json();
    assert.equal(budget.monthlyBudgetSgd,125.5);assert.equal(budget.planningRateCentsPerKwh,29.5);
    assert.equal(budget.modelled,true);assert.ok(budget.projectedSpendMonthSgd>0);
    assert.match(await page.locator('#projected-spend').textContent(),/\$/);
    await page.screenshot({path:path.join(output,'spending.png'),fullPage:true});
    const fixed=await (await page.request.get(base+'/api/v1/households/3/budget')).json();
    assert.equal(fixed.rateSource,'FIXED_PLAN');
    assert.deepEqual(errors,[]);
    fs.writeFileSync(path.join(output,'proof.json'),JSON.stringify({source:'ISOLATED_APP_ONLY',dashboard:true,
      recommendations:true,spendingBudgetSavedAndReloaded:true,fixedRatePreserved:true,browserErrors:errors},null,2));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
