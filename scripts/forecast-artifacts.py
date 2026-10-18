"""Build/verify/restore frozen research evidence without keeping generated files in source."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def build(manifest, output, repository=ROOT):
    with output.open('xb') as stream, gzip.GzipFile(filename='', mode='wb', fileobj=stream, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w') as archive:
            files = [('MANIFEST.json', json.dumps({k: manifest[k] for k in ('sourceCommit', 'files')},
                                                sort_keys=True).encode())]
            for entry in manifest['files']:
                data = subprocess.check_output(['git', 'show', manifest['sourceCommit'] + ':' + entry['path']], cwd=repository)
                if len(data) != entry['bytes'] or digest(data) != entry['sha256']:
                    raise ValueError('Source evidence changed: ' + entry['path'])
                files.append((entry['path'], data))
            for name, data in files:
                info = tarfile.TarInfo(name)
                info.size = len(data); info.mode = 0o644
                archive.addfile(info, io.BytesIO(data))
    return digest(output.read_bytes())


def verify(archive, manifest):
    if digest(archive.read_bytes()) != manifest['archiveSha256']:
        raise ValueError('Archive checksum mismatch')
    with tarfile.open(archive, 'r:gz') as stream:
        members = stream.getmembers()
        expected = {e['path']: e for e in manifest['files']}
        if len(members) != len(expected) + 1 or {m.name for m in members} != set(expected) | {'MANIFEST.json'}:
            raise ValueError('Unexpected or duplicate archive entries')
        result = {}
        for member in members:
            if not member.isfile() or PurePosixPath(member.name).is_absolute() or '..' in PurePosixPath(member.name).parts:
                raise ValueError('Unsafe archive entry')
            data = stream.extractfile(member).read()
            if member.name == 'MANIFEST.json':
                if json.loads(data) != {k: manifest[k] for k in ('sourceCommit', 'files')}:
                    raise ValueError('Archive manifest mismatch')
            elif len(data) != expected[member.name]['bytes'] or digest(data) != expected[member.name]['sha256']:
                raise ValueError('Evidence checksum mismatch: ' + member.name)
            result[member.name] = data
        return result


def restore(archive, manifest, directory):
    files = verify(archive, manifest)
    directory.mkdir(parents=True, exist_ok=False)
    for name, data in files.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['build', 'verify', 'restore'])
    parser.add_argument('archive', type=Path)
    parser.add_argument('destination', type=Path, nargs='?')
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'docs/forecasting/artifacts.json').read_text())
    if args.command == 'build':
        if build(manifest, args.archive) != manifest['archiveSha256']:
            raise ValueError('Built archive differs from the frozen checksum')
    elif args.command == 'restore':
        if args.destination is None:
            parser.error('restore requires a new destination directory')
        restore(args.archive, manifest, args.destination)
    else:
        verify(args.archive, manifest)
    print(json.dumps({'status': 'PASS', 'command': args.command, 'files': len(manifest['files']),
                      'archiveSha256': manifest['archiveSha256']}))


if __name__ == '__main__':
    main()
