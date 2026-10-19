// Recommendations page (CSDT4-40): tabs for Active, Accepted, Dismissed and History, "Remind me
// later" until a chosen time, and a mini forecast chart on each open suggestion. The chart uses the
// forecast the suggestion was built from: F3's (/api/v1/forecast/latest) or the demo forecast.
// Needs wattly.js and recommendations.js first. Every figure comes from the API.

const WattlyRecommendationsPage = {};

(function () {
  const Page = WattlyRecommendationsPage;
  Page.TABS = ['ACTIVE', 'ACCEPTED', 'DISMISSED', 'HISTORY'];

  Page.tabUrl = function (householdId, tab) {
    return WattlyRecommendations.listUrl(householdId) + '?status=' + tab;
  };

  // Active tab: open suggestions, and the ones "Remind me later" is still hiding.
  Page.splitActive = function (recs, nowMs) {
    const snoozed = function (rec) { return rec.remindAt && Date.parse(rec.remindAt) > nowMs; };
    return { open: recs.filter(function (rec) { return !snoozed(rec); }), snoozed: recs.filter(snoozed) };
  };

  Page.tabNote = function (tab, count) {
    const label = { ACCEPTED: 'accepted', DISMISSED: 'dismissed', HISTORY: 'suggestions' }[tab];
    if (count === 0) {
      return { ACCEPTED: 'Nothing accepted', DISMISSED: 'Nothing dismissed', HISTORY: 'No suggestions' }[tab] +
        ' in the last 30 days.';
    }
    return count + ' ' + (tab === 'HISTORY' && count === 1 ? 'suggestion' : label) +
      ' in the last 30 days, newest first.';
  };

  // Chart points by forecast source, fetched only for the sources the suggestions used.
  Page.forecastPoints = async function (recs) {
    const points = {};
    const uses = function (source) { return recs.some(function (rec) { return rec.forecastSource === source; }); };
    if (uses('DEMO_FORECAST')) {
      const demo = await Wattly.api('GET', '/api/v1/recommendations/demo-forecast');
      points.DEMO_FORECAST = demo.ok ? demo.data.points : [];
    }
    if (uses('F3_FORECAST')) {
      const f3 = await Wattly.api('GET', '/api/v1/forecast/latest');
      points.F3_FORECAST = f3.ok && f3.data && f3.data.run ? f3.data.run.points
        .filter(function (p) { return p.predictedUsep !== null && p.predictedUsep !== undefined; })
        .map(function (p) { return { periodStart: p.targetPeriod, usepSgdPerMwh: p.predictedUsep }; }) : [];
    }
    return points;
  };

  let current = 'ACTIVE';

  function el(id) { return document.getElementById(id); }

  function selectTab(tab) {
    Page.TABS.forEach(function (name) {
      const button = el('tab-' + name.toLowerCase());
      const selected = name === tab;
      button.setAttribute('aria-selected', String(selected));
      button.tabIndex = selected ? 0 : -1;
    });
    el('rec-tabpanel').setAttribute('aria-labelledby', 'tab-' + tab.toLowerCase());
  }

  Page.loadTab = async function (tab) {
    current = tab;
    selectTab(tab);
    const list = el('recommendation-list');
    const note = el('recommendation-note');
    const snoozedBlock = el('snoozed-block');
    const result = await Wattly.api('GET', Page.tabUrl(Wattly.householdId(), tab));
    if (current !== tab) return; // another tab was picked while this one loaded
    if (!result.ok) {
      list.innerHTML = '';
      snoozedBlock.hidden = true;
      note.textContent = (result.data && result.data.detail) || 'Could not load suggestions.';
      return;
    }
    const data = result.data;
    const recs = data.recommendations || [];
    const nowMs = Date.now();
    if (tab !== 'ACTIVE') {
      el('recommendation-source').hidden = true;
      snoozedBlock.hidden = true;
      note.textContent = Page.tabNote(tab, recs.length);
      list.innerHTML = recs.map(function (rec) {
        return WattlyRecommendations.itemHtml(rec, { nowMs: nowMs, readOnly: true });
      }).join('');
      return;
    }
    const split = Page.splitActive(recs, nowMs);
    const points = await Page.forecastPoints(recs);
    if (current !== tab) return;
    el('recommendation-source').hidden = !data.demoForecast;
    WattlyRecommendations.showTotals(split.open);
    note.textContent = WattlyRecommendations.noteText(data, split.open.length);
    list.innerHTML = split.open.map(function (rec) {
      return WattlyRecommendations.itemHtml(rec, { nowMs: nowMs, points: points[rec.forecastSource] || [] });
    }).join('');
    snoozedBlock.hidden = split.snoozed.length === 0;
    el('recommendation-snoozed').innerHTML = split.snoozed.map(function (rec) {
      return WattlyRecommendations.itemHtml(rec, { nowMs: nowMs, readOnly: true });
    }).join('');
  };

  function tabFromHash() {
    const wanted = (window.location.hash || '').replace('#', '').toUpperCase();
    return Page.TABS.indexOf(wanted) >= 0 ? wanted : 'ACTIVE';
  }

  async function loadHousehold() {
    const result = await Wattly.api('GET', '/api/v1/households/' + Wattly.householdId());
    if (!result.ok) {
      if (result.status === 404 && await Wattly.leaveMissingHousehold()) return;
      const banner = el('page-banner');
      banner.className = 'banner error';
      banner.textContent = (result.data && result.data.detail) || 'Could not load this household.';
      return;
    }
    Wattly.renderHouseholdCard(result.data);
    if (!result.data.exposedToWholesalePrice) {
      const banner = el('page-banner');
      banner.className = 'banner info';
      banner.textContent = 'This household has a fixed-rate plan, so shifting use will not lower its electricity rate.';
    }
  }

  Page.start = function () {
    const tablist = el('rec-tabs');
    tablist.addEventListener('click', function (event) {
      const button = event.target.closest('[role="tab"]');
      if (!button) return;
      history.replaceState(null, '', '#' + button.dataset.tab.toLowerCase());
      Page.loadTab(button.dataset.tab);
    });
    tablist.addEventListener('keydown', function (event) {
      if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return;
      const step = event.key === 'ArrowRight' ? 1 : Page.TABS.length - 1;
      const next = Page.TABS[(Page.TABS.indexOf(current) + step) % Page.TABS.length];
      el('tab-' + next.toLowerCase()).focus();
      el('tab-' + next.toLowerCase()).click();
    });
    // A link to #history (or the back button) on an open page switches tab too.
    window.addEventListener('hashchange', function () { Page.loadTab(tabFromHash()); });
    const refresh = el('refresh-recommendations');
    refresh.addEventListener('click', async function () {
      refresh.disabled = true;
      await Page.loadTab(current);
      refresh.disabled = false;
    });
    Wattly.keepHouseholdInLinks();
    Wattly.loadHouseholdSwitcher();
    WattlyRecommendations.init('recommendation-list');
    loadHousehold();
    // The header figures always describe the open suggestions, whichever tab is shown first.
    if (tabFromHash() !== 'ACTIVE') {
      Wattly.api('GET', Page.tabUrl(Wattly.householdId(), 'ACTIVE')).then(function (result) {
        if (result.ok) WattlyRecommendations.showTotals(Page.splitActive(result.data.recommendations || [], Date.now()).open);
      });
    }
    Page.loadTab(tabFromHash());
  };

  if (typeof document !== 'undefined' && document.getElementById && document.getElementById('rec-tabs')) {
    Page.start();
  }
})();
