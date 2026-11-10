const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
function helper(){const c=vm.createContext({Date,Number,Math});vm.runInContext(fs.readFileSync('src/main/resources/static/js/current-assessment.js','utf8'),c);return vm.runInContext('WattlyAssessment',c);}
const good={available:true,stale:false,reason:null,actualPrice:120,typical:100,deviation:20,deviationPercent:20,spikeThreshold:103,classification:'SPIKE',sampleCount:28,periodStart:'2026-11-02T12:00:00Z'};
test('fresh cheap/normal/spike labels report the fixed reference and handle nonpositive typical prices',()=>{
 const h=helper();assert.match(h.describe(good),/^Spike.*20% above typical.*28 prior days/);
 assert.match(h.describe({...good,classification:'CHEAP',actualPrice:80,deviation:-20,deviationPercent:-20}),/^Cheap now.*20% below typical/);
 assert.match(h.describe({...good,classification:'NORMAL',actualPrice:100,deviation:0,deviationPercent:0}),/^Normal.*0% above typical/);
 assert.match(h.describe({...good,classification:'EXPENSIVE',typical:0,deviation:5,deviationPercent:null}),/Normal \(above typical\).*5.00 SGD\/MWh.*percentage unavailable/);
});
test('stale or unverified legacy responses never retain a confident label',()=>{
 const h=helper();for(const input of [{...good,stale:true},{...good,reason:'PERIOD_MAPPING_UNVERIFIED'},{...good,available:false,reason:'STALE_PRICE'}]){
   const text=h.describe(input);assert.match(text,/unavailable/);assert.doesNotMatch(text,/^Spike|20%|^Cheap|^Normal/);
 }
 assert.match(h.describe({...good,reason:'PERIOD_MAPPING_UNVERIFIED'}),/not been verified/);
});
test('missing, insufficient, unknown and malformed evidence is explicit',()=>{
 const h=helper();assert.match(h.describe(null),/unavailable/);
 assert.match(h.describe({available:false,stale:true,reason:'NO_PRICE'}),/no recent price/);
 assert.match(h.describe({available:false,reason:'INSUFFICIENT_REFERENCE_HISTORY',sampleCount:13}),/Insufficient history.*13 prior days/);
 for(const change of [{available:false},{reason:'UNRECOGNIZED'},{spikeThreshold:null},{actualPrice:NaN},{classification:'UNRECOGNIZED'}])assert.match(h.describe({...good,...change}),/unavailable/);
});
test('assessment must identify the exact displayed half-hour and price',()=>{
 const h=helper(),price={price:120,intervalStart:'2026-11-02T20:00:00+08:00'};
 assert.equal(h.matches(good,price),true);
 for(const p of [{...price,price:121},{...price,price:null},{...price,intervalStart:'2026-11-02T12:30:00Z'},{price:120}])assert.equal(h.matches(good,p),false);
 assert.equal(h.matches({...good,periodStart:null},price),false);
});
