(function () {
  const dateTime = new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore', dateStyle: 'medium', timeStyle: 'short'
  });
  const today = new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore', weekday: 'long', day: 'numeric', month: 'long', year: 'numeric'
  });

  function setStatus(element, text, state) {
    element.textContent = text;
    element.className = 'status-pill ' + state;
  }

  function formatTime(value) {
    if (!value) return '—';
    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? '—' : dateTime.format(parsed);
  }

  function showBanner(text, kind) {
    const banner = document.getElementById('page-banner');
    banner.replaceChildren();
    if (text) {
      const message = document.createElement('div');
      message.className = 'banner ' + kind;
      message.textContent = text;
      banner.appendChild(message);
    }
  }

  async function loadPrice() {
    const result = await Wattly.api('GET', '/api/v1/prices/current');
    const status = document.getElementById('price-status');
    const staleBanner = document.getElementById('price-stale-banner');
    staleBanner.hidden = true;
    if (!result.ok) {
      document.getElementById('price-value').textContent = '—';
      document.getElementById('market-price').textContent = '—';
      document.getElementById('forecast-demand').textContent = '—';
      document.getElementById('price-updated').textContent = '—';
      document.getElementById('interval-start').textContent = '—';
      setStatus(status, result.status === 503 ? 'Unavailable' : 'Could not load', 'unavailable');
      return;
    }

    const data = result.data;
    const price = data.marketPrice || {};
    document.getElementById('price-value').textContent = Number(data.centsPerKwh).toFixed(1);
    document.getElementById('market-price').textContent = Number(price.price).toFixed(2) + ' SGD/MWh';
    document.getElementById('forecast-demand').textContent = price.forecastDemandMw === null
      ? '—' : Number(price.forecastDemandMw).toLocaleString('en-SG') + ' MW';
    document.getElementById('price-updated').textContent = formatTime(price.sourceUpdatedAt);
    document.getElementById('interval-start').textContent = formatTime(price.intervalStart);
    setStatus(status, data.stale ? 'Stale market data' : 'Live market data', data.stale ? 'stale' : '');
    staleBanner.hidden = !data.stale;
  }

  async function loadWeather() {
    const result = await Wattly.api('GET', '/api/v1/weather/today');
    const status = document.getElementById('weather-status');
    const hint = document.getElementById('heat-hint');
    hint.replaceChildren();
    if (!result.ok || !result.data.available) {
      document.getElementById('weather-high').textContent = '—';
      document.getElementById('weather-low').textContent = '—';
      document.getElementById('weather-description').textContent = "Today's forecast is not available.";
      setStatus(status, result.ok ? 'Unavailable' : 'Could not load', 'unavailable');
      return;
    }

    const data = result.data;
    const forecast = data.forecast;
    document.getElementById('weather-high').textContent = Number(forecast.temperatureHighC).toFixed(0) + '°';
    document.getElementById('weather-low').textContent = Number(forecast.temperatureLowC).toFixed(0) + '°';
    document.getElementById('weather-description').textContent = forecast.forecast;
    setStatus(status, data.stale ? 'Forecast may be outdated' : 'Updated ' + formatTime(forecast.updatedAt),
      data.stale ? 'stale' : '');
    if (data.heatHint) {
      const message = document.createElement('div');
      message.className = 'banner warn';
      message.textContent = 'Hot day expected: the forecast high reaches ' +
        Number(data.heatThresholdC).toFixed(0) + '°C or above.';
      hint.appendChild(message);
    }
  }

  async function refresh() {
    const button = document.getElementById('refresh-button');
    button.disabled = true;
    await Promise.all([loadPrice(), loadWeather()]);
    document.getElementById('last-refreshed').textContent = 'Checked ' + dateTime.format(new Date());
    button.disabled = false;
  }

  async function loadHousehold() {
    const result = await Wattly.api('GET', '/api/v1/households/' + Wattly.householdId());
    if (!result.ok) {
      if (result.status === 404 && await Wattly.leaveMissingHousehold()) return;
      showBanner((result.data && result.data.detail) || 'Could not load this household.', 'error');
      return;
    }
    Wattly.renderHouseholdCard(result.data);
    document.getElementById('welcome-title').textContent = result.data.name + ' dashboard';
    if (!result.data.exposedToWholesalePrice) {
      showBanner('This household has a fixed-rate plan and is not exposed to wholesale price changes.', 'info');
    }
  }

  document.getElementById('today-label').textContent = today.format(new Date());
  document.getElementById('refresh-button').addEventListener('click', refresh);
  Wattly.keepHouseholdInLinks();
  Wattly.loadHouseholdSwitcher();
  loadHousehold();
  refresh();
  window.setInterval(loadPrice, 60000);
})();