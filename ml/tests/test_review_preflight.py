"""Transport-policy and saved-data parsing regressions; no real credentials/data."""
from pathlib import Path
import gzip,json,os,runpy,subprocess,sys
import pytest
from wattly_ml.operations import audit_url
from test_operations_http import server

@pytest.mark.parametrize('url', ['http://example.com','http://192.0.2.1','ftp://localhost','https://u:p@example.com','https://example.com/?token=x','https://example.com/#x'])
def test_audit_transport_rejects_unsafe_url(url):
    with pytest.raises(ValueError):audit_url(url)

@pytest.mark.parametrize('url,loopback',[('https://example.com',False),('http://localhost',True),('http://127.0.0.2:8080',True),('http://[::1]:8080',True)])
def test_audit_transport_accepts_tls_or_literal_loopback(url,loopback):
    assert audit_url(url)==(url,loopback)

def test_shell_preflight_rejects_remote_http_before_network(tmp_path):
    root=Path(__file__).resolve().parents[2]
    home=tmp_path/'models';home.mkdir();collector=tmp_path/'collector';collector.mkdir();(collector/'collector.sqlite3').touch()
    values=dict(WATTLY_PYTHON=sys.executable,WATTLY_ML_HOME=str(home),COLLECTOR_DATA_DIR=str(collector),
        WATTLY_RUN_API_URL='http://192.0.2.1:1',WATTLY_ADMIN_USER='fixture',WATTLY_ADMIN_PASSWORD='fixture',
        WATTLY_PROTECTED_START='2026-01-01T00:00:00Z',WATTLY_PROTECTED_END='2026-02-01T00:00:00Z')
    import shlex
    env=tmp_path/'weekly.env';env.write_text(''.join(k+'='+shlex.quote(v)+'\n' for k,v in values.items()));env.chmod(0o600)
    p=subprocess.run(['bash',str(root/'scripts/forecast-weekly-preflight.sh'),str(env)],capture_output=True,text=True,timeout=10)
    assert p.returncode!=0 and 'HTTPS or loopback HTTP' in p.stderr
