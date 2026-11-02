// Shared observed-price wording. Uses textContent; never renders server HTML.
const WattlyAssessment = {};

WattlyAssessment.matches = function (assessment, price) {
  const actual = assessment && assessment.actualPrice;
  const market = price && price.price;
  const assessedPeriod = Date.parse(assessment && assessment.periodStart);
  const displayedPeriod = Date.parse(price && price.intervalStart);
  return Number.isFinite(actual) && Number.isFinite(market) &&
    Number.isFinite(assessedPeriod) && assessedPeriod === displayedPeriod && actual === market;
};

WattlyAssessment.describe = function (assessment) {
  if (!assessment) return 'Price assessment is temporarily unavailable.';
  if (assessment.reason === 'NO_PRICE') return 'Price assessment unavailable: no recent price is available.';
  // Fail closed even with an older server that returns available=true alongside
  // one of these warning flags. No cheap/normal/spike label in these states.
  if (assessment.stale || assessment.reason === 'STALE_PRICE') {
    return 'Price assessment unavailable: the latest price is stale.';
  }
  if (assessment.reason === 'PERIOD_MAPPING_UNVERIFIED') {
    return 'Price assessment unavailable: the market interval has not been verified.';
  }
  if (assessment.reason === 'PRICE_MISMATCH') {
    return 'Price assessment unavailable: waiting for a matching price update.';
  }
  if (assessment.reason === 'INSUFFICIENT_REFERENCE_HISTORY') {
    const samples = Number.isInteger(assessment.sampleCount) ? assessment.sampleCount : 0;
    return 'Insufficient history for a price assessment (' + samples + ' prior days).';
  }
  const labels = {CHEAP: 'Cheap now', NORMAL: 'Normal', EXPENSIVE: 'Normal (above typical)', SPIKE: 'Spike'};
  if (!assessment.available || assessment.reason || !labels[assessment.classification] ||
      !Number.isFinite(assessment.actualPrice) || !Number.isFinite(assessment.typical) ||
      !Number.isFinite(assessment.deviation) || !Number.isFinite(assessment.spikeThreshold) ||
      !Number.isInteger(assessment.sampleCount) || assessment.sampleCount < 1) {
    return 'Price assessment is temporarily unavailable.';
  }
  const deviation = Number.isFinite(assessment.deviationPercent)
    ? Math.abs(assessment.deviationPercent).toFixed(0) + '% ' +
      (assessment.deviationPercent < 0 ? 'below' : 'above') + ' typical'
    : Math.abs(assessment.deviation).toFixed(2) + ' SGD/MWh ' +
      (assessment.deviation < 0 ? 'below' : 'above') + ' typical (percentage unavailable for zero/negative typical prices)';
  return labels[assessment.classification] + ' · ' + deviation +
    ' against ' + assessment.sampleCount + ' prior days.';
};
