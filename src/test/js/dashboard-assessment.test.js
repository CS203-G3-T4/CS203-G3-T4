const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
class Element{constructor(){this.textContent='';this.children=[];this.hidden=false;}replaceChildren(){this.children=[];}appendChild(n){this.children.push(n);}addEventListener(event,fn){this[event]=fn;}}
const base={available:true,stale:false,actualPrice:120,typical:100,deviation:20,deviationPercent:20,spikeThreshold:103,classification:'SPIKE',sampleCount:28,periodStart:'2026-11-02T12:00:00Z'};
const price={centsPerKwh:12,stale:false,marketPrice:{price:120,forecastDemandMw:6000,intervalStart:'2026-11-02T12:00:00Z',sourceUpdatedAt:'2026-11-02T12:01:00Z'}};
async function page(assessmentResponse,{market={ok:true,data:price}}={}){
 const nodes={},calls=[],timers=[];
 const c=vm.createContext({Date,Intl,Number,Math,document:{getElementById:id=>nodes[id]??(nodes[id]=new Element()),createElement:()=>new Element()},window:{setInterval:fn=>timers.push(fn)},
 Wattly:{api:async(_method,url)=>{calls.push(url);if(url.endsWith('/prices/current'))return market;if(url.endsWith('/current-assessment'))return typeof assessmentResponse==='function'?assessmentResponse():assessmentResponse instanceof Error?Promise.reject(assessmentResponse):assessmentResponse;if(url.endsWith('/weather/today'))return {ok:true,data:{available:false}};return {ok:true,data:{name:'Test household',exposedToWholesalePrice:true}};},householdId:()=>1,keepHouseholdInLinks(){},loadHouseholdSwitcher(){},renderHouseholdCard(){}},WattlyRecommendations:{init(){},load:async()=>{}},WattlyBudget:{load:async()=>null,showBanner(){}}});
 vm.runInContext(fs.readFileSync('src/main/resources/static/js/current-assessment.js','utf8'),c);
 vm.runInContext(fs.readFileSync('src/main/resources/static/js/dashboard.js','utf8'),c);
 await new Promise(resolve=>setImmediate(resolve));return {nodes,calls,timers};
}
test('Dashboard fetches and displays the matching classification and deviation',async()=>{
 const p=await page({ok:true,data:base});assert(p.calls.includes('/api/v1/forecast/current-assessment'));
 assert.match(p.nodes['price-assessment'].textContent,/^Spike.*20% above typical/);
 assert.equal(p.nodes['price-value'].textContent,'12.0');assert.equal(p.nodes['price-status'].textContent,'Live market data');
});
test('a changed price or half-hour cannot borrow another snapshot assessment',async()=>{
 for(const change of [{actualPrice:121},{periodStart:'2026-11-02T12:30:00Z'},{periodStart:null}]){
  const p=await page({ok:true,data:{...base,...change}});assert.match(p.nodes['price-assessment'].textContent,/waiting for a matching price/);
 }
});
test('stale and unverified classifications are unavailable on the Dashboard',async()=>{
 for(const change of [{stale:true},{reason:'PERIOD_MAPPING_UNVERIFIED'}]){
  const p=await page({ok:true,data:{...base,...change}});assert.match(p.nodes['price-assessment'].textContent,/unavailable/);assert.doesNotMatch(p.nodes['price-assessment'].textContent,/^Spike/);
 }
 const p=await page({ok:true,data:base},{market:{ok:true,data:{...price,stale:true}}});assert.match(p.nodes['price-assessment'].textContent,/stale/);
});
test('assessment outage and empty history preserve the rest of the price card',async()=>{
 for(const response of [{ok:false,status:503,data:null},new Error('fixture outage'),{ok:true,data:{available:false,reason:'INSUFFICIENT_REFERENCE_HISTORY',sampleCount:13}}]){
  const p=await page(response);assert.equal(p.nodes['price-value'].textContent,'12.0');assert.match(p.nodes['price-assessment'].textContent,/unavailable|Insufficient history/);
 }
 const p=await page({ok:true,data:base},{market:{ok:false,status:503}});assert.equal(p.nodes['price-value'].textContent,'—');assert.doesNotMatch(p.nodes['price-assessment'].textContent,/^Spike/);
});
test('slow assessment leaves price and Refresh usable and an older result cannot replace a newer one',async()=>{
 let finish,attempt=0;const pending=new Promise(resolve=>{finish=resolve;});
 const p=await page(()=>attempt++===0?pending:{ok:true,data:base});
 assert.equal(p.nodes['price-value'].textContent,'12.0');assert.match(p.nodes['price-assessment'].textContent,/Checking/);
 assert.equal(p.nodes['refresh-button'].disabled,false);
 await p.timers[0]();await new Promise(resolve=>setImmediate(resolve));
 assert.match(p.nodes['price-assessment'].textContent,/^Spike/);
 finish({ok:true,data:{...base,reason:'PERIOD_MAPPING_UNVERIFIED'}});await new Promise(resolve=>setImmediate(resolve));
 assert.match(p.nodes['price-assessment'].textContent,/^Spike/);
});
