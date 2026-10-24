(function () {
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

  function render(data) {
    document.getElementById('households-monitored').textContent = data.householdsMonitored;
    document.getElementById('price-linked').textContent = data.priceLinkedHouseholds;
    document.getElementById('savings-total').textContent = money(data.totalSavingsThisMonthSgd);
    document.getElementById('forecast-mae').textContent = data.forecastAccuracyAvailable
      ? Number(data.forecastMaeSgdPerMwh).toFixed(2) + ' SGD/MWh' : 'Unavailable';
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
  }

  fetch('/api/v1/admin/overview', { cache: 'no-store' })
    .then(response => {
      if (!response.ok) throw new Error(response.status === 403 ? 'Admin access required.' : 'Overview unavailable.');
      return response.json();
    })
    .then(data => {
      render(data);
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
})();
