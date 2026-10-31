// Run with: node --test src/test/js/wattly-presenter.test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(
  path.join(__dirname, '../../main/resources/static/js/wattly.js'),
  'utf8'
);

class FakeNode {
  constructor() {
    this.children = [];
    this.listeners = {};
    this.attributes = {};
    this.hidden = false;
    this.disabled = false;
    this.textContent = '';
  }
  appendChild(child) { this.children.push(child); }
  addEventListener(name, callback) { this.listeners[name] = callback; }
  setAttribute(name, value) { this.attributes[name] = value; }
}

function response(data, status) {
  return {
    ok: status === undefined || status < 400,
    status: status || 200,
    text: async function () { return JSON.stringify(data); },
    json: async function () { return data; }
  };
}

function load(initialState, status) {
  const calls = [];
  const intervals = [];
  let state = initialState;
  let reloads = 0;
  const main = new FakeNode();
  const body = new FakeNode();
  body.prepend = function (node) { this.children.unshift(node); };
  const context = vm.createContext({
    document: {
      body: body,
      getElementById: function () { return null; },
      querySelector: function (selector) { return selector === 'main' ? main : null; },
      createElement: function () { return new FakeNode(); }
    },
    fetch: async function (url, options) {
      if (url === '/api/v1/csrf') {
        return response({ headerName: 'X-CSRF-TOKEN', token: 'test-token' });
      }
      const method = options.method;
      calls.push({ method: method, url: url, body: options.body ? JSON.parse(options.body) : undefined });
      if (method === 'GET') {
        return response(state, status);
      }
      if (url.endsWith('/advance')) {
        const periods = JSON.parse(options.body).periods;
        const currentTime = new Date(state.currentTime);
        currentTime.setTime(currentTime.getTime() + periods * 30 * 60 * 1000);
        state = { ...state, currentTime: currentTime.toISOString() };
      } else {
        state = { running: false, currentTime: '2026-10-24T08:00:00Z', zone: 'Asia/Singapore' };
      }
      return response(state, status);
    },
    Intl: Intl,
    Date: Date,
    URLSearchParams: URLSearchParams,
    window: {
      location: { search: '', reload: function () { reloads += 1; } },
      setInterval: function (callback, delay) { intervals.push({ callback: callback, delay: delay }); return intervals.length; },
      clearInterval: function (id) { intervals[id-1].cleared = true; }
    }
  });
  vm.runInContext(source, context);

  return {
    bar: body.children[0],
    calls: calls,
    intervals: intervals,
    reloads: function () { return reloads; },
    state: function () { return state; }
  };
}

async function settle() {
  await new Promise((resolve) => setImmediate(resolve));
}

function button(bar, label) {
  return bar.children[1].children.find((item) => item.textContent === label);
}

const ON = {
  running: true,
  currentTime: '2026-09-26T00:00:00Z',
  zone: 'Asia/Singapore'
};

test('presenter bar shows the simulated time and advances by the requested intervals', async () => {
  const app = load(ON);
  await settle();

  assert.equal(app.bar.hidden, false);
  assert.match(app.bar.children[0].children[0].textContent, /Simulated time:.*SGT/);
  assert.equal(app.intervals[0].delay, 1000);

  await button(app.bar, '+30 min').listeners.click();
  await button(app.bar, '+2 h').listeners.click();

  assert.deepEqual(app.calls.filter((call) => call.method === 'POST').map((call) => call.body), [
    { periods: 1 },
    { periods: 4 }
  ]);
  assert.equal(app.state().currentTime, '2026-09-26T02:30:00.000Z');
  assert.equal(app.reloads(), 2);
});

test('Stop ends demo mode and there is no duplicate Reset control', async () => {
  for (const label of ['Stop']) {
    const app = load(ON);
    await settle();

    assert.equal(button(app.bar, 'Reset'), undefined);
    await button(app.bar, label).listeners.click();

    assert.deepEqual(app.calls.filter((call) => call.method === 'POST').map((call) => call.url), [
      '/api/v1/demo/stop'
    ]);
    assert.equal(app.bar.hidden, true);
  }
});

test('polling reloads on simulated-time changes but not on real-time ticks', async () => {
  const app = load(ON);
  await settle();
  app.state().currentTime = '2026-09-26T00:30:00Z';
  await app.intervals[0].callback();
  assert.equal(app.reloads(), 1);

  const stopped = load({ running: false, currentTime: '2026-10-24T08:00:00Z', zone: 'Asia/Singapore' });
  await settle();
  stopped.state().currentTime = '2026-10-24T08:00:01Z';
  await stopped.intervals[0].callback();
  assert.equal(stopped.bar.hidden, true);
  assert.equal(stopped.reloads(), 0);
});

test('resident authorization failures stop polling, including already queued callbacks', async () => {
  for(const status of [401,403]) {
    const app=load({},status); await settle();
    assert.equal(app.bar.hidden,true);
    assert.equal(app.intervals[0].cleared,true);
    await app.intervals[0].callback(); await app.intervals[0].callback();
    assert.equal(app.calls.filter(c=>c.url==='/api/v1/demo/state').length,1);
  }
});
