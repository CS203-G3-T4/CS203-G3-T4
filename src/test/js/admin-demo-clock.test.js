// Run with: node --test src/test/js/admin-demo-clock.test.js
// CSDT4-43: admin controls operate the shared demo clock using explicit half-hour steps.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const script = path.join(__dirname, '../../main/resources/static/js/admin-demo-clock.js');
const IDS = [
  'demo-clock-message',
  'demo-advance-button',
  'demo-stop-button',
  'demo-clock-start',
  'demo-start-at',
  'demo-clock-advance',
  'demo-half-hours'
];

class FakeNode {
  constructor() {
    this.textContent = '';
    this.className = '';
    this.disabled = false;
    this.value = '';
    this.listeners = {};
  }
  addEventListener(name, callback) { this.listeners[name] = callback; }
}

function load(api) {
  const nodes = Object.fromEntries(IDS.map((id) => [id, new FakeNode()]));
  const context = vm.createContext({
    document: { getElementById: (id) => nodes[id] },
    Wattly: { api },
    Intl,
    Date,
    Number,
    window: { setInterval() {} }
  });
  vm.runInContext(fs.readFileSync(script, 'utf8'), context);
  return { nodes, Admin: vm.runInContext('DemoClockAdmin', context) };
}

const ON = {
  running: true,
  currentTime: '2026-09-26T00:00:00Z',
  zone: 'Asia/Singapore'
};
const OFF = {
  running: false,
  currentTime: '2026-10-24T08:00:00Z',
  zone: 'Asia/Singapore'
};

test('clock state reports simulated and real Singapore time and toggles controls', () => {
  const { nodes, Admin } = load(async () => ({ ok: true, status: 200, data: ON }));
  Admin.render(ON);
  assert.match(nodes['demo-clock-message'].textContent, /Demo mode is ON.*Simulated time:.*SGT/);
  assert.equal(nodes['demo-advance-button'].disabled, false);
  assert.equal(nodes['demo-stop-button'].disabled, false);

  Admin.render(OFF);
  assert.match(nodes['demo-clock-message'].textContent, /Demo mode is OFF.*real time.*SGT/);
  assert.equal(nodes['demo-advance-button'].disabled, true);
  assert.equal(nodes['demo-stop-button'].disabled, true);
});

test('start, advance, and stop call the demo API with date, time, and periods', async () => {
  const requests = [];
  const { nodes, Admin } = load(async (method, url, body) => {
    if (method === 'POST') {
      requests.push({ method, url, body });
    }
    const data = url.endsWith('/stop') ? OFF : ON;
    return { ok: true, status: 200, data };
  });

  nodes['demo-start-at'].value = '2026-09-26T08:00';
  await nodes['demo-clock-start'].listeners.submit({ preventDefault() {} });
  nodes['demo-half-hours'].value = '4';
  await nodes['demo-clock-advance'].listeners.submit({ preventDefault() {} });
  await nodes['demo-stop-button'].listeners.click();

  assert.deepEqual(JSON.parse(JSON.stringify(requests)), [
    { method: 'POST', url: '/api/v1/demo/start', body: { date: '2026-09-26', time: '08:00' } },
    { method: 'POST', url: '/api/v1/demo/advance', body: { periods: 4 } },
    { method: 'POST', url: '/api/v1/demo/stop', body: {} }
  ]);
  assert.match(nodes['demo-clock-message'].textContent, /Demo mode is OFF.*real time/);
  assert.match(nodes['demo-clock-message'].textContent, /Demo mode stopped/);
});

test('non-positive and fractional half-hour periods are rejected before the API call', async () => {
  let calls = 0;
  const { nodes } = load(async (method) => {
    if (method === 'POST') {
      calls += 1;
    }
    return { ok: true, status: 200, data: ON };
  });
  nodes['demo-half-hours'].value = '1.5';
  nodes['demo-clock-advance'].listeners.submit({ preventDefault() {} });
  assert.equal(calls, 0);
  assert.match(nodes['demo-clock-message'].textContent, /positive whole number/);
});

test('server errors are shown to the presenter', async () => {
  const { nodes, Admin } = load(async () => ({
    ok: false,
    status: 400,
    data: { error: 'Cannot advance when the demo clock is stopped' }
  }));
  await Admin.submit('/api/v1/demo/advance', { periods: 1 }, 'advanced');
  assert.match(nodes['demo-clock-message'].textContent, /Cannot advance when the demo clock is stopped/);
});
