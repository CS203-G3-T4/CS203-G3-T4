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

  // "Estimated saving $2.22" until actual prices are known. Then an accepted suggestion shows what
  // it really saved (CSDT4-53): "Saved $0.41", or "Cost $0.12 more" if the chosen time turned out dearer.
  WattlyRecommendations.savingText = function (rec) {
    if (rec.realisedSavingSgd !== null && rec.realisedSavingSgd !== undefined) {
      const saved = Number(rec.realisedSavingSgd);
      return saved < 0 ? 'Cost ' + WattlyRecommendations.money(-saved) + ' more'
        : 'Saved ' + WattlyRecommendations.money(saved);
    }
    return (rec.savingEstimated === false ? 'Saved ' : 'Estimated saving ') +
      WattlyRecommendations.money(rec.estSavingSgd);
  };

  // Past-day frequency is descriptive; it is not forecast-error coverage.
  WattlyRecommendations.confidenceText = function (rec) {
    const suggested = Wattly.prettyTime(WattlyRecommendations.localTime(rec.suggestedStart));
    const usual = Wattly.prettyTime(WattlyRecommendations.localTime(rec.usualStart));
    if (rec.confidencePercent === null || rec.confidencePercent === undefined) {
      return 'Past-day pattern: not enough price history yet to check this.';
    }
    return 'Past-day pattern: ' + suggested + ' was really cheaper than ' + usual + ' on ' + rec.confidenceCheaperDays +
      ' of ' + rec.confidenceDaysChecked + ' past days (' + rec.confidencePercent + '%).';
  };

  WattlyRecommendations.withSavingsEvidence = function (data) {
    return (data.recommendations || []).map(function (rec) {
      return Object.assign({}, rec, { savingsEvidence: (data.savingsEvidence || {})[String(rec.id)] });
    });
  };

  WattlyRecommendations.rangeHtml = function (rec) {
    if (rec.demoForecast || rec.forecastSource !== 'F3_FORECAST' || rec.realisedSavingSgd != null) return '';
    const evidence = rec.savingsEvidence || {}, range = evidence.range || {};
    let text;
    if (range.available) {
      const money = function (n) { return Number(n) < 0 ? '-' + WattlyRecommendations.money(-Number(n)) : WattlyRecommendations.money(n); };
      text = 'Past-error savings range: ' + money(range.lowerSgd) + ' to ' + money(range.upperSgd) +
        ' for this run. Based on ' + range.completedDays + ' completed days; 90% target, not a guarantee.';
      if (rec.acceptedStart && rec.acceptedStart !== rec.suggestedStart) {
        text = 'For your chosen start: estimated saving ' + money(evidence.estimatedSavingSgd) + '. ' + text;
      }
    } else if (range.reason === 'ORIGINAL_FORECAST_NOT_RECORDED') {
      text = 'Savings range unavailable for this older suggestion.';
    } else if (range.reason === 'LIVE_ERROR_HISTORY_STALE') {
      text = 'Savings range unavailable: recent completed live results are missing.';
    } else if (range.reason === 'ORIGINAL_FORECAST_INCOMPLETE') {
      text = 'Savings range unavailable: the original forecast does not cover your chosen run.';
    } else {
      text = 'Savings range: collecting live results (' + Number(range.completedDays || 0) + '/20 completed days). Estimates may change.';
    }
    if (evidence.latestCheck && evidence.latestCheck.estimatedSavingSgd != null) {
      text += ' Latest start-time saving estimate: ' + WattlyRecommendations.money(evidence.latestCheck.estimatedSavingSgd) + '.';
    }
    return '<div class="rec-range">' + Wattly.escape(text) + '</div>';
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

  const hourOfDay = new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Singapore', hour: '2-digit', hourCycle: 'h23'
  });

  // Chart labels, Singapore time: "2pm", "12am", and "1:30pm" when not on the hour.
  WattlyRecommendations.shortTime = function (ms) {
    const parts = WattlyRecommendations.localTime(new Date(ms).toISOString()).split(':');
    if (parts.length < 2) return '';
    const hour = Number(parts[0]);
    return ((hour % 12) || 12) + (parts[1] === '00' ? '' : ':' + parts[1]) + (hour < 12 ? 'am' : 'pm');
  };

  function sgtDay(ms) {
    return singaporeDate.format(new Date(ms));
  }

  // A round ¢/kWh step that gives 2 to 4 gridlines over the shown range.
  function niceStep(range) {
    const steps = [0.5, 1, 2, 2.5, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000];
    for (let i = 0; i < steps.length; i++) {
      if (range / steps[i] <= 4) return steps[i];
    }
    return steps[steps.length - 1];
  }

  // Forecast chart on the Recommendations page (CSDT4-40, reworked after demo feedback): a line
  // of the forecast price in ¢/kWh with an hour gridline and an "2pm" style label on the x-axis,
  // and shaded bands for the suggested run (teal) and the usual run (blue). points are
  // { periodStart, usepSgdPerMwh } half-hours from the forecast the suggestion was built from.
  // The half-hours are kept, not averaged per hour, so the line shows the same prices as the
  // card's explanation; each point sits in the middle of its half-hour. sourceLabel says which
  // forecast it is (demo replay or F3's AI forecast).
  WattlyRecommendations.chartHtml = function (rec, points, sourceLabel) {
    const valid = (points || []).filter(function (p) {
      return Number.isFinite(Date.parse(p.periodStart)) && p.usepSgdPerMwh !== null &&
        p.usepSgdPerMwh !== undefined && Number.isFinite(Number(p.usepSgdPerMwh));
    });
    if (valid.length === 0) {
      return '<p class="rec-chart-empty">Forecast chart not available right now.</p>';
    }
    const sorted = valid.slice().sort(function (a, b) { return Date.parse(a.periodStart) - Date.parse(b.periodStart); });
    const times = sorted.map(function (p) { return Date.parse(p.periodStart); });
    const prices = sorted.map(function (p) { return Number(p.usepSgdPerMwh) / 10; });
    const ordered = prices.slice().sort(function (a, b) { return a - b; });
    // A single spike would squash the rest of the line, so with enough points the scale tops out
    // a little above the 95th percentile; higher prices are drawn at the top, marked as off the
    // scale (exact in the tooltip).
    const lowest = ordered[0];
    const highest = ordered[ordered.length - 1];
    const p95 = ordered[Math.floor(0.95 * (ordered.length - 1))];
    const top = Math.max(ordered.length >= 10 ? Math.min(highest, p95 * 1.25) : highest, 0.1);
    const bottom = Math.min(0, lowest); // USEP can go negative
    const step = niceStep(top - bottom);
    const yMax = Math.ceil(top / step) * step;
    const yMin = Math.floor(bottom / step) * step;
    const cents = function (value) {
      const sign = value < 0 ? '-' : '';
      const size = Math.abs(value);
      return sign + (size < 10 && size !== Math.round(size) ? size.toFixed(1) : String(Math.round(size))) + '¢';
    };

    // Plot coordinates: x 0-1000 across the forecast (first half-hour start to last half-hour end),
    // y 0-100 from yMax down to yMin. The SVG stretches to the card; text is HTML so it stays sharp.
    const t0 = times[0];
    const t1 = times[times.length - 1] + HALF_HOUR_MS;
    const x = function (t) { return (t - t0) / (t1 - t0) * 1000; };
    const y = function (v) { return (yMax - Math.min(Math.max(v, yMin), yMax)) / (yMax - yMin) * 100; };
    const pct = function (value, total) { return (value / total * 100).toFixed(2) + '%'; };
    const num = function (value) { return value.toFixed(1); };

    let grid = '';
    let yLabels = '';
    for (let v = yMin; v <= yMax + step / 1000; v += step) {
      grid += '<line class="rec-chart-grid' + (Math.abs(v) < step / 1000 ? ' zero' : '') + '" x1="0" x2="1000" y1="' +
        num(y(v)) + '" y2="' + num(y(v)) + '"></line>';
      yLabels += '<span style="top:' + pct(y(v), 100) + '">' + cents(v) + '</span>';
    }

    // An hour gridline on every hour, labelled every hour (every 2 hours past 13 hours, so a 24-hour
    // demo forecast fits). Labels between every third one are "minor": a narrow chart (a phone)
    // hides them, so it shows every 3 hours (every 6 for the demo) without labels overlapping.
    const labelEvery = t1 - t0 > 13 * HOUR_MS ? 2 : 1;
    let xLabels = '';
    for (let t = Math.ceil(t0 / HOUR_MS) * HOUR_MS; t <= t1; t += HOUR_MS) {
      grid += '<line class="rec-chart-grid hour" x1="' + num(x(t)) + '" x2="' + num(x(t)) + '" y1="0" y2="100"></line>';
      const hour = Number(hourOfDay.format(new Date(t)));
      if (hour % labelEvery !== 0) continue;
      const at = x(t) / 10;
      const classes = (at < 3 ? ' start' : at > 97 ? ' end' : '') + (hour % (labelEvery * 3) !== 0 ? ' minor' : '');
      xLabels += '<span' + (classes ? ' class="' + classes.trim() + '"' : '') + ' style="left:' + at.toFixed(2) + '%">' +
        WattlyRecommendations.shortTime(t) + '</span>';
    }

    // Bands for the two runs, clipped to the forecast. A run outside the forecast has no band.
    const runMs = (rec.runMinutes || 30) * MINUTE_MS;
    function band(kind, startIso) {
      const start = Date.parse(startIso);
      if (!Number.isFinite(start)) return { svg: '', range: '', shown: false };
      const from = Math.max(start, t0);
      const to = Math.min(start + runMs, t1);
      const range = WattlyRecommendations.shortTime(start) + '–' + WattlyRecommendations.shortTime(start + runMs);
      if (to <= from) return { svg: '', range: range, shown: false };
      return {
        svg: '<rect class="rec-band ' + kind + '" x="' + num(x(from)) + '" y="0" width="' +
          num(Math.max(x(to) - x(from), 2)) + '" height="100"></rect>',
        range: range,
        shown: true
      };
    }
    const usualBand = band('usual', rec.usualStart);
    const suggestedBand = band('suggested', rec.suggestedStart);

    // The line through the middle of each half-hour; a missing half-hour breaks it.
    let path = '';
    let dots = '';
    let targets = '';
    sorted.forEach(function (p, i) {
      const t = times[i];
      const value = prices[i];
      const px = x(t + HALF_HOUR_MS / 2);
      const py = y(value);
      const joined = i > 0 && t - times[i - 1] === HALF_HOUR_MS;
      path += (joined ? 'L' : 'M') + num(px) + ' ' + num(py) + ' ';
      const clipped = value > yMax;
      dots += '<path class="rec-dot' + (clipped ? ' off-scale' : '') + '" d="M' + num(px) + ' ' + num(py) + 'h0"></path>';
      const label = WattlyRecommendations.shortTime(t) + '–' + WattlyRecommendations.shortTime(t + HALF_HOUR_MS) +
        ': ' + cents(value) + '/kWh' + (clipped ? ' (off the scale)' : '');
      targets += '<rect class="rec-chart-target" x="' + num(x(t)) + '" y="0" width="' + num(x(t + HALF_HOUR_MS) - x(t)) +
        '" height="100"><title>' + Wattly.escape(label) + '</title></rect>';
    });

    const first = WattlyRecommendations.shortTime(t0);
    const last = WattlyRecommendations.shortTime(t1) + (sgtDay(t0) !== sgtDay(t1) ? ' tomorrow' : '');
    const warning = rec.kind === 'AVOID';
    const usualName = warning ? 'Avoid ' : 'Your usual ';
    const suggestedName = warning ? 'Run at ' : 'Suggested ';
    const outside = function (b) { return b.shown ? '' : ' (outside this forecast)'; };
    const summary = 'Forecast price from ' + first + ' to ' + last + ', ' + cents(lowest) + ' to ' +
      cents(highest) + '/kWh. ' + suggestedName + suggestedBand.range + ' shaded teal' + outside(suggestedBand) +
      ', ' + usualName.toLowerCase() + usualBand.range + ' shaded blue' + outside(usualBand) + '.';
    return '<figure class="rec-chart">' +
      '<div class="rec-chart-frame">' +
        '<div class="rec-chart-y" aria-hidden="true">' + yLabels + '</div>' +
        '<div class="rec-chart-plot">' +
          '<svg viewBox="0 0 1000 100" preserveAspectRatio="none" role="img" aria-label="' + Wattly.escape(summary) + '">' +
            usualBand.svg + suggestedBand.svg + grid +
            '<path class="rec-line" d="' + path.trim() + '"></path>' + dots + targets +
          '</svg>' +
        '</div>' +
        '<div class="rec-chart-x' + (labelEvery === 2 ? ' two-hourly' : '') + '" aria-hidden="true">' + xLabels + '</div>' +
      '</div>' +
      '<figcaption>' +
        '<div class="rec-chart-legend">' +
          '<span><i class="rec-swatch suggested"></i>' + Wattly.escape(suggestedName + suggestedBand.range + outside(suggestedBand)) + '</span>' +
          '<span><i class="rec-swatch usual"></i>' + Wattly.escape(usualName + usualBand.range + outside(usualBand)) + '</span>' +
          '<span><i class="rec-swatch line"></i>Forecast price, ¢/kWh' + (highest > yMax ? ' (spikes above ' + cents(yMax) + ' marked at the top)' : '') + '</span>' +
        '</div>' +
        (sourceLabel ? '<div class="rec-chart-source">' + Wattly.escape(sourceLabel) + '</div>' : '') +
      '</figcaption>' +
    '</figure>';
  };

  // The parts every card ends with: chart (on the Recommendations page), actions or what happened.
  function cardEnd(rec, options, actions) {
    const chart = options.points ? WattlyRecommendations.chartHtml(rec, options.points, options.forecastLabel) : '';
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
        '<div class="rec-confidence">' + Wattly.escape(WattlyRecommendations.confidenceText(rec)) + '</div>' + WattlyRecommendations.rangeHtml(rec) +
      '</div>' +
      '<div class="rec-saving">' + Wattly.escape(WattlyRecommendations.savingText(rec)) + '</div>' +
      cardEnd(rec, options,
        '<button type="button" class="btn-primary btn-sm" data-action="got-it">Got it</button>') +
    '</li>';
  };

  // options: nowMs, points (draw the forecast chart), forecastLabel (which forecast the chart shows)
  // and readOnly (a card on a decided tab).
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
        '<div class="rec-confidence">' + Wattly.escape(WattlyRecommendations.confidenceText(rec)) + '</div>' + WattlyRecommendations.rangeHtml(rec) +
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

  function render(data) {
    const list = document.getElementById('recommendation-list');
    const note = document.getElementById('recommendation-note');
    const badge = document.getElementById('recommendation-source');
    badge.hidden = !data.demoForecast;
    const recs = WattlyRecommendations.withSavingsEvidence(data);
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
    // A page can react to a decision, e.g. the Recommendations page updates its weekly savings.
    if (WattlyRecommendations.onDecided) WattlyRecommendations.onDecided(result.data);
    Wattly.toast(WattlyRecommendations.toastFor(result.data));
  }

  // Shows the open suggestions and warnings (snoozed warnings come back after their time).
  WattlyRecommendations.load = async function () {
    const note = document.getElementById('recommendation-note');
    // Reads only: suggestions are recomputed in the background, not on page load (CSDT4-37).
    const result = await Wattly.api('GET', WattlyRecommendations.listUrl(Wattly.householdId()));
    if (!result.ok) {
      document.getElementById('recommendation-list').innerHTML = '';
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
