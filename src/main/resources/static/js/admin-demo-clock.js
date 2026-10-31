// CSDT4-43: admin controls for the shared DemoClock.
const DemoClockAdmin = {};

DemoClockAdmin.message = document.getElementById('demo-clock-message');
DemoClockAdmin.advanceButton = document.getElementById('demo-advance-button');
DemoClockAdmin.stopButton = document.getElementById('demo-stop-button');

DemoClockAdmin.render = function (data) {
  DemoClockAdmin.message.className = data.running ? 'demo-clock-status running' : 'demo-clock-status';
  const time = new Intl.DateTimeFormat('en-SG', {
    timeZone: 'Asia/Singapore',
    dateStyle: 'full',
    timeStyle: 'short'
  }).format(new Date(data.currentTime));
  DemoClockAdmin.message.textContent = data.running
    ? 'Demo mode is ON · Simulated time: ' + time + ' SGT'
    : 'Demo mode is OFF · Using real time: ' + time + ' SGT';
  DemoClockAdmin.advanceButton.disabled = !data.running;
  DemoClockAdmin.stopButton.disabled = !data.running;
};

DemoClockAdmin.refresh = async function () {
  const result = await Wattly.api('GET', '/api/v1/demo/state');
  if (!result.ok) {
    DemoClockAdmin.message.className = 'demo-clock-status error';
    DemoClockAdmin.message.textContent = result.status === 401 || result.status === 403
      ? 'ADMIN access is required to manage the demo clock.'
      : 'Could not load demo clock status. Check the connection and try again.';
    return;
  }
  DemoClockAdmin.render(result.data);
};

DemoClockAdmin.submit = async function (url, body, successMessage) {
  const result = await Wattly.api('POST', url, body);
  if (!result.ok) {
    DemoClockAdmin.message.className = 'demo-clock-status error';
    DemoClockAdmin.message.textContent = result.data && result.data.error
      ? result.data.error
      : 'Demo clock update failed (HTTP ' + result.status + ').';
    return;
  }
  DemoClockAdmin.render(result.data);
  DemoClockAdmin.message.textContent += ' · ' + successMessage;
};

document.getElementById('demo-clock-start').addEventListener('submit', async function (event) {
  event.preventDefault();
  const localDateTime = document.getElementById('demo-start-at').value;
  if (!localDateTime) {
    DemoClockAdmin.message.textContent = 'Choose a Singapore date and time to start demo mode.';
    return;
  }
  const [date, time] = localDateTime.split('T');
  await DemoClockAdmin.submit('/api/v1/demo/start', { date: date, time: time },
    'Demo mode started.');
});

document.getElementById('demo-clock-advance').addEventListener('submit', async function (event) {
  event.preventDefault();
  const periods = Number(document.getElementById('demo-half-hours').value);
  if (!Number.isInteger(periods) || periods < 1) {
    DemoClockAdmin.message.textContent = 'Enter a positive whole number of half-hour periods.';
    return;
  }
  await DemoClockAdmin.submit('/api/v1/demo/advance', { periods: periods },
    'Demo time advanced by ' + periods + (periods === 1 ? ' half-hour period.' : ' half-hour periods.'));
});

DemoClockAdmin.stopButton.addEventListener('click', async function () {
  await DemoClockAdmin.submit('/api/v1/demo/stop', {}, 'Demo mode stopped; real time is active.');
});

DemoClockAdmin.refresh();
window.setInterval(DemoClockAdmin.refresh, 10000);
