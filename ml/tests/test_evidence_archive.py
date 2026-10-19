import importlib.util
import json
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]


def script(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_archive_is_deterministic_verified_and_never_overwrites(tmp_path):
    archive = script('forecast-artifacts')
    repo = tmp_path / 'repo'; repo.mkdir()
    subprocess.run(['git', 'init', '-q', str(repo)], check=True)
    (repo / 'result.json').write_text('{"mae":12,"modelledSavings":-3}\n')
    subprocess.run(['git', '-C', str(repo), 'add', 'result.json'], check=True)
    subprocess.run(['git', '-C', str(repo), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                    'commit', '-qm', 'fixture'], check=True)
    raw = (repo / 'result.json').read_bytes()
    manifest = {'sourceCommit': subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip(),
        'files': [{'path': 'result.json', 'bytes': len(raw), 'sha256': archive.digest(raw)}]}
    a, b = tmp_path / 'a.tar.gz', tmp_path / 'b.tar.gz'
    manifest['archiveSha256'] = archive.build(manifest, a, repo)
    assert archive.build(manifest, b, repo) == manifest['archiveSha256']
    assert archive.verify(a, manifest)['result.json'] == raw
    archive.restore(a, manifest, tmp_path / 'restored')
    assert (tmp_path / 'restored/result.json').read_bytes() == raw
    with pytest.raises(FileExistsError):
        archive.restore(a, manifest, tmp_path / 'restored')
    with pytest.raises(FileExistsError):
        archive.build(manifest, a, repo)
    a.write_bytes(a.read_bytes() + b'changed')
    with pytest.raises(ValueError, match='checksum'):
        archive.verify(a, manifest)


def test_bundled_public_report_is_small_real_allowlisted_projection():
    project = script('compact-forecast-demo').project
    report = json.loads((ROOT / 'src/main/resources/static/forecast-demo-latest.json').read_text())
    assert report['actionable'] is False and report['productionApproved'] is False
    assert len(report['origins']) == 1 and len(report['origins'][0]['points']) == 24
    assert report['test']['eligibleOrigins'] == 24 and report['test']['commonPairs'] == 576
    assert report['test']['table']['AI']['mae'] == pytest.approx(224.407932695209)
    assert 'Real saved historical diagnostic' in report['presentationScope']
    assert (ROOT / 'src/main/resources/static/forecast-demo-latest.json').stat().st_size < 15000
    # Unrecognised/private fields never survive publication; source values remain untouched.
    source = {**report, 'privateToken': 'never-publish', 'customer': {'id': 'private'}}
    source['protocol']['privatePath'] = '/private/model'
    projected = project(source, 'a' * 64)
    assert 'privateToken' not in projected and 'customer' not in projected
    assert 'privatePath' not in projected['protocol']
    assert source['protocol']['privatePath'] == '/private/model'
