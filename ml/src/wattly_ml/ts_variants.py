"""Price-only AR ablations: daily seasonality, robust loss, changes and direct horizons."""
from collections import Counter
import warnings

import numpy as np
from sklearn.exceptions import ConvergenceWarning
from sklearn.linear_model import HuberRegressor, Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler
from threadpoolctl import threadpool_limits

from .backtest import History, fresh, origins
from .data import truth
from .timebase import DAY, STEP, floor, targets

SPECS = {
    'AR6_DAILY_RIDGE': {'seasonal': True, 'delta': False, 'direct': False, 'loss': 'ridge'},
    'AR6_HUBER': {'seasonal': False, 'delta': False, 'direct': False, 'loss': 'huber'},
    'AR6_DELTA_RIDGE': {'seasonal': False, 'delta': True, 'direct': False, 'loss': 'ridge'},
    'AR6_DAILY_DELTA_HUBER': {'seasonal': True, 'delta': True, 'direct': False, 'loss': 'huber'},
    'DIRECT6_RIDGE': {'seasonal': False, 'delta': False, 'direct': True, 'loss': 'ridge'},
    'DIRECT6_HUBER': {'seasonal': False, 'delta': False, 'direct': True, 'loss': 'huber'},
    'DIRECT6_DAILY_RIDGE': {'seasonal': True, 'delta': False, 'direct': True, 'loss': 'ridge'},
    'DIRECT6_DAILY_DELTA_HUBER': {'seasonal': True, 'delta': True, 'direct': True, 'loss': 'huber'},
}
for spec in SPECS.values():
    spec['parameters'] = ({'alpha': 100.0} if spec['loss'] == 'ridge' else
                          {'alpha': .01, 'epsilon': 1.35, 'max_iter': 1000, 'tol': 1e-6})


def vector(prices, target, spec):
    if not prices:
        return None
    latest = max(prices)
    periods = [latest-n*STEP for n in range(6)]
    if not all(p in prices for p in periods):
        return None
    recent = [prices[p] for p in periods]
    values = [recent[n]-recent[n+1] for n in range(5)] if spec['delta'] else recent
    if spec['seasonal']:
        if target-DAY not in prices:
            return None
        daily = prices[target-DAY]
        values = values+[daily-recent[0] if spec['delta'] else daily]
    return values


def examples(rows, start, cutoff, spec):
    history, labels = History(rows), truth(rows, 'PROVISIONAL', cutoff)
    count = 24 if spec['direct'] else 1
    xs, ys = [[] for _ in range(count)], [[] for _ in range(count)]
    stamps, excluded = [], Counter()
    for origin in origins(start, cutoff):
        ts = targets(origin) if spec['direct'] else [origin]
        if ts[-1]+STEP > cutoff or not all(t in labels for t in ts):
            excluded['MISSING_TRUTH_OR_PURGED_TARGET_WINDOW'] += 1
            continue
        past = history.at(origin)
        # Recursive one-step labels predict the origin interval, which must never be an input.
        prices = {p:r['usep'] for p,r in past.items() if spec['direct'] or p < origin}
        permitted_latest = (origin, origin-STEP) if spec['direct'] else (origin-STEP,)
        if not fresh(past, origin) or not prices or max(prices) not in permitted_latest:
            excluded['STALE_OR_MISSING_RECENT_INPUT'] += 1
            continue
        vectors = [vector(prices,t,spec) for t in ts]
        if any(v is None for v in vectors):
            excluded['MISSING_EXACT_LAGS_OR_DAILY_REFERENCE'] += 1
            continue
        anchor = prices[max(prices)]
        for h,(target,x) in enumerate(zip(ts,vectors)):
            xs[h].append(x);ys[h].append(labels[target]-anchor if spec['delta'] else labels[target])
        stamps.append(origin.isoformat())
    width = (5 if spec['delta'] else 6)+int(spec['seasonal'])
    return [np.asarray(x,dtype=float).reshape(-1,width) for x in xs], [np.asarray(y,dtype=float) for y in ys], stamps, dict(excluded)


def fit(rows, start, cutoff):
    models, samples, matrices = {}, {}, {}
    with threadpool_limits(limits=1):
        for name,spec in SPECS.items():
            key = (spec['direct'],spec['delta'],spec['seasonal'])
            if key not in matrices:
                matrices[key] = examples(rows,start,cutoff,spec)
            xs,ys,stamps,excluded = matrices[key]
            if len(ys[0]) < 48:
                raise ValueError(f'{name}: fewer than 48 complete eligible training examples')
            fitted,issues,fit_warnings,iterations = [],[],[],[]
            for h,(x,y) in enumerate(zip(xs,ys),1):
                estimator = Ridge(**spec['parameters']) if spec['loss']=='ridge' else HuberRegressor(**spec['parameters'])
                model = make_pipeline(StandardScaler(),estimator)
                with warnings.catch_warnings(record=True) as caught:
                    warnings.simplefilter('always',ConvergenceWarning)
                    try:
                        fitted.append(model.fit(x,y))
                    except ValueError as exc:
                        raise ValueError(f'{name}, estimator {h}: fit failed: {exc}') from exc
                fit_warnings.extend({'horizon':h,'category':w.category.__name__,'message':str(w.message)} for w in caught)
                issues.extend({'horizon':h,'message':str(w.message)} for w in caught
                              if issubclass(w.category,ConvergenceWarning))
                iterations.append(int(estimator.n_iter_) if spec['loss']=='huber' else None)
            models[name] = fitted if spec['direct'] else fitted[0]
            samples[name] = {'perEstimator':[len(y) for y in ys], 'fittedEstimators':len(fitted),
                'trainingOriginTimestamps':stamps, 'excludedTrainingOrigins':excluded,
                'convergenceWarnings':issues, 'fitWarnings':fit_warnings, 'iterations':iterations,
                'featureOrder':(['change_'+str(n)+'_'+str(n+1) for n in range(1,6)] if spec['delta'] else
                                 ['recent_'+str(n) for n in range(1,7)])+
                                (['yesterday_target_minus_latest' if spec['delta'] else 'yesterday_target'] if spec['seasonal'] else [])}
    return models, {'trainingStart':start.isoformat(),'trainingCutoff':cutoff.isoformat(),
        'trainingSamplesByMethod':samples,'candidateSpecs':SPECS,
        'preprocessing':'Training-only StandardScaler; no imputation; every missing seasonal anchor excludes the window',
        'targetRule':'Delta labels subtract the origin-known latest price; direct models never feed back predictions',
        'productionEligible':False,'researchOnly':True}


def forecast(model, past, origin, spec):
    if not past or not fresh(past,origin):
        return None
    prices = {p:r['usep'] for p,r in past.items()}
    latest = max(prices)
    if latest not in (floor(origin),floor(origin)-STEP):
        return None
    ts = targets(origin)
    predicted = {}
    with threadpool_limits(limits=1):
        if spec['direct']:
            vectors = [vector(prices,t,spec) for t in ts]
            if any(x is None for x in vectors):
                return None
            anchor = prices[latest]
            for target,estimator,x in zip(ts,model,vectors):
                predicted[target] = float(estimator.predict([x])[0])+(anchor if spec['delta'] else 0)
        else:
            period = latest
            while period < ts[-1]:
                period += STEP
                x = vector(prices,period,spec)
                if x is None:
                    return None
                value = float(model.predict([x])[0])+(prices[max(prices)] if spec['delta'] else 0)
                predicted[period] = value;prices[period] = value
    values = [predicted[t] for t in ts]
    if not np.all(np.isfinite(values)):
        raise ValueError('Non-finite variant forecast; retain failed research run')
    return values
