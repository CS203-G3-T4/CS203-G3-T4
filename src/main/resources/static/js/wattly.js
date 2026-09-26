// Shared helpers for the Wattly pages. Plain JavaScript, no build step needed.
// Every page includes this file with <script src="/js/wattly.js"></script>.

const Wattly = {};

// Which household is on screen: ?household=3 in the URL, otherwise household 1 (the Tans).
// Login (CSDT4-23) will replace this with "the logged-in user's household".
Wattly.householdId = function () {
  const params = new URLSearchParams(window.location.search);
  const id = Number(params.get('household'));
  if (Number.isInteger(id) && id > 0) {
    return id;
  }
  return 1;
};

// Calls the backend and always returns { ok, status, data } instead of throwing,
// so each page can decide how to show errors.
Wattly.api = async function (method, url, body) {
  const options = { method: method, headers: { Accept: 'application/json' } };
  if (body !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(url, options);
  } catch (networkError) {
    return { ok: false, status: 0, data: { detail: 'Cannot reach the Wattly server. Is it running?' } };
  }
  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (notJson) {
      data = { detail: text };
    }
  }
  return { ok: response.ok, status: response.status, data: data };
};

// "19:45:00" or "19:45" -> "19:45" (for <input type="time">)
Wattly.hhmm = function (time) {
  if (!time) {
    return '';
  }
  return String(time).substring(0, 5);
};

// "19:45" -> "7:45 PM"
Wattly.prettyTime = function (time) {
  if (!time) {
    return '';
  }
  const parts = Wattly.hhmm(time).split(':');
  let hour = Number(parts[0]);
  const minute = parts[1];
  const suffix = hour >= 12 ? 'PM' : 'AM';
  hour = hour % 12;
  if (hour === 0) {
    hour = 12;
  }
  return hour + ':' + minute + ' ' + suffix;
};

Wattly.escape = function (value) {
  const div = document.createElement('div');
  div.textContent = value === null || value === undefined ? '' : String(value);
  return div.innerHTML;
};

Wattly.toast = function (message) {
  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.setAttribute('role', 'status');
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(function () { toast.remove(); }, 2200);
};

Wattly.initials = function (name) {
  const words = String(name || '').replace(/^the\s+/i, '').split(/\s+/).filter(Boolean);
  if (words.length === 0) {
    return '?';
  }
  if (words.length === 1) {
    return words[0].substring(0, 2).toUpperCase();
  }
  return (words[0][0] + words[1][0]).toUpperCase();
};

// Fills the household card at the bottom of the sidebar and the household switcher.
Wattly.renderHouseholdCard = function (household) {
  const card = document.getElementById('household-card');
  if (!card || !household) {
    return;
  }
  card.querySelector('.avatar').textContent = Wattly.initials(household.name);
  card.querySelector('.hh-name').textContent = household.name;
  card.querySelector('.hh-plan').textContent = household.exposureLabel;
};

Wattly.loadHouseholdSwitcher = async function () {
  const select = document.getElementById('household-select');
  if (!select) {
    return;
  }
  const result = await Wattly.api('GET', '/api/v1/households');
  if (!result.ok) {
    select.closest('.household-switch').hidden = true;
    return;
  }
  const current = Wattly.householdId();
  select.innerHTML = '';
  result.data.forEach(function (household) {
    const option = document.createElement('option');
    option.value = household.id;
    option.textContent = household.name;
    option.selected = household.id === current;
    select.appendChild(option);
  });
  select.addEventListener('change', function () {
    const params = new URLSearchParams(window.location.search);
    params.set('household', select.value);
    window.location.search = params.toString();
  });
};

// Keeps ?household=N when moving between pages.
Wattly.keepHouseholdInLinks = function () {
  const id = Wattly.householdId();
  document.querySelectorAll('a[data-keep-household]').forEach(function (link) {
    const url = new URL(link.getAttribute('href'), window.location.href);
    url.searchParams.set('household', id);
    link.setAttribute('href', url.pathname + url.search);
  });
};
