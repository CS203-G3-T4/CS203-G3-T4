// Recommendations page (CSDT4-40): tabs for Active, Accepted, Dismissed and History, "Remind me
// later" until a chosen time, and a mini forecast chart on each open suggestion. The chart uses the
// forecast the suggestion was built from: F3's (/api/v1/forecast/latest) or the demo forecast.
// CSDT4-53: the Accepted tab shows what accepted suggestions really saved this week and this month.
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

  Page.savingsUrl = function (householdId, period) {
    return '/api/v1/households/' + encodeURIComponent(householdId) + '/savings?period=' + period;
  };

  // A realised total: "$0.41", or "-$0.12" if the chosen times turned out dearer overall.
  Page.savedText = function (value) {
    const amount = Number(value);
    return (amount < 0 ? '-' : '') + WattlyRecommendations.money(Math.abs(amount));
  };

  // The notes under the totals, from the API (both periods, without repeats), plus how many wait.
  Page.realisedNote = function (week, month) {
    const notes = [];
    [week, month].forEach(function (data) {
      (data.notes || []).forEach(function (note) { if (notes.indexOf(note) < 0) notes.push(note); });
    });
    if (month.pendingCount > 0) {
      notes.push(month.pendingCount === 1 ? '1 accepted run this month is not counted yet.'
        : month.pendingCount + ' accepted runs this month are not counted yet.');
    }
    return notes.join(' ');
  };

  // Totals for this week (Monday to Sunday) and this month, at actual prices.
  Page.loadSavings = async function () {
    const householdId = Wattly.householdId();
    const results = await Promise.all([Wattly.api('GET', Page.savingsUrl(householdId, 'week')),
      Wattly.api('GET', Page.savingsUrl(householdId, 'month'))]);
    if (current !== 'ACCEPTED') return;
    const week = results[0];
    const month = results[1];
    el('realised-block').hidden = false;
    if (!week.ok || !month.ok) {
      el('saved-week').textContent = '—';
      el('saved-month').textContent = '—';
      const failed = !week.ok ? week : month;
      el('realised-note').textContent = (failed.data && failed.data.detail) || 'Could not load your realised savings.';
      return;
    }
    el('saved-week').textContent = Page.savedText(week.data.totalSavedSgd);
    el('saved-month').textContent = Page.savedText(month.data.totalSavedSgd);
    el('realised-note').textContent = Page.realisedNote(week.data, month.data);
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
  let loadVersion = 0;
  let automaticallyRefreshing = false;

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

  Page.loadTab = async function (tab, options) {
    const automatic = options && options.automatic;
    const mayApply = function () {
      return current === tab && version === loadVersion && (!automatic || !residentBusy());
    };
    const version = ++loadVersion;
    current = tab;
    selectTab(tab);
    const list = el('recommendation-list');
    const note = el('recommendation-note');
    const snoozedBlock = el('snoozed-block');
    el('realised-block').hidden = true;
    if (tab === 'ACCEPTED') Page.loadSavings();
    const result = await Wattly.api('GET', Page.tabUrl(Wattly.householdId(), tab));
    if (!mayApply()) return;
    if (!result.ok) {
      list.innerHTML = '';
      snoozedBlock.hidden = true;
      note.textContent = (result.data && result.data.detail) || 'Could not load suggestions.';
      return;
    }
    const data = result.data;
    if (Page.loadAlerts) Page.loadAlerts();
    const recs = WattlyRecommendations.withSavingsEvidence(data);
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
    if (!mayApply()) return;
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

  function residentBusy() {
    return document.hidden ||
      (document.activeElement && document.activeElement.matches('input, select, textarea')) ||
      !!document.querySelector('.rec-actions button:disabled') ||
      Array.from(document.querySelectorAll('.rec-actions input')).some(function (input) {
        return input.value !== input.defaultValue;
      });
  }

  // Check before requesting and before rendering, including after chart requests.
  Page.refreshAutomatically = async function () {
    if (!document.hidden && current !== 'ACTIVE') { await Page.loadAlerts(); return; }
    if (automaticallyRefreshing || residentBusy()) return;
    automaticallyRefreshing = true;
    try { await Page.loadTab('ACTIVE', { automatic: true }); }
    finally { automaticallyRefreshing = false; }
  };

  Page.startAutoRefresh = function () {
    if (Page.autoRefreshStarted) return;
    Page.autoRefreshStarted = true;
    window.setInterval(Page.refreshAutomatically, 60000);
    document.addEventListener('visibilitychange', Page.refreshAutomatically);
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

  let alertsVersion = 0;
  Page.loadAlerts = async function () {
    const node = el('savings-alerts');
    if (!node) return;
    const version = ++alertsVersion;
    const home = Wattly.householdId();
    const response = await Wattly.api('GET', '/api/v1/households/' + encodeURIComponent(home) + '/recommendation-alerts');
    if (version !== alertsVersion || home !== Wattly.householdId()) return;
    if (!response.ok) { node.hidden = true; node.replaceChildren(); return; }
    node.hidden = !response.data.length;
    node.replaceChildren();
    response.data.forEach(function (alert) {
      const item = document.createElement('div'), text = document.createElement('p'), button = document.createElement('button');
      item.className = 'banner info'; text.textContent = alert.message + ' Checked ' + WattlyRecommendations.localTime(alert.updated_at) + ' SGT.';
      button.type = 'button'; button.className = 'btn-ghost'; button.textContent = 'Got it';
      button.addEventListener('click', async function () {
        button.disabled = true;
        const result = await Wattly.api('POST', '/api/v1/households/' + encodeURIComponent(home) + '/recommendation-alerts/' + alert.id + '/acknowledge');
        if (result.ok) await Page.loadAlerts(); else button.disabled = false;
      });
      item.appendChild(text); item.appendChild(button); node.appendChild(item);
    });
  };

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
    Page.startAutoRefresh();
  };

  if (typeof document !== 'undefined' && document.getElementById && document.getElementById('rec-tabs')) {
    Page.start();
  }
})();
