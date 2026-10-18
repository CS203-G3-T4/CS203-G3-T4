const WattlyAccuracy={data:null};
function accuracyRow(parent,values) {const tr=document.createElement('tr');values.forEach(v=>{const td=document.createElement('td');td.textContent=v;tr.appendChild(td);});parent.appendChild(tr);}
WattlyAccuracy.chart=function(){
  const model=document.getElementById('accuracy-model').value;
  const rows=WattlyAccuracy.data.series.filter(r=>r.model+':'+r.modelVersion===model);
  document.getElementById('accuracy-chart').style.display=rows.length?'block':'none';
  const points=document.getElementById('accuracy-points');points.replaceChildren();
  const time=v=>new Date(v).toLocaleString('en-SG',{timeZone:'Asia/Singapore'});
  rows.forEach(r=>accuracyRow(points,[time(r.asOf),time(r.targetPeriod),r.modelVersion,Number(r.predictedUsep).toFixed(2),r.actualUsep===null?'Missing':Number(r.actualUsep).toFixed(2)]));
  WattlyPriceChart.draw(document.getElementById('accuracy-chart'),[
    {name:'Forecast',color:'#2a78d6',points:rows.map(r=>({time:r.targetPeriod,value:Number(r.predictedUsep)}))},
    {name:'Actual',color:'#176346',points:rows.filter(r=>r.actualUsep!==null).map(r=>({time:r.targetPeriod,value:Number(r.actualUsep)}))}]);
};
WattlyAccuracy.refresh=async function(){
  const response=await fetch('/api/v1/admin/forecast-accuracy?days=7&horizon='+document.getElementById('accuracy-horizon').value+'&truthPolicy='+document.getElementById('accuracy-policy').value,{cache:'no-store'});
  if(!response.ok){document.getElementById('accuracy-status').textContent=response.status===403?'ADMIN access required.':'Accuracy data unavailable.';return;}
  const data=await response.json();WattlyAccuracy.data=data;
  document.getElementById('accuracy-status').textContent=data.available?'Saved forecasts loaded.':'No saved forecast/actual data for this window.';
  const tile=data.commonScores.find(s=>s.model==='AI')||data.commonScores[0];
  document.getElementById('accuracy-tile').textContent='Common-pair '+(tile?tile.model:'forecast')+' MAE: '+(tile&&tile.mae!==null?Number(tile.mae).toFixed(2)+' SGD/MWh':'Unavailable — insufficient common coverage');
  document.getElementById('accuracy-context').textContent='Mode '+data.mode+' · horizon '+data.horizon+' half-hours · '+data.truthPolicy+' · '+data.commonPairs+' common pairs. '+data.comparison;
  const scores=document.getElementById('accuracy-scores');scores.replaceChildren();
  data.commonScores.forEach(s=>accuracyRow(scores,[s.model+' / '+s.modelVersions.join(', '),s.mae===null?'Unavailable':Number(s.mae).toFixed(2),s.commonPairs,s.eligiblePairs+' / '+s.savedPairs,s.missingActualPairs]));
  const select=document.getElementById('accuracy-model');select.replaceChildren();
  [...new Set(data.series.map(r=>r.model+':'+r.modelVersion))].forEach(v=>{const option=document.createElement('option');option.value=v;option.textContent=v;select.appendChild(option);});
  WattlyAccuracy.chart();
};
const horizons=document.getElementById('accuracy-horizon');
for(let h=1;h<=24;h++){const option=document.createElement('option');option.value=h;option.textContent=(h/2)+' hours';horizons.appendChild(option);}
horizons.addEventListener('change',()=>WattlyAccuracy.refresh().catch(()=>{document.getElementById('accuracy-status').textContent='Accuracy data unavailable.';}));
document.getElementById('accuracy-policy').addEventListener('change',()=>WattlyAccuracy.refresh());
document.getElementById('accuracy-model').addEventListener('change',WattlyAccuracy.chart);
WattlyAccuracy.refresh().catch(()=>{document.getElementById('accuracy-status').textContent='Accuracy data unavailable.';});
