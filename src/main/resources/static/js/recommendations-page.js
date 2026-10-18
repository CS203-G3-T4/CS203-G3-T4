(function () {
  async function loadHousehold() {
    const result = await Wattly.api('GET', '/api/v1/households/' + Wattly.householdId());
    if (!result.ok) {
      if (result.status === 404 && await Wattly.leaveMissingHousehold()) return;
      const banner = document.getElementById('page-banner');
      banner.className = 'banner error';
      banner.textContent = (result.data && result.data.detail) || 'Could not load this household.';
      return;
    }
    Wattly.renderHouseholdCard(result.data);
    if (!result.data.exposedToWholesalePrice) {
      const banner = document.getElementById('page-banner');
      banner.className = 'banner info';
      banner.textContent = 'This household has a fixed-rate plan, so shifting use will not lower its electricity rate.';
    }
  }

  const button = document.getElementById('refresh-recommendations');
  async function refresh() {
    button.disabled = true;
    await WattlyRecommendations.load();
    button.disabled = false;
  }
  button.addEventListener('click', refresh);
  Wattly.keepHouseholdInLinks();
  Wattly.loadHouseholdSwitcher();
  WattlyRecommendations.init();
  loadHousehold();
  refresh();
})();
