"""Package the trusted saved SOLAR_183D pipelines without fitting or promotion.

Only load locally trusted joblib. The caller must pin its expected SHA256 and
supply the prior development baseline score report; no new ranking search.
"""
import argparse
from datetime import datetime, timezone
from pathlib import Path
import json
import math
import joblib
from wattly_ml.artifacts import save_bundle
from wattly_ml.data import digest
from wattly_ml.features import feature_names, holiday_calendar, holiday_digest
from wattly_ml.solar import SOLAR_POLICY
from wattly_ml.spikes import DEFAULT_CONFIG


def package(source, expected_sha, baseline_report, root, version):
    if digest(source)!=expected_sha:
        raise ValueError('Research source checksum mismatch')
    source_bundle=joblib.load(source)
    if (source_bundle.get('researchOnly') is not True or source_bundle.get('featureNames')!=feature_names(3)
            or source_bundle.get('recipe')!={'features':'SOLAR','windowDays':183,'halfLifeDays':None}
            or len(source_bundle['models'])!=24):
        raise ValueError('Not the frozen SOLAR_183D recipe')
    for model in source_bundle['models']:
        params=model.get_params()
        if (model.n_features_in_!=30 or params.get('histgradientboostingregressor__loss')!='squared_error'
                or params.get('histgradientboostingregressor__random_state')!=42):
            raise ValueError('Unexpected fitted model')
    report=json.loads(baseline_report.read_text())
    scores={n:report['table'][n]['mae'] for n in ('B1','B2','B3')}
    if not all(type(v) in (int,float) and math.isfinite(v) and v>=0 for v in scores.values()):
        raise ValueError('Invalid baseline report')
    cutoff=source_bundle['trainingCutoff']
    now=datetime.now(timezone.utc).isoformat()
    manifest={'schemaVersion':1,'featureSchemaVersion':3,'featureOrder':feature_names(3),
        'inputPolicy':SOLAR_POLICY,'holidayCalendarVersion':holiday_calendar()['version'],
        'holidayCalendarSha256':holiday_digest(),'trainingCutoff':cutoff,'usableFrom':cutoff,
        'trainingDate':now,'trainingDateMeaning':'Conservative availability bound at packaging; no fit performed',
        'validationEnd':cutoff,'trainingOrigins':source_bundle['trainingOrigins'],
        'baselineRanking':sorted(scores,key=scores.get),
        'baselineRankingSource':'Previously examined eight-week development price MAE; not fresh validation',
        'baselineDevelopmentMae':scores,'baselineReportSha256':digest(baseline_report),
        'spikeConfig':DEFAULT_CONFIG.copy(),'productionEligible':False,'exploratory':True,'retrospective':True,
        'researchRecipe':'SOLAR_183D','researchSourceSha256':expected_sha,
        'inputProvenance':source_bundle['inputProvenance'],
        'savingsEvidence':'Same recipe as weekly refits, not a live savings guarantee or a new evaluation'}
    result=save_bundle({'models':source_bundle['models'],'manifest':manifest},root,version)
    if digest(source)!=expected_sha:
        raise ValueError('Research source changed')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,required=True);p.add_argument('--sha256',required=True)
    p.add_argument('--baseline-report',type=Path,required=True)
    p.add_argument('--models-root',type=Path,required=True);p.add_argument('--version',required=True)
    a=p.parse_args();m=package(a.source,a.sha256,a.baseline_report,a.models_root,a.version)
    print(json.dumps({'version':m['version'],'featureSchemaVersion':3,'sourceSha256':m['researchSourceSha256'],
                      'modelSha256':m['modelSha256'],'productionEligible':False,'retrained':False}))
