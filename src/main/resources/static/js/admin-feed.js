// CSDT4-48/141: admin "USEP price feed" panel, filled from GET /api/v1/admin/feed-status.
const WattlyFeed = {};
WattlyFeed.time = v => v ? new Date(v).toLocaleString('en-SG', {timeZone: 'Asia/Singapore', dateStyle: 'medium', timeStyle: 'short'}) : '—';
WattlyFeed.duration = seconds => {
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return minutes + ' min';
  const hours = Math.floor(minutes / 60);
  return hours < 48 ? hours + ' h ' + (minutes % 60) + ' min' : Math.round(hours / 24) + ' days';
};
WattlyFeed.ago = (v, now) => v ? WattlyFeed.duration(Math.max(0, (new Date(now) - new Date(v)) / 1000)) + ' ago' : 'Never';
WattlyFeed.set = (id, value, note) => {
  const dd = document.getElementById(id);
  dd.textContent = value;
  if (note) { const small = document.createElement('small'); small.textContent = note; dd.appendChild(small); }
};
WattlyFeed.render = function (data) {
  WattlyFeed.set('feed-last-sync', WattlyFeed.ago(data.lastSuccessfulSyncAt, data.generatedAt), WattlyFeed.time(data.lastSuccessfulSyncAt));
  WattlyFeed.set('feed-failures', String(data.consecutiveFailures),
    'Last poll ' + data.lastPollStatus.replace('_', ' ').toLowerCase() + (data.lastPollAt ? ' at ' + WattlyFeed.time(data.lastPollAt) : ''));
  WattlyFeed.set('feed-uptime', data.uptimePercent === null ? '—' : Number(data.uptimePercent).toFixed(2) + '%',
    data.measuredFrom ? 'Since ' + WattlyFeed.time(data.measuredFrom) : 'No polls yet');
  WattlyFeed.set('feed-fallbacks', String(data.fallbackCount), 'Polls that served the last stored price');

  const banner = document.getElementById('feed-banner');
  const open = data.openIncident;
  banner.hidden = !open && data.lastPollStatus !== 'NEVER_POLLED';
  banner.className = 'banner ' + (open ? 'error' : 'info');
  banner.textContent = open
    ? 'Feed incident in progress for ' + WattlyFeed.duration(open.durationSeconds) + ' (' + open.failedPolls + ' failed polls): ' + open.reason + '. Residents see the last stored price, marked stale.'
    : 'The feed has not been polled yet.';

  const rows = document.getElementById('feed-incidents');
  rows.replaceChildren();
  if (!data.recentIncidents.length) {
    const tr = document.createElement('tr'), td = document.createElement('td');
    td.colSpan = 5; td.textContent = 'No incidents recorded.'; tr.appendChild(td); rows.appendChild(tr);
  }
  data.recentIncidents.forEach(i => {
    const tr = document.createElement('tr');
    [WattlyFeed.time(i.startedAt), i.open ? 'Ongoing' : WattlyFeed.time(i.endedAt), WattlyFeed.duration(i.durationSeconds), String(i.failedPolls), i.reason]
      .forEach(v => { const td = document.createElement('td'); td.textContent = v; tr.appendChild(td); });
    rows.appendChild(tr);
  });
  document.getElementById('feed-status').textContent = 'Updated ' + WattlyFeed.time(data.generatedAt) + '.';
};
WattlyFeed.refresh = async function () {
  try {
    const response = await fetch('/api/v1/admin/feed-status', {cache: 'no-store'});
    if (!response.ok) {
      document.getElementById('feed-status').textContent = response.status === 403 ? 'ADMIN access required.' : 'Feed status unavailable.';
      return;
    }
    WattlyFeed.render(await response.json());
  } catch (e) {
    document.getElementById('feed-status').textContent = 'Feed status unavailable.';
  }
};
WattlyFeed.refresh();
setInterval(WattlyFeed.refresh, 60000);
