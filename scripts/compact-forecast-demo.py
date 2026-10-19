"""Allowlist one real saved chart origin and aggregate scores for public presentation."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys


def project(report, source_digest):
    if report.get('actionable') is not False or report.get('productionApproved') is not False:
        raise ValueError('Require an explicitly unapproved research report')
    result = {k: deepcopy(report[k]) for k in ('schemaVersion', 'label', 'actionable', 'productionApproved',
        'modelVersion', 'displayModel', 'evaluationPurpose', 'exclusions', 'limitations', 'study') if k in report}
    result['protocol'] = {k: report['protocol'][k] for k in ('trainingStart', 'trainingCutoff', 'validationStart',
        'validationEnd', 'testStart', 'testEnd', 'validationSelectedBaseline', 'mappingEvidenceId')}
    result['quality'] = {k: report['quality'][k] for k in ('periodCount', 'from', 'to', 'revisionRows',
        'missingHalfHours', 'parsingFailures')}
    result['test'] = {'commonPairs': report['test']['commonPairs'], 'eligibleOrigins': len(report['origins']),
        'table': {k: {'mae': v['mae'], 'spikes': {'assessedPairs': v.get('spikes', {}).get('assessedPairs', 0)}}
            for k, v in report['test']['table'].items() if k in ('AI', 'B1', 'B2', 'B3')}}
    result['allMethods'] = {'commonPairs': report['allMethods']['commonPairs'], 'table': {
        k: {key: v[key] for key in ('eligibleOrigins', 'eligiblePairs')}
        for k, v in report['allMethods']['table'].items() if k in ('AI', 'B1', 'B2', 'B3')}}
    result['origins'] = [{'asOf': o['asOf'], 'points': [{k: p[k] for k in
        ('horizon', 'targetPeriod', 'actual', 'AI', 'B1', 'B2', 'B3')} for p in o['points']]}
        for o in report['origins'][:1]]
    result['actuals'] = [{k: p[k] for k in ('targetPeriod', 'actual')} for p in report['actuals']]
    result['sourceReportSha256'] = source_digest
    result['presentationScope'] = 'Real saved historical diagnostic: one chart origin; aggregate metrics use all eligible origins. Not a live forecast.'
    result['limitations'].append('Only the first saved chart origin is bundled; aggregate scores use the full archived report.')
    return result


def main():
    source, output = map(Path, sys.argv[1:])
    raw = source.read_bytes()
    result = project(json.loads(raw), hashlib.sha256(raw).hexdigest())
    with output.open('x') as stream:
        json.dump(result, stream, separators=(',', ':'), allow_nan=False)
        stream.write('\n')


if __name__ == '__main__':
    main()
