(function () {
  const money = value => value == null ? '—' : 'S$' + Number(value).toFixed(2);
  const kwh = value => Number(value).toFixed(1) + ' kWh modelled';
  const byId = id => document.getElementById(id);
  let current;
  let readOnly = false;

  function render(data) {
    current = data;
    byId('save-budget').disabled = readOnly;
    const month = String(data.month);
    const title = new Intl.DateTimeFormat('en-SG', { month: 'long', year: 'numeric', timeZone: 'Asia/Singapore' })
      .format(new Date(month + '-01T00:00:00+08:00'));
    byId('month-label').textContent = title + ' · Modelled household spending';
    byId('spent-to-date').textContent = money(data.estimatedSpendToDateSgd);
    byId('projected-spend').textContent = money(data.projectedSpendMonthSgd);
    byId('budget-value').textContent = money(data.monthlyBudgetSgd);
    byId('energy-to-date').textContent = kwh(data.modelledKwhToDate);
    byId('energy-month').textContent = kwh(data.modelledKwhMonth);
    byId('monthly-budget').value = data.monthlyBudgetSgd == null ? '' : data.monthlyBudgetSgd;
    byId('planning-rate').value = data.planningRateCentsPerKwh == null ? '' : data.planningRateCentsPerKwh;
    byId('rate-field').hidden = data.rateSource === 'FIXED_PLAN';
    byId('fixed-rate-note').hidden = data.rateSource !== 'FIXED_PLAN';
    if (data.rateSource === 'FIXED_PLAN') {
      byId('fixed-rate-note').textContent = 'Using your fixed plan rate of ' +
        Number(data.rateCentsPerKwh).toFixed(2) + ' ¢/kWh from Household Settings.';
    }
    byId('assumption-note').textContent = data.rateSource === 'USER_PLANNING_RATE'
      ? 'Estimates use modelled load and your planning rate, not actual meter readings or a live bill. Taxes, fixed fees and other charges are excluded.'
      : data.rateSource === 'FIXED_PLAN'
        ? 'Estimates use modelled load and your saved fixed rate, not actual meter readings or a live bill. Taxes, fixed fees and other charges are excluded.'
        : 'Enter a planning rate to estimate spending. Energy use is modelled, not measured; taxes and other bill charges are excluded.';
    const chart = byId('spending-chart');
    chart.replaceChildren();
    const days = data.days || [];
    const hasCost = data.rateCentsPerKwh != null;
    const max = Math.max(1, ...days.map(day => Number(hasCost ? day.estimatedCostSgd : day.modelledKwh) || 0));
    days.forEach(day => {
      const value = Number(hasCost ? day.estimatedCostSgd : day.modelledKwh) || 0;
      const bar = document.createElement('span');
      bar.className = 'chart-bar' + (day.date > data.asOf ? ' future' : '');
      bar.style.height = Math.max(2, value / max * 100) + '%';
      bar.title = day.date + ': ' + (hasCost ? money(day.estimatedCostSgd) : kwh(day.modelledKwh));
      chart.appendChild(bar);
    });
    chart.setAttribute('aria-label', hasCost ? 'Daily estimated spending for ' + title : 'Daily modelled energy use for ' + title);
    byId('chart-description').textContent = hasCost
      ? 'Daily spending based on modelled use and ' + Number(data.rateCentsPerKwh).toFixed(2) + ' ¢/kWh.'
      : 'Daily modelled energy use. Add a planning rate to see spending.';
    byId('chart-end').textContent = String(days.length);
    const progress = byId('budget-progress');
    const verdict = byId('budget-verdict');
    if (data.monthlyBudgetSgd == null || data.projectedSpendMonthSgd == null) {
      progress.hidden = true;
      verdict.textContent = data.monthlyBudgetSgd == null ? 'Set a budget to see how your projection compares.' :
        'Add a planning rate to compare your budget with estimated spending.';
      byId('budget-remaining').textContent = data.monthlyBudgetSgd == null ? 'Set a budget to compare' : 'Comparison needs a rate';
      return;
    }
    progress.hidden = false;
    const remaining = Number(data.remainingBudgetSgd);
    const over = remaining < 0;
    const fill = byId('budget-progress-fill');
    fill.style.width = Math.min(100, Math.max(0, Number(data.projectedSpendMonthSgd) / Number(data.monthlyBudgetSgd) * 100)) + '%';
    fill.className = over ? 'over' : '';
    verdict.className = 'budget-verdict' + (over ? ' over' : '');
    verdict.textContent = over ? 'Projected ' + money(-remaining) + ' over budget.' : 'Projected ' + money(remaining) + ' under budget.';
    byId('budget-remaining').textContent = over ? 'Projected over budget' : 'Projected within budget';
  }

  async function load() {
    const result = await Wattly.api('GET', '/api/v1/households/' + Wattly.householdId() + '/budget');
    if (!result.ok) {
      if (result.status === 404 && await Wattly.leaveMissingHousehold()) return;
      byId('page-banner').className = 'banner error';
      byId('page-banner').textContent = (result.data && result.data.detail) || 'Could not load spending estimates.';
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
      byId('planning-rate').disabled = readOnly;
      byId('save-budget').disabled = readOnly || !current;
    }
  }

  byId('budget-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (readOnly || !current) return;
    byId('budget-error').textContent = '';
    byId('rate-error').textContent = '';
    byId('save-error').hidden = true;
    const budgetText = byId('monthly-budget').value.trim();
    const rateText = byId('planning-rate').value.trim();
    if (budgetText && (!Number.isFinite(Number(budgetText)) || Number(budgetText) <= 0)) {
      byId('budget-error').textContent = 'Enter a monthly budget above zero.';
      return;
    }
    if (current && current.rateSource !== 'FIXED_PLAN' && rateText &&
        (!Number.isFinite(Number(rateText)) || Number(rateText) <= 0 || Number(rateText) > 200)) {
      byId('rate-error').textContent = 'Enter a rate above zero and at most 200 ¢/kWh.';
      return;
    }
    const button = byId('save-budget');
    button.disabled = true;
    const result = await Wattly.api('PUT', '/api/v1/households/' + Wattly.householdId() + '/budget', {
      monthlyBudgetSgd: budgetText || null,
      planningRateCentsPerKwh: current && current.rateSource === 'FIXED_PLAN' ? current.planningRateCentsPerKwh : rateText || null
    });
    button.disabled = false;
    if (!result.ok) {
      const errors = result.data && result.data.errors;
      if (errors) {
        byId('budget-error').textContent = errors.monthlyBudgetSgd || '';
        byId('rate-error').textContent = errors.planningRateCentsPerKwh || '';
      }
      byId('save-error').textContent = (result.data && result.data.detail) || 'Could not save the budget.';
      byId('save-error').hidden = false;
      return;
    }
    render(result.data);
    Wattly.toast('Budget saved');
  });

  Wattly.keepHouseholdInLinks();
  Wattly.loadHouseholdSwitcher();
  loadHousehold();
  load();
})();
