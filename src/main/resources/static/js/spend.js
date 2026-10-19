// CSDT4-41: "Spent so far this month" on the Spending page, from
// GET /api/v1/households/{id}/spend/month-to-date: every finished half-hour's modelled use x that
// half-hour's market price plus the bill's other charges and 9% GST (price-linked), or x the
// contracted rate (fixed). Shows how it was worked out and where each charge comes from.
// Needs wattly.js first. Pure helpers are on WattlySpend so they can be tested with Node.

const WattlySpend = {};

(function () {
  WattlySpend.money = function (value) {
    return value === null || value === undefined ? '—' : 'S$' + Number(value).toFixed(2);
  };

  WattlySpend.url = function (householdId) {
    return '/api/v1/households/' + householdId + '/spend/month-to-date';
  };

  // A charge's rate as a resident would read it: 6.10¢/kWh, S$2.18/month, 9%.
  WattlySpend.rateText = function (charge) {
    const rate = Number(charge.rate);
    if (charge.unit === 'SGD_PER_KWH') return (rate * 100).toFixed(2) + '¢/kWh';
    if (charge.unit === 'SGD_PER_MONTH') return 'S$' + rate.toFixed(2) + '/month';
    if (charge.unit === 'PERCENT') return rate.toFixed(rate % 1 === 0 ? 0 : 2) + '%';
    return String(charge.rate);
  };

  // The line under the figure: what it includes and how much of the month it covers.
  WattlySpend.detailText = function (data) {
    if (data.planType === 'FIXED') {
      return 'At your contracted ' + Number(data.fixedRateCentsPerKwh) + '¢/kWh · ' +
        Number(data.kwh).toFixed(1) + ' kWh so far (modelled)';
    }
    const coverage = data.halfHoursWithoutPrice > 0
      ? data.halfHoursCounted + ' of ' + data.halfHoursSoFar + ' half-hours priced'
      : 'all ' + data.halfHoursSoFar + ' half-hours priced';
    return 'Market price + bill charges + GST · ' + coverage;
  };

  WattlySpend.introText = function (data) {
    return data.planType === 'FIXED'
      ? 'Every finished half-hour this month: your home’s modelled use × your contracted rate.'
      : 'Every finished half-hour this month: your home’s modelled use × that half-hour’s market price, plus ' +
        'the other charges on the bill and GST.';
  };

  // Rows for "How this is worked out": market price, each charge with its source, then the total.
  WattlySpend.breakdownHtml = function (data) {
    const rows = [];
    rows.push('<tr><th scope="row">' + (data.planType === 'FIXED' ? 'Your contracted rate' : 'Market price (USEP)') +
      '</th><td>' + (data.planType === 'FIXED' ? Wattly.escape(Number(data.fixedRateCentsPerKwh) + '¢/kWh')
        : 'Each half-hour’s price') + '</td><td class="num">' + Wattly.escape(WattlySpend.money(data.energySgd)) +
      '</td><td>' + (data.planType === 'FIXED' ? 'Household Settings' : 'Wattly’s saved market prices (EMC)') +
      '</td></tr>');
    (data.charges || []).forEach(function (charge) {
      const source = charge.sourceUrl
        ? '<a href="' + Wattly.escape(charge.sourceUrl) + '" target="_blank" rel="noopener">' +
          Wattly.escape(charge.source) + '</a>'
        : Wattly.escape(charge.source);
      rows.push('<tr><th scope="row">' + Wattly.escape(charge.charge) + '</th><td>' +
        Wattly.escape(WattlySpend.rateText(charge)) + '</td><td class="num">' +
        Wattly.escape(WattlySpend.money(charge.amountSgd)) + '</td><td>' + source + '</td></tr>');
    });
    rows.push('<tr class="total"><th scope="row">Spent so far</th><td></td><td class="num">' +
      Wattly.escape(WattlySpend.money(data.totalSgd)) + '</td><td></td></tr>');
    return rows.join('');
  };

  WattlySpend.render = function (data) {
    document.getElementById('spent-to-date').textContent = WattlySpend.money(data.totalSgd);
    document.getElementById('energy-to-date').textContent = WattlySpend.detailText(data);
    document.getElementById('spend-breakdown-intro').textContent = WattlySpend.introText(data);
    document.getElementById('spend-breakdown-rows').innerHTML = WattlySpend.breakdownHtml(data);
    document.getElementById('spend-notes').innerHTML = (data.notes || []).map(function (note) {
      return '<li>' + Wattly.escape(note) + '</li>';
    }).join('');
    document.getElementById('spend-breakdown').hidden = false;
  };

  WattlySpend.load = async function () {
    const result = await Wattly.api('GET', WattlySpend.url(Wattly.householdId()));
    if (!result.ok) {
      document.getElementById('spent-to-date').textContent = '—';
      document.getElementById('energy-to-date').textContent =
        (result.data && result.data.detail) || 'Could not work out spending so far.';
      document.getElementById('spend-breakdown').hidden = true;
      return;
    }
    WattlySpend.render(result.data);
  };

  if (typeof document !== 'undefined' && document.getElementById && document.getElementById('spend-breakdown')) {
    WattlySpend.load();
  }
})();
