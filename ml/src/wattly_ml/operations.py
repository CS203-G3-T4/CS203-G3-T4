"""Durable weekly-attempt audit; local spool survives a database/API outage."""
import base64
from datetime import datetime, timezone, timedelta
import fcntl
import json
import os
from pathlib import Path
import signal
import tempfile
from urllib.request import Request, build_opener, HTTPCookieProcessor, HTTPRedirectHandler
from urllib.error import HTTPError
from urllib.parse import urlencode, urlsplit
from http.cookiejar import CookieJar
import ipaddress
import uuid


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def post_admin(path, value):
    """Use the existing shared login/CSRF contract; never weaken server guards."""
    base = os.environ.get('WATTLY_RUN_API_URL')
    if not base:
        return False
    base = base.rstrip('/')
    parsed = urlsplit(base)
    try:
        loopback = parsed.hostname == 'localhost' or ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        loopback = False
    if (parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username
            or parsed.password or parsed.query or parsed.fragment
            or (parsed.scheme == 'http' and not loopback)):
        raise ValueError('Training audit URL requires HTTPS or loopback HTTP')
    if path not in ('forecast-training-attempts', 'forecast-baseline-validation', 'forecast-demand', 'forecast-savings-replay'):
        raise ValueError('Unsupported training audit endpoint')
    user, password = os.environ.get('WATTLY_ADMIN_USER'), os.environ.get('WATTLY_ADMIN_PASSWORD')
    if not user or not password:
        raise ValueError('Admin credentials required for training-run persistence')
    mode = os.environ.get('WATTLY_RUN_AUTH_MODE', 'session')
    if mode not in ('session', 'local-basic') or (mode == 'local-basic' and not loopback):
        raise ValueError('Use session authentication; local-basic is restricted to loopback demos')
    opener = build_opener(HTTPCookieProcessor(CookieJar()), _NoRedirect())

    def csrf():
        with opener.open(Request(base+'/api/v1/csrf'), timeout=10) as response:
            token = json.load(response)
        if token.get('headerName') != 'X-XSRF-TOKEN' or not isinstance(token.get('token'), str) or not token['token']:
            raise ValueError('Invalid training audit CSRF response')
        return {token['headerName']: token['token']}

    headers = csrf()
    if mode == 'session':
        login = Request(base+'/login', data=urlencode({'username':user, 'password':password}).encode(),
                        headers={**headers, 'Content-Type':'application/x-www-form-urlencoded'})
        try:
            with opener.open(login, timeout=10) as response:
                if response.status != 200:
                    raise ValueError('Training audit login failed')
        except HTTPError as exc:
            # Spring redirects after login. Do not follow it or forward credentials.
            location = exc.headers.get('Location', '')
            if exc.code not in (302, 303) or not location or 'error' in urlsplit(location).query:
                raise ValueError('Training audit login failed') from None
        headers = csrf()  # Successful authentication rotates the CSRF token.
    else:
        token = base64.b64encode((user+':'+password).encode()).decode()
        headers['Authorization'] = 'Basic '+token
    request = Request(base+'/api/v1/admin/'+path,
                      data=json.dumps(value, allow_nan=False).encode(),
                      headers={**headers, 'Content-Type':'application/json'})
    with opener.open(request, timeout=10) as response:
        if response.status != 200:
            raise OSError('Training audit API did not acknowledge the record')
        return json.load(response)


class TrainingAttempt:
    def __init__(self, args):
        home = Path(os.environ.get('WATTLY_ML_HOME', str(args.output.parent/'weekly-runtime'))).expanduser()
        self.directory = home/'training-attempts'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory/(uuid.uuid4().hex+'.json')
        self.value = {'attemptId':self.path.stem, 'modelVersion':args.version,
            'trainingDate':datetime.now(timezone.utc).isoformat(), 'trainingStart':args.start.isoformat(),
            'trainingEnd':args.cutoff.isoformat(), 'validationEnd':args.end.isoformat(),
            'dataset':str(args.dataset), 'datasetDigest':'', 'scores':{}, 'state':'RUNNING',
            'reason':'', 'promotionDecision':'NOT_ATTEMPTED'}
        self.stage = 'TRAINING'
        self.lock = None
        self.signal_handler = None
        self.save()

    def save(self):
        with tempfile.NamedTemporaryFile(mode='w',dir=self.directory,delete=False) as out:
            json.dump(self.value,out,allow_nan=False,indent=2); out.flush(); os.fsync(out.fileno()); name=out.name
        os.replace(name,self.path)

    def start(self):
        post_admin('forecast-training-attempts', self.value)
        self.lock = (self.directory.parent/'training.lock').open('a')
        fcntl.flock(self.lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        if __import__('threading').current_thread() is __import__('threading').main_thread():
            def interrupted(signum, frame):
                raise InterruptedError('Weekly training interrupted by signal '+str(signum))
            self.signal_handler=signal.signal(signal.SIGTERM, interrupted)

    def update(self, **fields):
        self.value.update(fields); self.save()
        post_admin('forecast-training-attempts',self.value)

    def finish(self, state, reason='', decision='RETAINED'):
        self.value.update(state=state,reason=reason,promotionDecision=decision); self.save()
        try:
            persisted=post_admin('forecast-training-attempts',self.value)
            if not persisted:
                self.path.with_suffix('.pending').write_text('Database sync pending; configure WATTLY_RUN_API_URL and run sync-runs\n')
        except (OSError,ValueError):
            # The exact final record is retained for sync-runs; never rewrite its outcome.
            self.path.with_suffix('.pending').write_text('Audit API unavailable; exact final outcome retained for sync-runs\n')

    def close(self):
        if self.value['state']=='RUNNING':
            self.finish('FAILED','Attempt interrupted before completion')
        if self.signal_handler is not None:
            signal.signal(signal.SIGTERM,self.signal_handler)
        if self.lock:
            self.lock.close()


def sync_runs(home):
    if not os.environ.get('WATTLY_RUN_API_URL'):
        raise ValueError('Set WATTLY_RUN_API_URL before syncing records')
    count=0
    for path in sorted((home/'training-attempts').glob('*.json')):
        value=json.loads(path.read_text())
        if value['state']=='RUNNING' and datetime.now(timezone.utc)-datetime.fromisoformat(value['trainingDate'])>timedelta(hours=1):
            with (home/'training.lock').open('a') as lock:
                try:
                    fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                except BlockingIOError:
                    continue
                value.update(state='FAILED',reason='Abandoned bounded attempt recovered by next run',promotionDecision='RETAINED')
                path.write_text(json.dumps(value,indent=2)+'\n')
        post_admin('forecast-training-attempts',value); count+=1
        path.with_suffix('.pending').unlink(missing_ok=True)
    return {'synced':count}
