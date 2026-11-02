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

  let priceLoadVersion = 0;
  async function loadPrice() {
    const version = ++priceLoadVersion;
    const assessmentNode = document.getElementById('price-assessment');
    assessmentNode.textContent = 'Checking typical prices…';
    const priceRequest = Wattly.api('GET', '/api/v1/prices/current').catch(() => ({ok: false, status: 0}));
    const assessmentRequest = Wattly.api('GET', '/api/v1/forecast/current-assessment').catch(() => ({ok: false}));
    const result = await priceRequest;
    if (version !== priceLoadVersion) return;
    const status = document.getElementById('price-status');
    const staleBanner = document.getElementById('price-stale-banner');
    staleBanner.hidden = true;
    if (!result.ok || !result.data || !result.data.marketPrice) {
      document.getElementById('price-value').textContent = '—';
      document.getElementById('market-price').textContent = '—';
      document.getElementById('forecast-demand').textContent = '—';
      document.getElementById('price-updated').textContent = '—';
      document.getElementById('interval-start').textContent = '—';
      setStatus(status, result.status === 503 ? 'Unavailable' : 'Could not load', 'unavailable');
      assessmentNode.textContent = WattlyAssessment.describe(result.status === 503
        ? {available: false, reason: 'NO_PRICE'} : null);
      return;
    }
    const data = result.data;
    const price = data.marketPrice;
    document.getElementById('price-value').textContent = Number(data.centsPerKwh).toFixed(1);
    document.getElementById('market-price').textContent = Number(price.price).toFixed(2) + ' SGD/MWh';
    document.getElementById('forecast-demand').textContent = price.forecastDemandMw === null
      ? '—' : Number(price.forecastDemandMw).toLocaleString('en-SG') + ' MW';
    document.getElementById('price-updated').textContent = formatTime(price.sourceUpdatedAt);
    document.getElementById('interval-start').textContent = formatTime(price.intervalStart);
    setStatus(status, data.stale ? 'Stale market data' : 'Live market data', data.stale ? 'stale' : '');
    staleBanner.hidden = !data.stale;
    if (data.stale) {
      assessmentNode.textContent = WattlyAssessment.describe({available: false, stale: true, reason: 'STALE_PRICE'});
      return;
    }
    // A slow assessment must not hold up current prices or disable Refresh.
    // Ignore responses from an older refresh, and match the displayed snapshot.
    assessmentRequest.then(assessmentResult => {
      if (version !== priceLoadVersion) return;
      let assessment = assessmentResult && assessmentResult.ok ? assessmentResult.data : null;
      if (assessment && assessment.available && !WattlyAssessment.matches(assessment, price)) {
        assessment = {available: false, reason: 'PRICE_MISMATCH'};
      }
      assessmentNode.textContent = WattlyAssessment.describe(assessment);
    });
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
        Number(data.heatThresholdC).toFixed(0) +
        '°C or above. Evening prices may rise, so consider running heavy appliances earlier or later.';
      hint.appendChild(message);
    }
  }

  // CSDT4-42: the same projection and over-budget banner as the Spending page.
  async function loadBudget() {
    const status = await WattlyBudget.load();
    const summary = document.getElementById('budget-summary');
    if (!status) {
      summary.hidden = true;
      WattlyBudget.showBanner(document.getElementById('budget-banner'), null);
      return;
    }
    WattlyBudget.showBanner(document.getElementById('budget-banner'), status);
    document.getElementById('budget-summary-text').textContent = WattlyBudget.summaryText(status) + '.';
    summary.hidden = false;
  }

  async function refresh() {
    const button = document.getElementById('refresh-button');
    button.disabled = true;
    await Promise.all([loadPrice(), loadWeather(), WattlyRecommendations.load(), loadBudget()]);
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
  WattlyRecommendations.init();
  Wattly.loadHouseholdSwitcher();
  loadHousehold();
  refresh();
  window.setInterval(loadPrice, 60000);
})();