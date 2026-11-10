"""Fetch the pinned forecast bundle into WATTLY_ML_HOME once [CSDT4-35].

The zip must contain models/<version>/manifest.json and models.joblib. The manifest
must match WATTLY_EXPERIMENTAL_MANIFEST_SHA256 and the model must match the manifest's
modelSha256 before anything is placed; the serving app checks both again on load.
The URL is a private secret; it is never logged.
"""
import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

FILES = ("manifest.json", "models.joblib")


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    home = Path(os.environ["WATTLY_ML_HOME"])
    version = os.environ.get("WATTLY_EXPERIMENTAL_MODEL_VERSION", "")
    pin = os.environ.get("WATTLY_EXPERIMENTAL_MANIFEST_SHA256", "")
    if not version or "/" in version or version.startswith(".") or len(pin) != 64:
        print("Set WATTLY_EXPERIMENTAL_MODEL_VERSION and WATTLY_EXPERIMENTAL_MANIFEST_SHA256", file=sys.stderr)
        return 1
    target = home / "models" / version
    manifest = target / "manifest.json"
    if manifest.is_file() and sha256(manifest.read_bytes()) == pin:
        print(f"Forecast model {version} already present")
        return 0
    with urllib.request.urlopen(os.environ["WATTLY_MODEL_ZIP_URL"], timeout=60) as response:
        archive = response.read(50_000_000)
    with zipfile.ZipFile(io.BytesIO(archive)) as bundle:
        contents = {name: bundle.read(f"models/{version}/{name}") for name in FILES}
    if sha256(contents["manifest.json"]) != pin:
        print("Downloaded manifest does not match the pinned SHA-256", file=sys.stderr)
        return 1
    if sha256(contents["models.joblib"]) != json.loads(contents["manifest.json"])["modelSha256"]:
        print("Downloaded model does not match its manifest", file=sys.stderr)
        return 1
    staging = Path(tempfile.mkdtemp(dir=home / "models", prefix=".fetch-"))
    for name, data in contents.items():
        (staging / name).write_bytes(data)
    if target.exists():
        shutil.rmtree(target)
    staging.rename(target)
    print(f"Forecast model {version} installed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
