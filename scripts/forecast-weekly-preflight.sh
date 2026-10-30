#!/usr/bin/env bash
# Readiness only: no training, model promotion, collector writes or service changes.
set -euo pipefail
env_file=${1:?Supply the private weekly environment file}
[[ -r "$env_file" && $(stat -c %a "$env_file") == 600 ]] || { echo 'Weekly environment file must be readable and mode 600' >&2; exit 2; }
set -a; source "$env_file"; set +a
: "${WATTLY_PYTHON:?Set the dedicated Python executable}"
export WATTLY_PREFLIGHT_REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$WATTLY_PYTHON" - <<'PY'
import json,os,sys
from pathlib import Path
from urllib.request import urlopen
from datetime import datetime
sys.path.insert(0,str(Path(os.environ.pop('WATTLY_PREFLIGHT_REPO'))/'ml/src'))
from wattly_ml.operations import audit_url
missing=[k for k in ('WATTLY_ML_HOME','COLLECTOR_DATA_DIR','WATTLY_RUN_API_URL','WATTLY_ADMIN_USER','WATTLY_ADMIN_PASSWORD','WATTLY_PROTECTED_START','WATTLY_PROTECTED_END') if not os.environ.get(k)]
if missing: raise SystemExit('Not ready; configure: '+', '.join(missing))
if os.environ.get('WATTLY_RUN_AUTH_MODE','session')!='session':
    raise SystemExit('Permanent scheduling requires the shared session login')
start,end=[datetime.fromisoformat(os.environ[k].replace('Z','+00:00')) for k in ('WATTLY_PROTECTED_START','WATTLY_PROTECTED_END')]
if start.utcoffset() is None or end.utcoffset() is None or start>=end: raise SystemExit('Invalid protected interval')
if not (Path(os.environ['COLLECTOR_DATA_DIR'])/'collector.sqlite3').is_file(): raise SystemExit('Collector database missing')
home=Path(os.environ['WATTLY_ML_HOME'])
if not home.is_dir() or not os.access(home,os.W_OK): raise SystemExit('Persistent model directory is not writable')
url,_=audit_url(os.environ['WATTLY_RUN_API_URL'])
with urlopen(url+'/actuator/health',timeout=5) as response:
    if json.load(response).get('status')!='UP': raise SystemExit('Wattly app is not healthy')
print('Runtime prerequisites present. Verify a bounded authenticated attempt and data/model eligibility before activation.')
PY
