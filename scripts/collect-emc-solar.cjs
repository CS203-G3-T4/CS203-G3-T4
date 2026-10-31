// Requires operator-verified collection permission; see docs/forecasting/emc-collection-permission.md.
// Normal browser session; no login or access-control bypass.
const {chromium}=require('playwright');
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const {MAX_RESPONSE_BYTES,serializeSnapshot}=require('./emc-snapshot-limits.cjs');
const args=process.argv.slice(2), flag=n=>args.includes(n), option=(n,d)=>args.includes(n)?args[args.indexOf(n)+1]:d;
const output=option('--output',null), interval=Number(option('--interval-seconds','300'));
if(!output || !Number.isFinite(interval) || interval<120) throw Error('Use --output FILE and interval >=120 seconds');
const sha=b=>crypto.createHash('sha256').update(b).digest('hex');
const base='https://www.nems.emcsg.com/api/DataSync/Get?value=';
async function capture(browser){
 const page=await browser.newPage();
 try{
  await page.goto('https://www.nems.emcsg.com/nems-prices',{waitUntil:'networkidle',timeout:35000});
  const responses=[];
  for(const value of [10,12,14]){
   const url=base+value+'&fromDate=&toDate=';
   const response=await page.request.get(url,{timeout:20000});
   if(response.status()!==200)throw Error('EMC returned HTTP '+response.status());
   const raw=await response.body();
   if(raw.length>MAX_RESPONSE_BYTES)throw Error('EMC response exceeds limit');
   const receivedAt=new Date().toISOString(), body=JSON.parse(raw.toString('utf8'));
   if(body.success!==true)throw Error('EMC reported unsuccessful response');
   responses.push({value,url,receivedAt,sha256:sha(raw),body});
  }
  const snapshot={schemaVersion:1,responses};
  const content=serializeSnapshot(snapshot), directory=path.dirname(path.resolve(output));
  fs.mkdirSync(path.join(directory,'archive'),{recursive:true});
  // Archive the exact receipt-stamped snapshot, so the forecast's hash is recoverable.
  const identity=sha(content);
  const archive=path.join(directory,'archive',identity+'.json');
  if(!fs.existsSync(archive))fs.writeFileSync(archive,content,{flag:'wx'});
  const temp=output+'.tmp-'+process.pid;
  fs.writeFileSync(temp,content);fs.renameSync(temp,output);
  console.log(JSON.stringify({receivedAt:responses.at(-1).receivedAt,snapshot:identity,status:'COLLECTED'}));
 }finally{await page.close();}
}
(async()=>{
 const browser=await chromium.launch({channel:option('--channel','msedge'),headless:true});
 try{
  let failures=0;
  do{
   try{await capture(browser);failures=0;}catch(e){console.error('Solar collection failed: '+e.message);if(flag('--once') || ++failures>=3)throw e;}
   if(flag('--once'))break;
   await new Promise(resolve=>setTimeout(resolve,interval*1000));
  }while(true);
 }finally{await browser.close();}
})().catch(e=>{console.error(e.message);process.exitCode=1;});
