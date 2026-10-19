// F4 dashboard card: suggested start times (CSDT4-19) with Accept / Dismiss (CSDT4-20), and
// price spike warnings (CSDT4-38) with Got it / Remind me later, and explanations with real
// numbers, a confidence from past prices and an "estimated" saving (CSDT4-39). CSDT4-40 adds
// "Remind me later" until a chosen time on every card, read-only cards for the Recommendations
// page tabs, and a mini forecast chart.
// Needs wattly.js first. Decisions update the card in place; nothing reloads and nothing runs
// automatically. Pure helpers are on WattlyRecommendations so they can be tested with Node.

const WattlyRecommendations = {};

(function () {
  const timeOfDay = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Singapore', hour: '2-digit', minute: '2-digit', hourCycle: 'h23'
  });

  // "2026-09-30T05:00:00Z" -> "13:00" (Singapore time)
  WattlyRecommendations.localTime = function (iso) {
    if (!iso) return '';
    const parsed = new Date(iso);
    return Number.isNaN(parsed.getTime()) ? '' : timeOfDay.format(parsed);
  };

  WattlyRecommendations.money = function (value) {
    return '$' + Number(value).toFixed(2);
  };

  // "Estimated saving $2.22" until actual prices are known (CSDT4-53 will show the real saving).
  WattlyRecommendations.savingText = function (rec) {
    return (rec.savingEstimated === false ? 'Saved ' : 'Estimated saving ') +
      WattlyRecommendations.money(rec.estSavingSgd);
  };

  // How sure: from actual prices on past days, never made up (CSDT4-39).
  WattlyRecommendations.confidenceText = function (rec) {
    const suggested = Wattly.prettyTime(WattlyRecommendations.localTime(rec.suggestedStart));
    const usual = Wattly.prettyTime(WattlyRecommendations.localTime(rec.usualStart));
    if (rec.confidencePercent === null || rec.confidencePercent === undefined) {
      return 'How sure: not enough price history yet to check this.';
    }
    return 'How sure: ' + suggested + ' was really cheaper than ' + usual + ' on ' + rec.confidenceCheaperDays +
      ' of ' + rec.confidenceDaysChecked + ' past days (' + rec.confidencePercent + '%).';
  };

  WattlyRecommendations.listUrl = function (householdId) {
    return '/api/v1/households/' + householdId + '/recommendations';
  };

  // "Updated 14:31" from the latest background recompute, or '' before the first one.
  WattlyRecommendations.updatedText = function (refreshedAt) {
    const time = WattlyRecommendations.localTime(refreshedAt);
    return time ? ' Updated ' + Wattly.prettyTime(time) + '.' : '';
  };

  const HOUR_MS = 60 * 60 * 1000;
  const MINUTE_MS = 60 * 1000;
  const HALF_HOUR_MS = 30 * MINUTE_MS;

  // When the suggestion runs out (the server sends it; older responses fall back to the start).
  function deadlineMs(rec) {
    return Date.parse(rec.deadline || rec.suggestedStart);
  }

  // "Remind me later" needs time to come back before the suggestion runs out (CSDT4-40).
  WattlyRecommendations.canSnooze = function (rec, nowMs) {
    const deadline = deadlineMs(rec);
    return !Number.isNaN(deadline) && deadline - nowMs > 15 * MINUTE_MS;
  };

  // The time picker starts at an hour from now, or 15 minutes before it runs out if sooner,
  // rounded down to 5 minutes, as Singapore HH:mm.
  WattlyRecommendations.defaultSnoozeTime = function (rec, nowMs) {
    const at = Math.min(nowMs + HOUR_MS, deadlineMs(rec) - 15 * MINUTE_MS);
    const rounded = at - (at % (5 * MINUTE_MS));
    return WattlyRecommendations.localTime(new Date(rounded).toISOString());
  };

  WattlyRecommendations.snoozeHtml = function (rec, nowMs) {
    if (!WattlyRecommendations.canSnooze(rec, nowMs)) return '';
    return '<details class="rec-snooze"><summary>Remind me later</summary>' +
      '<input type="time" step="60" value="' + Wattly.escape(WattlyRecommendations.defaultSnoozeTime(rec, nowMs)) +
        '" aria-label="Remind me about ' + Wattly.escape(rec.applianceName) + ' at">' +
      '<button type="button" class="btn-ghost btn-sm" data-action="snooze">Remind me then</button>' +
    '</details>';
  };

  // The request for a decision: accept, accept at the resident's own time, dismiss, got it (spike
  // warnings), or snooze until a chosen time.
  WattlyRecommendations.requestFor = function (id, action, time) {
    if (action === 'dismiss' || action === 'got-it' || action === 'remind-later') {
      return { url: '/api/v1/recommendations/' + id + '/' + action, body: undefined };
    }
    if (action === 'snooze') {
      return { url: '/api/v1/recommendations/' + id + '/snooze', body: { until: time } };
    }
    const startTime = time;
    return {
      url: '/api/v1/recommendations/' + id + '/accept',
      body: startTime ? { startTime: startTime } : undefined
    };
  };

  WattlyRecommendations.decidedText = function (rec) {
    if (rec.status === 'ACCEPTED') {
      const own = WattlyRecommendations.localTime(rec.acceptedStart) !==
        WattlyRecommendations.localTime(rec.suggestedStart);
      return 'Accepted: run it at ' + Wattly.prettyTime(WattlyRecommendations.localTime(rec.acceptedStart)) +
        (own ? ' (your time)' : '') + '. Wattly won’t start anything for you.';
    }
    if (rec.status === 'DISMISSED') {
      return 'Dismissed. You won’t see this suggestion again for this run.';
    }
    if (rec.status === 'ACKNOWLEDGED') {
      return 'Got it. Wattly won’t warn you again about this run.';
    }
    if (rec.status === 'ACTIVE' && rec.remindAt) {
      return 'OK, we’ll remind you at ' + Wattly.prettyTime(WattlyRecommendations.localTime(rec.remindAt)) + '.';
    }
    return '';
  };

  const singaporeDate = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Singapore', year: 'numeric', month: '2-digit', day: '2-digit'
  });
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

  // "30 Sep, 2:31 PM" (Singapore time; month names spelled out so every browser shows the same)
  WattlyRecommendations.when = function (iso) {
    if (!iso) return '';
    const parts = singaporeDate.format(new Date(iso)).split('-'); // 2026-09-30
    return Number(parts[2]) + ' ' + MONTHS[Number(parts[1]) - 1] + ', ' +
      Wattly.prettyTime(WattlyRecommendations.localTime(iso));
  };

  // What a card on the Accepted, Dismissed or History tab says happened (CSDT4-40).
  WattlyRecommendations.statusText = function (rec, nowMs) {
    const when = WattlyRecommendations.when(rec.decidedAt || rec.createdAt);
    if (rec.status === 'ACTIVE') {
      return rec.remindAt && Date.parse(rec.remindAt) > nowMs
        ? 'Snoozed: comes back at ' + Wattly.prettyTime(WattlyRecommendations.localTime(rec.remindAt)) + '.'
        : 'Still open.';
    }
    if (rec.status === 'SUPERSEDED') return 'Replaced by newer advice (' + when + ').';
    if (rec.status === 'EXPIRED') return 'Expired: the time passed without a decision (' + when + ').';
    return WattlyRecommendations.decidedText(rec) + ' (' + when + ')';
  };

  // Mini forecast chart (CSDT4-40): one bar per forecast half-hour in ¢/kWh, the usual run in
  // blue and the suggested run in teal, so the cheaper window is visible. points are
  // { periodStart, usepSgdPerMwh } from the forecast the suggestion was built from.
  WattlyRecommendations.chartHtml = function (rec, points) {
    if (!points || points.length === 0) {
      return '<p class="rec-chart-empty">Forecast chart not available right now.</p>';
    }
    const sorted = points.slice().sort(function (a, b) { return Date.parse(a.periodStart) - Date.parse(b.periodStart); });
    const prices = sorted.map(function (p) { return Number(p.usepSgdPerMwh) / 10; });
    const ordered = prices.slice().sort(function (a, b) { return a - b; });
    // A single spike would flatten every other bar, so with enough points the scale tops out a
    // little above the 95th percentile; taller bars are drawn full height and marked as off the
    // scale (exact in the tooltip).
    const highest = ordered[ordered.length - 1];
    const p95 = ordered[Math.floor(0.95 * (ordered.length - 1))];
    const top = Math.max(ordered.length >= 10 ? Math.min(highest, p95 * 1.25) : highest, 0.1);
    const cents = function (value) { return (value < 10 ? value.toFixed(1) : String(Math.round(value))) + '¢'; };
    const runMs = (rec.runMinutes || 30) * MINUTE_MS;
    function inRun(startIso, t) {
      const start = Date.parse(startIso);
      return t >= start - (start % HALF_HOUR_MS) && t < start + runMs;
    }
    const step = 6;
    const height = 56;
    const bars = sorted.map(function (p, i) {
      const t = Date.parse(p.periodStart);
      const value = prices[i];
      const kind = inRun(rec.suggestedStart, t) ? 'suggested' : inRun(rec.usualStart, t) ? 'usual' : '';
      const clipped = value > top;
      // Highlighted runs stay visible even when they are very cheap.
      const h = Math.max(kind ? 4 : 1, Math.min(value, top) / top * height);
      const label = Wattly.prettyTime(WattlyRecommendations.localTime(p.periodStart)) + ': ' +
        cents(value) + '/kWh' + (clipped ? ' (off the scale)' : '');
      return '<rect class="rec-bar' + (kind ? ' ' + kind : '') + '" x="' + (i * step) + '" y="' + (height - h).toFixed(1) +
        '" width="4" height="' + h.toFixed(1) + '" rx="1"><title>' + Wattly.escape(label) + '</title></rect>' +
        (clipped ? '<rect class="rec-bar-cap" x="' + (i * step) + '" y="0" width="4" height="2"></rect>' : '');
    }).join('');
    const usual = Wattly.prettyTime(WattlyRecommendations.localTime(rec.usualStart));
    const suggested = Wattly.prettyTime(WattlyRecommendations.localTime(rec.suggestedStart));
    const first = Wattly.prettyTime(WattlyRecommendations.localTime(sorted[0].periodStart));
    const nextDay = WattlyRecommendations.when(sorted[0].periodStart).split(',')[0] !==
      WattlyRecommendations.when(sorted[sorted.length - 1].periodStart).split(',')[0];
    const last = Wattly.prettyTime(WattlyRecommendations.localTime(sorted[sorted.length - 1].periodStart)) +
      (nextDay ? ' tomorrow' : '');
    const warning = rec.kind === 'AVOID';
    const summary = 'Forecast price from ' + first + ' to ' + last + ', ' + cents(ordered[0]) + ' to ' +
      cents(highest) + '/kWh. ' + (warning ? 'Avoid ' : 'Your usual ') + usual + ' in blue, ' +
      (warning ? 'run at ' : 'suggested ') + suggested + ' in teal.';
    return '<figure class="rec-chart">' +
      '<svg viewBox="0 0 ' + (sorted.length * step - 2) + ' ' + height + '" preserveAspectRatio="none" role="img" ' +
        'aria-label="' + Wattly.escape(summary) + '">' + bars + '</svg>' +
      '<figcaption class="rec-chart-axis"><span>' + Wattly.escape(first) + '</span><span>up to ' +
        cents(top) + '/kWh shown</span><span>' + Wattly.escape(last) + '</span></figcaption>' +
      '<div class="rec-chart-legend"><span><i class="rec-swatch usual"></i>' + (warning ? 'Avoid ' : 'Your usual ') +
        Wattly.escape(usual) + '</span><span><i class="rec-swatch suggested"></i>' +
        (warning ? 'Run at ' : 'Suggested ') + Wattly.escape(suggested) + '</span></div>' +
    '</figure>';
  };

  // The parts every card ends with: chart (on the Recommendations page), actions or what happened.
  function cardEnd(rec, options, actions) {
    const chart = options.points ? WattlyRecommendations.chartHtml(rec, options.points) : '';
    if (options.readOnly) {
      return chart + '<p class="rec-status" role="status">' +
        Wattly.escape(WattlyRecommendations.statusText(rec, options.nowMs)) + '</p>';
    }
    return chart + '<div class="rec-actions">' + actions + WattlyRecommendations.snoozeHtml(rec, options.nowMs) +
      '</div><p class="rec-status" role="status" hidden></p>';
  }

  function cardOptions(nowMsOrOptions) {
    const options = typeof nowMsOrOptions === 'object' && nowMsOrOptions !== null ? nowMsOrOptions
      : { nowMs: nowMsOrOptions };
    if (options.nowMs === undefined) options.nowMs = Date.now();
    return options;
  }

  WattlyRecommendations.toastFor = function (rec) {
    return {
      ACCEPTED: 'Suggestion accepted',
      DISMISSED: 'Suggestion dismissed',
      ACKNOWLEDGED: 'Warning noted',
      ACTIVE: 'We’ll remind you later'
    }[rec.status] || 'Saved';
  };

  // A price spike warning: avoid the usual time, with the spike-free alternative and cost avoided.
  WattlyRecommendations.warningHtml = function (rec, nowMsOrOptions) {
    const options = cardOptions(nowMsOrOptions);
    const usual = Wattly.prettyTime(WattlyRecommendations.localTime(rec.usualStart));
    const instead = Wattly.prettyTime(WattlyRecommendations.localTime(rec.suggestedStart));
    return '<li class="rec-item rec-warning' + (options.readOnly ? ' decided' : '') + '" data-id="' + Number(rec.id) + '">' +
      '<div class="rec-main">' +
        '<div class="rec-flag">Price spike expected</div>' +
        '<div class="rec-name">' + Wattly.escape(rec.applianceName) + '</div>' +
        '<div class="rec-when">Avoid <strong>' + Wattly.escape(usual) + '</strong>. Run at ' +
          Wattly.escape(instead) + ' instead</div>' +
        '<div class="rec-reason">' + Wattly.escape(rec.reason) + '</div>' +
        '<div class="rec-confidence">' + Wattly.escape(WattlyRecommendations.confidenceText(rec)) + '</div>' +
      '</div>' +
      '<div class="rec-saving">' + Wattly.escape(WattlyRecommendations.savingText(rec)) + '</div>' +
      cardEnd(rec, options,
        '<button type="button" class="btn-primary btn-sm" data-action="got-it">Got it</button>') +
    '</li>';
  };

  // options: nowMs, points (draw the mini chart) and readOnly (a card on a decided tab).
  WattlyRecommendations.itemHtml = function (rec, nowMsOrOptions) {
    const options = cardOptions(nowMsOrOptions);
    if (rec.kind === 'AVOID') {
      return WattlyRecommendations.warningHtml(rec, options);
    }
    const suggested = WattlyRecommendations.localTime(rec.suggestedStart);
    const usual = WattlyRecommendations.localTime(rec.usualStart);
    return '<li class="rec-item' + (options.readOnly ? ' decided' : '') + '" data-id="' + Number(rec.id) + '">' +
      '<div class="rec-main">' +
        '<div class="rec-name">' + Wattly.escape(rec.applianceName) + '</div>' +
        '<div class="rec-when">Start at <strong>' + Wattly.escape(Wattly.prettyTime(suggested)) +
          '</strong> instead of ' + Wattly.escape(Wattly.prettyTime(usual)) + '</div>' +
        '<div class="rec-reason">' + Wattly.escape(rec.reason) + '</div>' +
        '<div class="rec-confidence">' + Wattly.escape(WattlyRecommendations.confidenceText(rec)) + '</div>' +
      '</div>' +
      '<div class="rec-saving">' + Wattly.escape(WattlyRecommendations.savingText(rec)) + '</div>' +
      cardEnd(rec, options,
        '<button type="button" class="btn-primary btn-sm" data-action="accept">Accept</button>' +
        '<button type="button" class="btn-ghost btn-sm" data-action="dismiss">Dismiss</button>' +
        '<details class="rec-override"><summary>Pick my own time</summary>' +
          '<input type="time" step="60" value="' + Wattly.escape(suggested) + '" aria-label="Your start time for ' +
            Wattly.escape(rec.applianceName) + '">' +
          '<button type="button" class="btn-ghost btn-sm" data-action="accept-at">Accept at this time</button>' +
        '</details>') +
    '</li>';
  };

  // The note above the open suggestions.
  WattlyRecommendations.noteText = function (data, count) {
    let note;
    if (data.demoForecast) {
      note = 'Suggestions use a demo forecast (a replay of past prices), not a live prediction.';
      if (count === 0) note += ' No cheaper times found right now.';
    } else if (data.message) {
      note = data.message;
    } else if (count === 0) {
      note = 'Your appliances are already set to run at the cheapest forecast times.';
    } else {
      note = 'Based on F3’s price forecast.';
    }
    return note + WattlyRecommendations.updatedText(data.refreshedAt);
  };

  // Header figures: how many open suggestions and their estimated savings (from the app's data).
  WattlyRecommendations.showTotals = function (recs) {
    const count = document.getElementById('recommendation-count');
    const savings = document.getElementById('recommendation-savings');
    if (count) count.textContent = String(recs.length);
    if (savings) savings.textContent = WattlyRecommendations.money(
      recs.reduce(function (sum, rec) { return sum + Number(rec.estSavingSgd || 0); }, 0));
  };

  function render(data) {
    const list = document.getElementById('recommendation-list');
    const note = document.getElementById('recommendation-note');
    const badge = document.getElementById('recommendation-source');
    badge.hidden = !data.demoForecast;
    const recs = data.recommendations || [];
    WattlyRecommendations.showTotals(recs);
    note.textContent = WattlyRecommendations.noteText(data, recs.length);
    const nowMs = Date.now();
    list.innerHTML = recs.map(function (rec) { return WattlyRecommendations.itemHtml(rec, nowMs); }).join('');
  }

  async function decide(item, action) {
    const id = item.dataset.id;
    const status = item.querySelector('.rec-status');
    const input = action === 'accept-at' ? item.querySelector('.rec-override input[type="time"]')
      : action === 'snooze' ? item.querySelector('.rec-snooze input[type="time"]') : null;
    const request = WattlyRecommendations.requestFor(id, action, input ? input.value : null);
    item.querySelectorAll('button').forEach(function (button) { button.disabled = true; });
    const result = await Wattly.api('POST', request.url, request.body);
    status.hidden = false;
    if (!result.ok) {
      const errors = result.data && result.data.errors;
      status.textContent = (errors && (errors.startTime || errors.until)) || (result.data && result.data.detail) ||
        'Could not save your choice. Please try again.';
      status.className = 'rec-status error';
      item.querySelectorAll('button').forEach(function (button) { button.disabled = false; });
      return;
    }
    status.textContent = WattlyRecommendations.decidedText(result.data);
    status.className = 'rec-status done';
    item.classList.add('decided');
    item.querySelector('.rec-actions').remove();
    const count = document.getElementById('recommendation-count');
    const savings = document.getElementById('recommendation-savings');
    if (count) count.textContent = String(Math.max(0, Number(count.textContent) - 1));
    if (savings) savings.textContent = WattlyRecommendations.money(
      Math.max(0, Number(savings.textContent.replace(/[^0-9.-]/g, '')) - Number(result.data.estSavingSgd || 0)));
    Wattly.toast(WattlyRecommendations.toastFor(result.data));
  }

  // Shows the open suggestions and warnings (snoozed warnings come back after their time).
  WattlyRecommendations.load = async function () {
    const note = document.getElementById('recommendation-note');
    // Reads only: suggestions are recomputed in the background, not on page load (CSDT4-37).
    const result = await Wattly.api('GET', WattlyRecommendations.listUrl(Wattly.householdId()));
    if (!result.ok) {
      document.getElementById('recommendation-list').innerHTML = '';
      const count = document.getElementById('recommendation-count');
      const savings = document.getElementById('recommendation-savings');
      if (count) count.textContent = '—';
      if (savings) savings.textContent = '—';
      note.textContent = (result.data && result.data.detail) || 'Could not load suggestions.';
      return;
    }
    render(result.data);
  };

  WattlyRecommendations.init = function (listId) {
    const list = document.getElementById(listId || 'recommendation-list');
    if (!list) return;
    list.addEventListener('click', function (event) {
      const button = event.target.closest('button[data-action]');
      if (!button) return;
      decide(button.closest('.rec-item'), button.dataset.action);
    });
  };
})();
