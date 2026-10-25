// CSDT4-55: admin platform overview, filled from GET /api/v1/admin/overview.
// CSDT4-49: the platform totals include the simulated households, so each tile says how much of
// it is simulated and a note under the tiles says so in words. Pure helpers are on
// WattlyAdminOverview so they can be tested with Node.
const WattlyAdminOverview = {};

(function () {
  const Overview = WattlyAdminOverview;
  const money = value => value === null || value === undefined ? '—' : 'S$' + Number(value).toFixed(2);
  const escape = value => String(value).replace(/[&<>"']/g, character => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[character]));
  const statusText = {
    ON_TRACK: 'On track',
    OVER_PACE: 'Over pace',
    NO_BUDGET: 'No budget',
    NOT_EXPOSED: 'Not exposed'
  };

  Overview.money = money;

  // How much of each total comes from simulated households, from the household list.
  Overview.simulatedShare = function (data) {
    let households = 0;
    let priceLinked = 0;
    let savings = 0;
    (data.households || []).forEach(household => {
      if (!household.simulated) return;
      households++;
      if (household.status !== 'NOT_EXPOSED') priceLinked++;
      savings += Number(household.savingsThisMonthSgd) || 0;
    });
    return { households: households, priceLinked: priceLinked, savingsSgd: savings };
  };

  // The small line under a tile: "100 simulated · 6 real".
  Overview.splitText = function (total, simulated) {
    return simulated + ' simulated · ' + (Number(total) - simulated) + ' real';
  };

  // The note under the tiles, or '' when there are no simulated households.
  Overview.simulatedNote = function (share) {
    if (share.households === 0) return '';
    return 'These totals include ' + share.households + ' simulated household' +
      (share.households === 1 ? '' : 's') + ', made up for the demo from our home types. ' +
      'They are not real homes and are marked "(Simulated)" in the table below.';
  };

  function setDetail(id, text) {
    const element = document.getElementById(id);
    element.textContent = text;
    element.hidden = !text;
  }

  Overview.render = function (data) {
    const share = Overview.simulatedShare(data);
    const hasSimulated = share.households > 0;
    document.getElementById('households-monitored').textContent = data.householdsMonitored;
    document.getElementById('price-linked').textContent = data.priceLinkedHouseholds;
    document.getElementById('savings-total').textContent = money(data.totalSavingsThisMonthSgd);
    document.getElementById('forecast-mae').textContent = data.forecastAccuracyAvailable
      ? Number(data.forecastMaeSgdPerMwh).toFixed(2) + ' SGD/MWh' : 'Unavailable';
    setDetail('households-split', hasSimulated ? Overview.splitText(data.householdsMonitored, share.households) : '');
    setDetail('price-linked-split', hasSimulated ? Overview.splitText(data.priceLinkedHouseholds, share.priceLinked) : '');
    setDetail('savings-split', hasSimulated ? money(share.savingsSgd) + ' from simulated households' : '');
    setDetail('simulated-note', Overview.simulatedNote(share));

    const rows = document.getElementById('household-rows');
    rows.replaceChildren();
    (data.households || []).forEach(household => {
      const row = document.createElement('tr');
      row.innerHTML = '<th scope="row">' + escape(household.name) +
        (household.simulated ? ' <span class="simulated">(Simulated)</span>' : '') + '</th>' +
        '<td>' + escape(household.plan) + '</td>' +
        '<td class="num">' + money(household.savingsThisMonthSgd) + '</td>' +
        '<td class="status ' + escape(household.status) + '">' +
        escape(statusText[household.status] || household.status) + '</td>';
      rows.appendChild(row);
    });
  };

  Overview.load = function () {
    return fetch('/api/v1/admin/overview', { cache: 'no-store' })
      .then(response => {
        if (!response.ok) throw new Error(response.status === 403 ? 'Admin access required.' : 'Overview unavailable.');
        return response.json();
      })
      .then(data => {
        Overview.render(data);
        document.getElementById('status').textContent = 'Updated ' + new Date(data.asOf).toLocaleString('en-SG', {
          timeZone: 'Asia/Singapore'
        });
      })
      .catch(error => {
        document.getElementById('status').textContent = '';
        const banner = document.getElementById('error');
        banner.hidden = false;
        banner.textContent = error.message;
      });
  };

  if (typeof document !== 'undefined' && document.getElementById && document.getElementById('household-rows')) {
    Overview.load();
  }
})();
