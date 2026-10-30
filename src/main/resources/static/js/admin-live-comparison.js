const WattlyLiveComparison = {};
WattlyLiveComparison.refresh = async function () {
  const response = await fetch('/api/v1/admin/live-forecast-comparison', { cache: 'no-store' });
  const status = document.getElementById('trial-status');
  if (!response.ok) { status.textContent = response.status === 403 ? 'Admin access required.' : 'Live evidence is unavailable.'; return; }
  const data = await response.json(), trial = data.trial;
  if (!trial) { status.textContent = 'The live comparison has not been activated.'; return; }
  const formatTime = t => new Date(t).toLocaleString('en-SG', { timeZone: 'Asia/Singapore' });
  status.textContent = (data.captureActive ? 'Recording new forecasts' : 'New-forecast recording finished or disabled') + ' · ' + trial.model_version +
    ' · ' + formatTime(trial.started_at) + ' to ' + formatTime(trial.ends_at) + ' SGT';
  const number = n => n == null ? 'Pending' : Number(n).toFixed(4);
  const row = (parent, values) => { const tr = document.createElement('tr'); values.forEach(v => {
    const td = document.createElement('td'); td.textContent = v; tr.appendChild(td); }); parent.appendChild(tr); };
  const savings = document.getElementById('live-savings'), prices = document.getElementById('live-prices');
  savings.replaceChildren(); prices.replaceChildren();
  ['AI', 'B3'].forEach(method => {
    const s = data.methods[method], p = data.priceAccuracy[method];
    row(savings, [method, data.scoredOpportunities ? 'S$' + number(s.modelledSavingsSgd) : 'Pending', s.reschedules,
      s.harmfulReschedules, 'S$' + number(s.harmfulLossSgd), s.testedRanges ? s.coveredRanges + '/' + s.testedRanges : 'Not enough live evidence']);
    row(prices, [method, p.pairs, number(p.maeSgdPerMwh), number(p.rmseSgdPerMwh), number(p.ordinaryMaeSgdPerMwh),
      number(p.spikeMaeSgdPerMwh), p.falsePositiveTargetPairs, p.missedSpikeTargetPairs]);
  });
  document.getElementById('live-coverage').textContent = data.frozenOpportunities + ' frozen appliance-run opportunities · ' +
    data.scoredOpportunities + ' scored · ' + data.pendingOpportunities + ' waiting for completed runs or actual prices.';
  document.getElementById('live-exclusions').textContent = 'Forecast capture: ' + data.captureCoverage.map(x => x.reason + ': ' + x.count).join(' · ');
};
WattlyLiveComparison.refresh().catch(() => { document.getElementById('trial-status').textContent = 'Cannot load live evidence.'; });
