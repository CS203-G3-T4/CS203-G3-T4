"""Build a blind annotation workbook or score exported human labels; no model fitting."""
import argparse,hashlib,json
from pathlib import Path
from wattly_ml.spike_rule_review import build,load_final_prices,score,stamp,write_new

def main():
 p=argparse.ArgumentParser(description=__doc__);commands=p.add_subparsers(dest='command',required=True)
 b=commands.add_parser('build');b.add_argument('prices',type=Path);b.add_argument('--start',required=True);b.add_argument('--end',required=True);b.add_argument('--output',type=Path,required=True)
 s=commands.add_parser('score');s.add_argument('audit',type=Path);s.add_argument('labels',type=Path);s.add_argument('--output',type=Path,required=True);s.add_argument('--decision-at',required=True)
 a=p.parse_args()
 if a.command=='score':
  result=score(json.loads(a.audit.read_text()),json.loads(a.labels.read_text()),stamp(a.decision_at));write_new(a.output,result);print(json.dumps({'status':result['status'],'reviewedTargets':result['reviewedTargets']}));return
 source=hashlib.sha256(a.prices.read_bytes()).hexdigest()
 cohort,audit=build(load_final_prices(a.prices),stamp(a.start),stamp(a.end),source)
 result=score(audit)
 template=(Path(__file__).parent/'spike-rule-review.template.html').read_text()
 a.output.mkdir(parents=True,exist_ok=False)
 write_new(a.output/'blind-cohort.json',cohort);write_new(a.output/'rule-audit.json',audit);write_new(a.output/'review-status.json',result)
 # Prevent raw source text or labels from breaking out of the JSON script block.
 payload=json.dumps(cohort,allow_nan=False,separators=(',',':')).replace('<','\\u003c').replace('>','\\u003e').replace('&','\\u0026')
 (a.output/'review.html').write_text(template.replace('__COHORT_JSON__',payload))
 print(json.dumps({'targets':len(cohort['cases']),'sensitivity':audit['sensitivity'],'status':'AWAITING_INDEPENDENT_LABELS'}))

if __name__=='__main__':main()
