// CSDT4-42: the shared month-end projection and over-budget alert, from
// GET /api/v1/households/{id}/budget-status. The Dashboard and the Spending page both use these
// helpers, so they always show the same figures in the same words. Needs wattly.js first.

const WattlyBudget = {};

(function () {
  WattlyBudget.money = function (value) {
    return value === null || value === undefined ? '—' : 'S$' + Number(value).toFixed(2);
  };

  WattlyBudget.url = function (householdId) {
    return '/api/v1/households/' + householdId + '/budget-status';
  };

  // The over-budget banner, or null when there is nothing to warn about (alerts off, no budget,
  // under budget, or no projection yet).
  WattlyBudget.bannerText = function (status) {
    if (!status || !status.alert) return null;
    return 'You’re on track to go over your budget: about ' + WattlyBudget.money(status.projectedMonthSgd) +
      ' this month against your ' + WattlyBudget.money(status.monthlyBudgetSgd) + ' budget (' +
      WattlyBudget.money(-Number(status.remainingSgd)) + ' over). Cutting back now can still bring it down.';
  };

  // "S$5.00/day over the last 7 days × 21.5 days left"
  WattlyBudget.projectionDetail = function (status) {
    if (status.projectedMonthSgd === null || status.projectedMonthSgd === undefined) {
      return status.message || 'Not enough recent prices to project yet.';
    }
    return WattlyBudget.money(status.averageDailySpendSgd) + '/day over the last ' + status.daysInAverage +
      (status.daysInAverage === 1 ? ' day' : ' days') + ' × ' + Number(status.daysLeft).toFixed(1) + ' days left';
  };

  // "S$7.50 over" / "S$42.50 left" / "Set a budget to compare"
  WattlyBudget.remainingText = function (status) {
    if (status.monthlyBudgetSgd === null || status.monthlyBudgetSgd === undefined) return 'Set a budget to compare';
    if (status.remainingSgd === null || status.remainingSgd === undefined) return 'Projection not ready yet';
    const remaining = Number(status.remainingSgd);
    return remaining < 0 ? 'Projected ' + WattlyBudget.money(-remaining) + ' over'
      : 'Projected ' + WattlyBudget.money(remaining) + ' left';
  };

  // One line for the Dashboard, with the same figures as the Spending page's boxes.
  WattlyBudget.summaryText = function (status) {
    const parts = ['This month: ' + WattlyBudget.money(status.spentSoFarSgd) + ' spent so far'];
    if (status.projectedMonthSgd !== null && status.projectedMonthSgd !== undefined) {
      parts.push('projected ' + WattlyBudget.money(status.projectedMonthSgd));
    }
    if (status.monthlyBudgetSgd !== null && status.monthlyBudgetSgd !== undefined) {
      parts.push('budget ' + WattlyBudget.money(status.monthlyBudgetSgd));
    }
    return parts.join(' · ');
  };

  WattlyBudget.showBanner = function (element, status) {
    if (!element) return;
    const text = WattlyBudget.bannerText(status);
    element.hidden = !text;
    element.className = text ? 'banner warn budget-alert' : '';
    element.textContent = text || '';
  };

  WattlyBudget.load = async function () {
    const result = await Wattly.api('GET', WattlyBudget.url(Wattly.householdId()));
    return result.ok ? result.data : null;
  };
})();
