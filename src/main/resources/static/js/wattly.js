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

// Fills the household card at the bottom of the sidebar.
Wattly.renderHouseholdCard = function (household) {
  const card = document.getElementById('household-card');
  if (!card || !household) {
    return;
  }
  card.querySelector('.avatar').textContent = Wattly.initials(household.name);
  card.querySelector('.hh-name').textContent = household.name;
  card.querySelector('.hh-plan').textContent = household.exposureLabel;
};

// The household card is also the switcher: clicking it opens a list of households just
// above it. Picking one reloads the same page for that household.
Wattly.loadHouseholdSwitcher = async function () {
  const card = document.getElementById('household-card');
  const menu = document.getElementById('household-menu');
  const items = document.getElementById('household-menu-items');
  if (!card || !menu || !items) {
    return;
  }
  Wattly.setUpHouseholdMenu(card, menu);
  const result = await Wattly.api('GET', '/api/v1/households');
  if (!result.ok) {
    items.innerHTML = '<li class="menu-title">Could not load households.</li>';
    return;
  }
  const current = Wattly.householdId();
  let html = '';
  result.data.forEach(function (household) {
    const params = new URLSearchParams(window.location.search);
    params.delete('none');
    params.set('household', household.id);
    const isCurrent = household.id === current;
    html += '<li><a class="menu-item" href="' + window.location.pathname + '?' + params.toString() + '"' +
      (isCurrent ? ' aria-current="true"' : '') + '>' +
      '<span class="avatar" aria-hidden="true">' + Wattly.escape(Wattly.initials(household.name)) + '</span>' +
      '<span class="menu-item-text">' +
        '<span class="menu-item-name">' + Wattly.escape(household.name) + '</span>' +
        '<span class="menu-item-plan">' + Wattly.escape(household.exposureLabel) + '</span>' +
      '</span>' +
      (isCurrent
        ? '<svg class="menu-check" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" ' +
          'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7"/></svg>'
        : '') +
      '</a></li>';
  });
  items.innerHTML = html;
};

// Open/close behaviour for the household list. Runs once per page.
Wattly.setUpHouseholdMenu = function (card, menu) {
  if (card.dataset.ready) {
    return;
  }
  card.dataset.ready = 'true';

  function links() {
    return Array.prototype.slice.call(menu.querySelectorAll('a'));
  }
  function open() {
    menu.hidden = false;
    card.setAttribute('aria-expanded', 'true');
    const current = menu.querySelector('.menu-item[aria-current="true"]') || links()[0];
    if (current) {
      current.focus();
      current.scrollIntoView({ block: 'nearest' });
    }
  }
  function close(returnFocus) {
    if (menu.hidden) {
      return;
    }
    menu.hidden = true;
    card.setAttribute('aria-expanded', 'false');
    if (returnFocus) {
      card.focus();
    }
  }

  card.addEventListener('click', function () {
    if (menu.hidden) {
      open();
    } else {
      close(false);
    }
  });
  // Up/Down arrows move between households; Escape closes the list.
  menu.addEventListener('keydown', function (event) {
    const all = links();
    const index = all.indexOf(document.activeElement);
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      all[(index + 1) % all.length].focus();
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      all[(index - 1 + all.length) % all.length].focus();
    } else if (event.key === 'Escape') {
      close(true);
    }
  });
  card.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') {
      close(true);
    } else if ((event.key === 'ArrowUp' || event.key === 'ArrowDown') && menu.hidden) {
      event.preventDefault();
      open();
    }
  });
  // Clicking anywhere else, or tabbing out of the list, closes it.
  document.addEventListener('click', function (event) {
    if (!menu.contains(event.target) && !card.contains(event.target)) {
      close(false);
    }
  });
  menu.addEventListener('focusout', function (event) {
    if (event.relatedTarget && !menu.contains(event.relatedTarget) && event.relatedTarget !== card) {
      close(false);
    }
  });
};

// Called when the household in the URL doesn't exist (for example it was deleted on the
// Households page). Moves to the first remaining household, or to the Households page if
// there are none left, so the page doesn't sit on an error. Returns false if it can't move.
Wattly.leaveMissingHousehold = async function () {
  const result = await Wattly.api('GET', '/api/v1/households');
  if (!result.ok) {
    return false;
  }
  if (result.data.length === 0) {
    window.location.replace('/households.html?none=1');
    return true;
  }
  const first = result.data[0].id;
  if (first === Wattly.householdId()) {
    return false;
  }
  const params = new URLSearchParams(window.location.search);
  params.set('household', first);
  window.location.replace(window.location.pathname + '?' + params.toString());
  return true;
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
