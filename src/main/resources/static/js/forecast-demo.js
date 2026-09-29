// Offline report only; the shared forecast.js continues to read saved Spring results.
const WattlyDemo = {};
WattlyDemo.format = new Intl.DateTimeFormat('en-SG', {
  timeZone: 'Asia/Singapore', dateStyle: 'medium', timeStyle: 'short'
});
WattlyDemo.number = value => Number.isFinite(value) ? value.toFixed(2) : 'Unavailable';
WattlyDemo.text = (id, text) => { document.getElementById(id).textContent = text; };
WattlyDemo.row = function (parent, values) {
  const row = document.createElement('tr');
  values.forEach(value => { const cell = document.createElement('td'); cell.textContent = value; row.appendChild(cell); });
  parent.appendChild(row);
};
WattlyDemo.svg = function (tag, attributes, text) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  Object.entries(attributes).forEach(([key, value]) => node.setAttribute(key, value));
  if (text !== undefined) node.textContent = text;
  document.getElementById('demo-chart').appendChild(node);
  return node;
};
WattlyDemo.selectOrigin = function () {
  const report = WattlyDemo.report;
  const origin = report.origins[Number(document.getElementById('demo-origin').value)];
  if (!origin) return;
  const body = document.getElementById('demo-points'); body.replaceChildren();
  origin.points.forEach(p => WattlyDemo.row(body, [WattlyDemo.format.format(new Date(p.targetPeriod)),
    p.actual, p.AI, p.B1, p.B2, p.B3].map((v, i) => i ? WattlyDemo.number(v) : v)));
  const svg = document.getElementById('demo-chart'); svg.replaceChildren();
  WattlyDemo.svg('title', { id: 'demo-chart-title' }, 'Actual prices and experimental 24-interval forecasts');
  WattlyDemo.svg('desc', { id: 'demo-chart-description' }, 'AI, B1 and B2 use the selected historical origin. Exact values are available in the table below.');
  const start = Date.parse(report.protocol.testStart), end = Date.parse(report.protocol.testEnd);
  const series = [{ key: 'actual', points: report.actuals, color: '#52514e', dash: '' },
    { key: 'AI', points: origin.points, color: '#2a78d6', dash: '' },
    { key: 'B1', points: origin.points, color: '#148a5f', dash: '8 4' },
    { key: 'B2', points: origin.points, color: '#9a5f00', dash: '2 4' }];
  const values = series.flatMap(s => s.points.map(p => p[s.key]).filter(Number.isFinite));
  const low = Math.min(...values), high = Math.max(...values), pad = Math.max(1, (high - low) * 0.08);
  const bottom = low - pad, top = high + pad;
  const x = t => 72 + (Date.parse(t) - start) / (end - start) * 900;
  const y = v => 290 - (v - bottom) / (top - bottom) * 260;
  for (let i = 0; i <= 4; i++) {
    const value = bottom + (top - bottom) * i / 4;
    WattlyDemo.svg('line', { x1: 72, x2: 972, y1: y(value), y2: y(value), stroke: '#e1e0d9' });
    WattlyDemo.svg('text', { x: 62, y: y(value) + 4, 'text-anchor': 'end' }, value.toFixed(0));
    WattlyDemo.svg('text', { x: 72 + 900 * i / 4, y: 315, 'text-anchor': i === 4 ? 'end' : 'start' }, String(i * 6).padStart(2, '0') + ':00');
  }
  WattlyDemo.svg('text', { x: 72, y: 18 }, 'SGD/MWh');
  series.forEach(s => {
    let segment = [];
    const draw = () => {
      if (segment.length) WattlyDemo.svg('polyline', { points: segment.join(' '), fill: 'none', stroke: s.color,
        'stroke-width': s.key === 'AI' ? 3 : 2, 'stroke-dasharray': s.dash });
      segment = [];
    };
    s.points.forEach(p => { if (Number.isFinite(p[s.key])) segment.push(x(p.targetPeriod) + ',' + y(p[s.key])); else draw(); });
    draw();
  });
};
WattlyDemo.render = function (report) {
  if (report.schemaVersion !== 1 || report.actionable !== false || report.productionApproved !== false || !report.origins.length) {
    throw new Error('Invalid experimental report');
  }
  WattlyDemo.report = report;
  const selected = report.protocol.validationSelectedBaseline;
  const ai = report.test.table.AI.mae, baseline = report.test.table[selected].mae;
  const delta = ai - baseline;
  WattlyDemo.text('demo-status', report.label);
  WattlyDemo.text('demo-verdict', 'On this test day, AI has ' + WattlyDemo.number(Math.abs(delta)) +
    ' SGD/MWh ' + (delta > 0 ? 'more' : delta < 0 ? 'less' : 'difference in') + ' error than ' + selected + '. One day does not establish general accuracy.');
  WattlyDemo.text('demo-ai-mae', WattlyDemo.number(ai));
  WattlyDemo.text('demo-baseline-label', selected + ' baseline MAE');
  WattlyDemo.text('demo-baseline-mae', WattlyDemo.number(baseline));
  WattlyDemo.text('demo-pairs', report.test.commonPairs);
  WattlyDemo.text('demo-origins', report.origins.length + ' origins · 24 horizons · one day');
  const p = report.protocol, date = value => WattlyDemo.format.format(new Date(value));
  WattlyDemo.text('demo-dates', 'Train: ' + date(p.trainingStart) + ' → ' + date(p.trainingCutoff) +
    '. Validate: ' + date(p.validationStart) + ' → ' + date(p.validationEnd) +
    '. Test: ' + date(p.testStart) + ' → ' + date(p.testEnd) + ' SGT. End times are exclusive.');
  const select = document.getElementById('demo-origin'); select.replaceChildren();
  report.origins.forEach((origin, index) => {
    const option = document.createElement('option'); option.value = index; option.textContent = date(origin.asOf); select.appendChild(option);
  });
  select.value = '0';
  const scores = document.getElementById('demo-scores'); scores.replaceChildren();
  ['AI', 'B1', 'B2', 'B3'].forEach(method => {
    const coverage = report.allMethods.table[method];
    const excluded = Object.values(report.exclusions[method]).reduce((a, b) => a + b, 0);
    WattlyDemo.row(scores, [method + (method === selected ? ' · validation choice' : ''),
      WattlyDemo.number(report.test.table[method]?.mae), coverage.eligibleOrigins, coverage.eligiblePairs, excluded]);
  });
  const q = report.quality;
  WattlyDemo.text('demo-quality', q.periodCount + ' periods from ' + date(q.from) + ' to ' + date(q.to) +
    ' SGT; ' + q.revisionRows + ' revisions; ' + q.missingHalfHours + ' gaps; ' + q.parsingFailures.length +
    ' parsing failures. Mapping evidence: ' + (p.mappingEvidenceId || 'not configured') + '.');
  WattlyDemo.text('demo-exclusions', Object.entries(report.exclusions).map(([method, reasons]) => method + ': ' +
    Object.entries(reasons).map(([reason, count]) => count + ' ' + reason.toLowerCase().replaceAll('_', ' ')).join('; ')).join('. ') +
    '. All-method common pairs (including B3): ' + report.allMethods.commonPairs + '.');
  const limits = document.getElementById('demo-limitations'); limits.replaceChildren();
  report.limitations.forEach(text => { const li = document.createElement('li'); li.textContent = text; limits.appendChild(li); });
  document.getElementById('demo-results').hidden = false;
  WattlyDemo.selectOrigin();
};
WattlyDemo.live = async function () {
  const response = await Wattly.api('GET', '/api/v1/market-prices/latest');
  if (!response.ok || !Number.isFinite(response.data?.price)) {
    WattlyDemo.text('demo-live-price', 'Current market price unavailable.');
    WattlyDemo.text('demo-live-time', ''); return;
  }
  const p = response.data;
  WattlyDemo.text('demo-live-price', p.freshness + ' · ' + WattlyDemo.number(p.price) + ' SGD/MWh · ' + WattlyDemo.number(p.price / 10) + ' cents/kWh');
  WattlyDemo.text('demo-live-time', 'Period: ' + WattlyDemo.format.format(new Date(p.intervalStart)) +
    ' · published: ' + WattlyDemo.format.format(new Date(p.sourceUpdatedAt)) + ' SGT');
};
WattlyDemo.load = async function () {
  const result = await Wattly.api('GET', '/forecast-demo.json');
  try { if (!result.ok) throw new Error('Missing report'); WattlyDemo.render(result.data); }
  catch (_) { WattlyDemo.text('demo-status', 'Offline experiment unavailable. Start the presentation demo with its saved report.'); }
};
document.getElementById('demo-origin').addEventListener('change', WattlyDemo.selectOrigin);
document.getElementById('demo-refresh').addEventListener('click', () => { WattlyDemo.live(); WattlyForecast.refresh(); });
WattlyDemo.load(); WattlyDemo.live();
setInterval(WattlyDemo.live, 60000);
