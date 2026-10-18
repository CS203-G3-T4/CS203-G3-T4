// F4 dashboard card: suggested start times (CSDT4-19) with Accept / Dismiss (CSDT4-20).
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

  // The request for a decision: accept, accept at the resident's own time, or dismiss.
  WattlyRecommendations.requestFor = function (id, action, startTime) {
    if (action === 'dismiss') {
      return { url: '/api/v1/recommendations/' + id + '/dismiss', body: undefined };
    }
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
    return '';
  };

  WattlyRecommendations.itemHtml = function (rec) {
    const suggested = WattlyRecommendations.localTime(rec.suggestedStart);
    const usual = WattlyRecommendations.localTime(rec.usualStart);
    return '<li class="rec-item" data-id="' + Number(rec.id) + '">' +
      '<div class="rec-main">' +
        '<div class="rec-name">' + Wattly.escape(rec.applianceName) + '</div>' +
        '<div class="rec-when">Start at <strong>' + Wattly.escape(Wattly.prettyTime(suggested)) +
          '</strong> instead of ' + Wattly.escape(Wattly.prettyTime(usual)) + '</div>' +
        '<div class="rec-reason">' + Wattly.escape(rec.reason) + '</div>' +
      '</div>' +
      '<div class="rec-saving">Save about ' + Wattly.escape(WattlyRecommendations.money(rec.estSavingSgd)) + '</div>' +
      '<div class="rec-actions">' +
        '<button type="button" class="btn-primary btn-sm" data-action="accept">Accept</button>' +
        '<button type="button" class="btn-ghost btn-sm" data-action="dismiss">Dismiss</button>' +
        '<details class="rec-override"><summary>Pick my own time</summary>' +
          '<input type="time" step="60" value="' + Wattly.escape(suggested) + '" aria-label="Your start time for ' +
            Wattly.escape(rec.applianceName) + '">' +
          '<button type="button" class="btn-ghost btn-sm" data-action="accept-at">Accept at this time</button>' +
        '</details>' +
      '</div>' +
      '<p class="rec-status" role="status" hidden></p>' +
    '</li>';
  };

  function render(data) {
    const list = document.getElementById('recommendation-list');
    const note = document.getElementById('recommendation-note');
    const badge = document.getElementById('recommendation-source');
    badge.hidden = !data.demoForecast;
    const recs = data.recommendations || [];
    const count = document.getElementById('recommendation-count');
    const savings = document.getElementById('recommendation-savings');
    if (count) count.textContent = String(recs.length);
    if (savings) savings.textContent = WattlyRecommendations.money(
      recs.reduce(function (sum, rec) { return sum + Number(rec.estSavingSgd || 0); }, 0));
    if (data.demoForecast) {
      note.textContent = 'Suggestions use a demo forecast (a replay of past prices), not a live prediction.';
    } else if (data.message) {
      note.textContent = data.message;
    } else if (recs.length === 0) {
      note.textContent = 'Your appliances are already set to run at the cheapest forecast times.';
    } else {
      note.textContent = 'Based on F3’s price forecast.';
    }
    list.innerHTML = recs.map(WattlyRecommendations.itemHtml).join('');
    if (recs.length === 0 && data.demoForecast) {
      note.textContent += ' No cheaper times found right now.';
    }
  }

  async function decide(item, action) {
    const id = item.dataset.id;
    const status = item.querySelector('.rec-status');
    const input = item.querySelector('input[type="time"]');
    const request = WattlyRecommendations.requestFor(id, action, action === 'accept-at' ? input.value : null);
    item.querySelectorAll('button').forEach(function (button) { button.disabled = true; });
    const result = await Wattly.api('POST', request.url, request.body);
    status.hidden = false;
    if (!result.ok) {
      const errors = result.data && result.data.errors;
      status.textContent = (errors && errors.startTime) || (result.data && result.data.detail) ||
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
    Wattly.toast(result.data.status === 'ACCEPTED' ? 'Suggestion accepted' : 'Suggestion dismissed');
  }

  // Re-checks the forecast (safe to repeat) and shows the open suggestions.
  WattlyRecommendations.load = async function () {
    const note = document.getElementById('recommendation-note');
    const result = await Wattly.api('POST', '/api/v1/households/' + Wattly.householdId() +
      '/recommendations/generate');
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

  WattlyRecommendations.init = function () {
    const list = document.getElementById('recommendation-list');
    if (!list) return;
    list.addEventListener('click', function (event) {
      const button = event.target.closest('button[data-action]');
      if (!button) return;
      decide(button.closest('.rec-item'), button.dataset.action);
    });
  };
})();
