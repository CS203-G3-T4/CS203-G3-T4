"""Real HTTP cookie/CSRF tests for the weekly client; no auth bypass."""
import json
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread
from urllib.error import HTTPError
from urllib.parse import parse_qs
import pytest
from wattly_ml import operations

@contextmanager
def server(bad_login=False, denied=False):
    calls=[]
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def send(self,status,value=None,cookie=None,location=None):
            self.send_response(status)
            if cookie:self.send_header('Set-Cookie',cookie)
            if location:self.send_header('Location',location)
            self.send_header('Content-Type','application/json');self.end_headers()
            if value is not None:self.wfile.write(json.dumps(value).encode())
        def do_GET(self):
            calls.append(('GET',self.path))
            assert self.path=='/api/v1/csrf'
            logged='session=admin' in self.headers.get('Cookie','')
            self.send(200,{'headerName':'X-XSRF-TOKEN','token':'after' if logged else 'before'},'xsrf=present; Path=/')
        def do_POST(self):
            calls.append(('POST',self.path))
            body=self.rfile.read(int(self.headers.get('Content-Length',0)))
            assert 'xsrf=present' in self.headers.get('Cookie','')
            if self.path=='/login':
                assert self.headers['X-XSRF-TOKEN']=='before'
                assert parse_qs(body.decode())=={'username':['fixture-admin'],'password':['fixture password & +']}
                self.send(302,cookie='session=admin; Path=/' if not bad_login else None,location='/?error' if bad_login else '/households.html')
            else:
                assert self.path=='/api/v1/admin/forecast-training-attempts'
                assert self.headers['X-XSRF-TOKEN']=='after'
                assert 'session=admin' in self.headers.get('Cookie','')
                assert json.loads(body)=={'state':'FAILED'}
                self.send(403 if denied else 200,{'saved':True})
    http=ThreadingHTTPServer(('127.0.0.1',0),Handler);t=Thread(target=http.serve_forever,daemon=True);t.start()
    try:yield f'http://127.0.0.1:{http.server_port}',calls
    finally:http.shutdown();http.server_close();t.join()

def config(monkeypatch,base):
    monkeypatch.setenv('WATTLY_RUN_API_URL',base)
    monkeypatch.setenv('WATTLY_ADMIN_USER','fixture-admin')
    monkeypatch.setenv('WATTLY_ADMIN_PASSWORD','fixture password & +')
    monkeypatch.delenv('WATTLY_RUN_AUTH_MODE',raising=False)

def test_shared_login_rotates_csrf_and_keeps_cookies(monkeypatch):
    with server() as (base,calls):
        config(monkeypatch,base)
        assert operations.post_admin('forecast-training-attempts',{'state':'FAILED'})=={'saved':True}
        assert calls==[('GET','/api/v1/csrf'),('POST','/login'),('GET','/api/v1/csrf'),('POST','/api/v1/admin/forecast-training-attempts')]

def test_invalid_login_stops_without_retry_or_audit_post(monkeypatch):
    with server(bad_login=True) as (base,calls):
        config(monkeypatch,base)
        with pytest.raises(ValueError,match='login failed'):operations.post_admin('forecast-training-attempts',{'state':'FAILED'})
        assert calls==[('GET','/api/v1/csrf'),('POST','/login')]

def test_server_role_denial_is_not_bypassed(monkeypatch):
    with server(denied=True) as (base,calls):
        config(monkeypatch,base)
        with pytest.raises(HTTPError) as error:operations.post_admin('forecast-training-attempts',{'state':'FAILED'})
        assert error.value.code==403
        assert len(calls)==4

@pytest.mark.parametrize('base',['http://example.com','https://user:password@example.com','https://example.com/?token=x'])
def test_invalid_credential_transport_rejected_before_connection(monkeypatch,base):
    config(monkeypatch,base)
    with pytest.raises(ValueError,match='HTTPS or loopback'):operations.post_admin('forecast-training-attempts',{})