// Spending & Budget page. "Spent so far" and its breakdown come from spend.js (CSDT4-41). The
// projection, budget comparison, over-budget banner and weekly chart come from the shared
// projection in budget-status.js (CSDT4-42), the same figures the Dashboard shows.
(function () {
  const money = WattlyBudget.money;
  const byId = id => document.getElementById(id);
  let current;
  let readOnly = false;

  function render(status, weekly) {
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
    const weeks = weekly && weekly.weeks ? weekly.weeks : [];
    const max = Math.max(0.01, ...weeks.flatMap(week => [
      Number(week.spentSgd) || 0, Number(week.budgetPaceSgd) || 0
    ]));
    weeks.forEach(week => {
      const group = document.createElement('span');
      group.className = 'week-group';
      const spent = document.createElement('span');
      spent.className = 'chart-bar';
      spent.style.height = Math.max(2, (Number(week.spentSgd) || 0) / max * 100) + '%';
      spent.title = week.from + '–' + week.to + ': ' + money(week.spentSgd) + ' spent';
      group.appendChild(spent);
      if (week.budgetPaceSgd != null) {
        const pace = document.createElement('span');
        pace.className = 'chart-bar future';
        pace.style.height = Math.max(2, Number(week.budgetPaceSgd) / max * 100) + '%';
        pace.title = week.from + '–' + week.to + ': ' + money(week.budgetPaceSgd) + ' budget pace';
        group.appendChild(pace);
      }
      chart.appendChild(group);
    });
    chart.setAttribute('aria-label', 'Weekly spending for ' + title);
    byId('chart-description').textContent = weekly && weekly.monthlyBudgetSgd == null
      ? 'Actual spending by week. Set a budget to see the budget pace.'
      : 'Actual spending by week compared with the even pace of your monthly budget.';
    byId('chart-start').textContent = weeks.length ? weeks[0].from : 'Week of';
    byId('chart-end').textContent = weeks.length ? weeks[weeks.length - 1].to : 'End of month';

    const progress = byId('budget-progress');
    const verdict = byId('budget-verdict');
    if (status.monthlyBudgetSgd == null || status.projectedMonthSgd == null) {
      progress.hidden = true;
      verdict.className = 'budget-verdict';
      verdict.textContent = status.monthlyBudgetSgd == null ? 'Set a budget to see how your projection compares.'
        : (status.message || 'The projection isn’t ready yet.');
      return;
    }

    function renderSavings(data) {
      byId('savings-total').textContent = money(data.totalSavedSgd);
      byId('savings-description').textContent = 'Savings from accepted Wattly recommendations this week, calculated from actual prices.';
      const rows = byId('savings-rows');
      rows.replaceChildren();
      (data.items || []).forEach(item => {
        const row = document.createElement('tr');
        const state = item.state === 'REALISED';
        row.innerHTML = '<th scope="row">' + Wattly.escape(item.applianceName) + '</th>' +
          '<td>' + Wattly.escape(state ? new Date(item.chosenStart).toLocaleDateString('en-SG') : 'Pending') + '</td>' +
          '<td class="num ' + (state ? '' : 'pending') + '">' +
          (state ? money(item.savedSgd) : Wattly.escape(item.pendingReason || 'Pending actual prices')) + '</td>';
        rows.appendChild(row);
      });
      if (!data.items || data.items.length === 0) {
        const row = document.createElement('tr');
        row.innerHTML = '<td colspan="3" class="pending">No accepted recommendations in this week.</td>';
        rows.appendChild(row);
      }
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
    const [result, weekly, savings] = await Promise.all([
      Wattly.api('GET', WattlyBudget.url(Wattly.householdId())),
      Wattly.api('GET', '/api/v1/households/' + Wattly.householdId() + '/spend/weekly'),
      Wattly.api('GET', '/api/v1/households/' + Wattly.householdId() + '/savings?period=week')
    ]);
    if (!result.ok || !weekly.ok || !savings.ok) {
      if (result.status === 404 && await Wattly.leaveMissingHousehold()) return;
      byId('page-banner').className = 'banner error';
      byId('page-banner').textContent = (result.data && result.data.detail) || 'Could not load spending data.';
      return;
    }
    byId('page-banner').textContent = '';
    byId('page-banner').className = '';
    render(result.data, weekly.data);
    renderSavings(savings.data);
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
