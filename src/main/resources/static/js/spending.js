// Spending & Budget page. "Spent so far" and its breakdown come from spend.js (CSDT4-41). The
// projection, budget comparison, over-budget banner and daily chart come from the shared
// projection in budget-status.js (CSDT4-42), the same figures the Dashboard shows.
(function () {
  const money = WattlyBudget.money;
  const byId = id => document.getElementById(id);
  let current;
  let readOnly = false;

  function render(status) {
    current = status;
    byId('save-budget').disabled = readOnly;
    const title = new Intl.DateTimeFormat('en-SG', { month: 'long', year: 'numeric', timeZone: 'Asia/Singapore' })
      .format(new Date(String(status.month) + '-01T00:00:00+08:00'));
    byId('month-label').textContent = title + ' · Spending so far and projection';
    byId('projected-spend').textContent = money(status.projectedMonthSgd);
    byId('energy-month').textContent = WattlyBudget.projectionDetail(status);
    byId('budget-value').textContent = money(status.monthlyBudgetSgd);
    byId('budget-remaining').textContent = WattlyBudget.remainingText(status);
    byId('monthly-budget').value = status.monthlyBudgetSgd == null ? '' : status.monthlyBudgetSgd;
    byId('budget-alerts').checked = !!status.alertsEnabled;
    WattlyBudget.showBanner(byId('budget-banner'), status);

    const chart = byId('spending-chart');
    chart.replaceChildren();
    const days = status.days || [];
    const max = Math.max(0.01, ...days.map(day => Number(day.spendSgd) || 0));
    days.forEach(day => {
      const value = Number(day.spendSgd) || 0;
      const bar = document.createElement('span');
      bar.className = 'chart-bar' + (day.kind === 'PROJECTED' ? ' future' : '');
      bar.style.height = Math.max(2, value / max * 100) + '%';
      bar.title = day.date + ': ' + money(day.spendSgd) +
        (day.kind === 'PROJECTED' ? ' (projected)' : day.kind === 'TODAY' ? ' (today, partly projected)' : '');
      chart.appendChild(bar);
    });
    chart.setAttribute('aria-label', 'Daily spending for ' + title + ': actual so far, then projected');
    byId('chart-description').textContent = status.averageDailySpendSgd == null
      ? 'Daily spend so far. ' + (status.message || '')
      : 'Daily spend so far, then ' + money(status.averageDailySpendSgd) +
        '/day for the rest of the month (your average over the last ' + status.daysInAverage + ' days).';
    byId('chart-end').textContent = String(days.length);

    const progress = byId('budget-progress');
    const verdict = byId('budget-verdict');
    if (status.monthlyBudgetSgd == null || status.projectedMonthSgd == null) {
      progress.hidden = true;
      verdict.className = 'budget-verdict';
      verdict.textContent = status.monthlyBudgetSgd == null ? 'Set a budget to see how your projection compares.'
        : (status.message || 'The projection isn’t ready yet.');
      return;
    }
    progress.hidden = false;
    const over = status.overBudget;
    const fill = byId('budget-progress-fill');
    fill.style.width = Math.min(100, Math.max(0,
      Number(status.projectedMonthSgd) / Number(status.monthlyBudgetSgd) * 100)) + '%';
    fill.className = over ? 'over' : '';
    verdict.className = 'budget-verdict' + (over ? ' over' : '');
    verdict.textContent = WattlyBudget.remainingText(status) + ' at the end of the month.';
  }

  async function load() {
    const result = await Wattly.api('GET', WattlyBudget.url(Wattly.householdId()));
    if (!result.ok) {
      if (result.status === 404 && await Wattly.leaveMissingHousehold()) return;
      byId('page-banner').className = 'banner error';
      byId('page-banner').textContent = (result.data && result.data.detail) || 'Could not load the budget.';
      return;
    }
    byId('page-banner').textContent = '';
    byId('page-banner').className = '';
    render(result.data);
  }

  async function loadHousehold() {
    const result = await Wattly.api('GET', '/api/v1/households/' + Wattly.householdId());
    if (result.ok) {
      Wattly.renderHouseholdCard(result.data);
      readOnly = !!result.data.simulated;
      byId('readonly-note').hidden = !readOnly;
      byId('monthly-budget').disabled = readOnly;
      byId('budget-alerts').disabled = readOnly;
      byId('save-budget').disabled = readOnly || !current;
    }
  }

  byId('budget-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (readOnly || !current) return;
    byId('budget-error').textContent = '';
    byId('save-error').hidden = true;
    const budgetText = byId('monthly-budget').value.trim();
    if (budgetText && (!Number.isFinite(Number(budgetText)) || Number(budgetText) <= 0)) {
      byId('budget-error').textContent = 'Enter a monthly budget above zero.';
      return;
    }
    const button = byId('save-budget');
    button.disabled = true;
    const result = await Wattly.api('PUT', '/api/v1/households/' + Wattly.householdId() + '/budget', {
      monthlyBudgetSgd: budgetText || null,
      alertsEnabled: byId('budget-alerts').checked
    });
    button.disabled = false;
    if (!result.ok) {
      const errors = result.data && result.data.errors;
      if (errors) byId('budget-error').textContent = errors.monthlyBudgetSgd || '';
      byId('save-error').textContent = (result.data && result.data.detail) || 'Could not save the budget.';
      byId('save-error').hidden = false;
      return;
    }
    await load();
    Wattly.toast('Budget saved');
  });

  Wattly.keepHouseholdInLinks();
  Wattly.loadHouseholdSwitcher();
  loadHousehold();
  load();
})();
