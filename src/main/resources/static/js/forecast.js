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
    metadata.textContent = (run.modelType === 'AI' ? 'AI' : 'Baseline ' + run.selectedModel) +
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
    current.textContent = 'Current price: ' + (Number(assessment.actualPrice) / 10).toFixed(2) + ' cents/kWh. ' +
      (assessment.available ? assessment.classification.toLowerCase() + ' against ' + assessment.sampleCount + ' prior days.' :
        'Spike assessment: Insufficient history (' + assessment.sampleCount + ' reference samples).') +
      (assessment.stale ? ' Price data is stale.' : '');
  }
};

WattlyForecast.refresh = async function () {
  const results = await Promise.all([
    Wattly.api('GET', '/api/v1/forecast/latest'), Wattly.api('GET', '/api/v1/forecast/current-assessment')
  ]);
  if (!results[0].ok) {
    document.getElementById('forecast-status').textContent = 'Cannot load the forecast. Retrying in one minute.';
    document.getElementById('forecast-metadata').textContent = '';
    document.getElementById('forecast-table').hidden = true;
    document.getElementById('forecast-points').replaceChildren();
    document.getElementById('current-assessment').textContent = 'Current price unavailable.';
    return;
  }
  WattlyForecast.render(results[0].data, results[1].ok ? results[1].data : null);
};

Wattly.keepHouseholdInLinks();
WattlyForecast.refresh();
setInterval(WattlyForecast.refresh, 60000);
