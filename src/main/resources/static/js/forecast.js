// Saved Spring results only. No request from this page invokes training or Python.
const WattlyForecast = {};

WattlyForecast.render = function (view, assessment) {
  const status = document.getElementById('forecast-status');
  const metadata = document.getElementById('forecast-metadata');
  const body = document.getElementById('forecast-points');
  body.replaceChildren();
  document.getElementById('forecast-table').hidden = !view.available;
  if (!view.available) {
    status.textContent = 'Forecast unavailable. More recent, continuous price history is needed.';
    metadata.textContent = view.reason ? view.reason.replaceAll('_', ' ').toLowerCase() : '';
  } else {
    const run = view.run;
    status.textContent = view.stale ? 'Stale forecast — do not use it to schedule appliances.' :
      view.actionable ? 'Next 12 hours' : 'Forecast preview — market interval alignment awaits verification.';
    const formatted = new Intl.DateTimeFormat('en-SG', {
      timeZone: 'Asia/Singapore', dateStyle: 'medium', timeStyle: 'short'
    });
    metadata.textContent = (run.modelType === 'AI' ? (run.qualityFlags.includes('EXPERIMENTAL_AI') ? 'Experimental AI' : 'AI') : 'Baseline ' + run.selectedModel) +
      (run.qualityFlags.includes('PRICE_CALENDAR_HOLIDAY_SOLAR') ? ' · solar-assisted' : '') +
      ' · ' + run.modelVersion + ' · forecast as of ' + formatted.format(new Date(run.asOf)) + ' SGT' +
      (['PYTHON_UNAVAILABLE_OR_INVALID', 'MODEL_MANIFEST_CONFLICT'].includes(run.fallbackReason) ? ' · AI unavailable; baseline fallback' : '') +
      (run.qualityFlags.includes('BASELINE_RANKING_UNRANKED') ? ' · baseline order not yet validated' : '');
    run.points.forEach(function (point) {
      const tr = document.createElement('tr');
      [formatted.format(new Date(point.targetPeriod)),
        Number(point.predictedUsep).toFixed(2), (Number(point.predictedUsep) / 10).toFixed(2),
        typeof point.spikeFlag !== 'boolean' || !Number.isFinite(point.spikeThreshold) ? 'Insufficient history' :
          point.spikeFlag ? 'Predicted spike' : 'Below spike threshold'
      ].forEach(function (value) {
        const td = document.createElement('td'); td.textContent = value; tr.appendChild(td);
      });
      body.appendChild(tr);
    });
  }
  const current = document.getElementById('current-assessment');
  if (!assessment || assessment.actualPrice === null) {
    current.textContent = 'Current price unavailable.';
  } else {
    const deviation = assessment.available && Number.isFinite(assessment.deviationPercent) ?
      ' · ' + Math.abs(assessment.deviationPercent).toFixed(0) + '% ' +
      (assessment.deviationPercent < 0 ? 'below' : 'above') + ' typical' :
      assessment.available && Number.isFinite(assessment.deviation) ?
        ' · ' + Math.abs(assessment.deviation).toFixed(2) + ' SGD/MWh ' +
        (assessment.deviation < 0 ? 'below' : 'above') + ' typical (percentage unavailable for zero/negative typical prices)' : '';
    current.textContent = 'Current price: ' + (Number(assessment.actualPrice) / 10).toFixed(2) + ' cents/kWh. ' +
      (assessment.available ? (assessment.classification === 'EXPENSIVE' ? 'normal (above typical)' : assessment.classification.toLowerCase()) +
        deviation + ' against ' + assessment.sampleCount + ' prior days.' :
        'Spike assessment: Insufficient history (' + assessment.sampleCount + ' reference samples).') +
      (assessment.stale ? ' Price data is stale.' : '');
  }
};

WattlyForecast.refresh = async function () {
  const results = await Promise.all([
    Wattly.api('GET', '/api/v1/forecast/latest'), Wattly.api('GET', '/api/v1/forecast/current-assessment'),
    Wattly.api('GET', '/api/v1/forecast/today')
  ]);
  if (!results[0].ok) {
    document.getElementById('forecast-status').textContent = 'Cannot load the forecast. Retrying in one minute.';
    document.getElementById('forecast-metadata').textContent = '';
    document.getElementById('forecast-table').hidden = true;
    document.getElementById('forecast-points').replaceChildren();
    document.getElementById('current-assessment').textContent = 'Current price unavailable.';
    const chart=document.getElementById('live-price-chart'); if (chart) chart.replaceChildren();
    return;
  }
  WattlyForecast.render(results[0].data, results[1].ok ? results[1].data : null);
  const chart=document.getElementById('live-price-chart');
  if (chart && typeof WattlyPriceChart !== 'undefined') {
    const actuals=results[2].ok && Array.isArray(results[2].data.prices) ? results[2].data.prices : [];
    const view=results[0].data;
    WattlyPriceChart.draw(chart,[
      {name:'Actual',color:'#176346',points:actuals.map(p=>({time:p.periodStart,value:Number(p.usep)}))},
      {name:view.stale?'Stale forecast':'Forecast',color:'#2a78d6',points:view.available?view.run.points.map(p=>({time:p.targetPeriod,value:Number(p.predictedUsep),spike:p.spikeFlag===true})):[]}
    ]);
    const rows=document.getElementById('actual-points');
    rows.replaceChildren();
    actuals.forEach(p=>{ const tr=document.createElement('tr');
      [new Date(p.periodStart).toLocaleString('en-SG',{timeZone:'Asia/Singapore'}),Number(p.usep).toFixed(2)].forEach(v=>{
        const td=document.createElement('td'); td.textContent=v; tr.appendChild(td); }); rows.appendChild(tr); });
    document.getElementById('actual-coverage').textContent=results[2].ok ?
      (results[2].data.observedPeriods || 0)+' of '+(results[2].data.expectedPeriods || 0)+' expected periods received today. Gaps are not interpolated.' : 'Today’s actuals unavailable.';
  }
};

Wattly.keepHouseholdInLinks();
WattlyForecast.refresh();
setInterval(WattlyForecast.refresh, 60000);
