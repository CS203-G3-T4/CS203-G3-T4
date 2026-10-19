// Run with: node --test src/test/js/admin-feed.test.js
// CSDT4-48/141: the admin "USEP price feed" panel shows only what GET /api/v1/admin/feed-status returns.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = path.join(__dirname, '../../main/resources/static/js/admin-feed.js');
const IDS = ['feed-status', 'feed-banner', 'feed-last-sync', 'feed-failures', 'feed-uptime', 'feed-fallbacks', 'feed-incidents'];

class FakeNode {
  constructor() { this.children = []; this._text = ''; this.hidden = false; this.className = ''; }
  set textContent(value) { this._text = String(value); this.children = []; }
  get textContent() { return this._text + this.children.map((c) => c.textContent).join(''); }
  appendChild(child) { this.children.push(child); return child; }
  replaceChildren() { this.children = []; this._text = ''; }
}

// Loads the real script with a fake page; `fetchImpl` stands in for the admin API.
function load(fetchImpl) {
  const nodes = Object.fromEntries(IDS.map((id) => [id, new FakeNode()]));
  const context = vm.createContext({
    document: { getElementById: (id) => nodes[id], createElement: () => new FakeNode() },
    fetch: fetchImpl, setInterval() {}, Date, Number, String, Math, Intl
  });
  vm.runInContext(fs.readFileSync(script, 'utf8'), context);
  return { nodes, Feed: vm.runInContext('WattlyFeed', context) };
}

const NEVER = async () => ({ ok: false, status: 500 });

const HEALTHY = {
  source: 'NEMS_SN_SG', lastPollStatus: 'SUCCESS', lastPollAt: '2026-10-19T08:05:00Z',
  lastSuccessfulSyncAt: '2026-10-19T08:05:00Z', consecutiveFailures: 0, month: '2026-10',
  measuredFrom: '2026-10-01T00:00:00+08:00', uptimePercent: 99.1089, fallbackCount: 4,
  openIncident: null, recentIncidents: [], generatedAt: '2026-10-19T08:10:00Z'
};

test('every tile shows the value from the response', () => {
  const { nodes, Feed } = load(NEVER);
  Feed.render(HEALTHY);
  assert.match(nodes['feed-last-sync'].textContent, /^5 min ago/);
  assert.equal(nodes['feed-failures'].children[0].textContent, 'Last poll success at ' + Feed.time(HEALTHY.lastPollAt));
  assert.match(nodes['feed-uptime'].textContent, /^99\.11%/);
  assert.match(nodes['feed-fallbacks'].textContent, /^4/);
  assert.equal(nodes['feed-banner'].hidden, true);
  assert.equal(nodes['feed-incidents'].children[0].children[0].textContent, 'No incidents recorded.');
});

test('before any poll there is no uptime and the banner says so', () => {
  const { nodes, Feed } = load(NEVER);
  Feed.render({ ...HEALTHY, lastPollStatus: 'NEVER_POLLED', lastPollAt: null, lastSuccessfulSyncAt: null,
    measuredFrom: null, uptimePercent: null, fallbackCount: 0 });
  assert.match(nodes['feed-last-sync'].textContent, /^Never/);
  assert.match(nodes['feed-uptime'].textContent, /^—/);
  assert.match(nodes['feed-uptime'].textContent, /No polls yet/);
  assert.equal(nodes['feed-banner'].hidden, false);
  assert.match(nodes['feed-banner'].textContent, /not been polled yet/);
});

test('an open incident shows a banner with its reason and an Ongoing row', () => {
  const { nodes, Feed } = load(NEVER);
  const open = { startedAt: '2026-10-19T08:00:00Z', endedAt: null, durationSeconds: 600,
    reason: 'ResourceAccessException: Read timed out', failedPolls: 3, open: true };
  Feed.render({ ...HEALTHY, lastPollStatus: 'FAILURE', consecutiveFailures: 3, openIncident: open, recentIncidents: [open] });
  assert.equal(nodes['feed-banner'].hidden, false);
  assert.match(nodes['feed-banner'].className, /error/);
  assert.match(nodes['feed-banner'].textContent, /10 min.*3 failed polls.*Read timed out.*stale/);
  const cells = nodes['feed-incidents'].children[0].children.map((c) => c.textContent);
  assert.equal(cells[1], 'Ongoing');
  assert.equal(cells[3], '3');
  assert.match(cells[4], /Read timed out/);
});

test('incident reasons are shown as text, never as HTML', () => {
  const { nodes, Feed } = load(NEVER);
  const evil = { startedAt: '2026-10-19T08:00:00Z', endedAt: '2026-10-19T08:01:00Z', durationSeconds: 60,
    reason: '<img src=x onerror=alert(1)>', failedPolls: 1, open: false };
  Feed.render({ ...HEALTHY, recentIncidents: [evil] });
  const reasonCell = nodes['feed-incidents'].children[0].children[4];
  assert.equal(reasonCell.textContent, '<img src=x onerror=alert(1)>');
  assert.equal(reasonCell.children.length, 0);
});

test('durations read naturally from minutes to days', () => {
  const { Feed } = load(NEVER);
  assert.equal(Feed.duration(90), '2 min');
  assert.equal(Feed.duration(3600 + 5 * 60), '1 h 5 min');
  assert.equal(Feed.duration(3 * 24 * 3600), '3 days');
});

test('a 403 says ADMIN access is required and leaves the tiles unfilled', async () => {
  const { nodes, Feed } = load(async () => ({ ok: false, status: 403 }));
  await Feed.refresh();
  assert.equal(nodes['feed-status'].textContent, 'ADMIN access required.');
  assert.equal(nodes['feed-uptime'].textContent, '');
});

test('a server error or network failure says the status is unavailable', async () => {
  const failing = load(async () => ({ ok: false, status: 500 }));
  await failing.Feed.refresh();
  assert.equal(failing.nodes['feed-status'].textContent, 'Feed status unavailable.');
  const offline = load(async () => { throw new TypeError('network down'); });
  await offline.Feed.refresh();
  assert.equal(offline.nodes['feed-status'].textContent, 'Feed status unavailable.');
});

test('a successful response fills the panel from the API', async () => {
  const { nodes, Feed } = load(async () => ({ ok: true, status: 200, json: async () => HEALTHY }));
  await Feed.refresh();
  assert.match(nodes['feed-uptime'].textContent, /^99\.11%/);
  assert.match(nodes['feed-status'].textContent, /^Updated /);
});
