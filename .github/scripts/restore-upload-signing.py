"""Restore an owner-managed upload key. Never reuse or generate a debug key in CI."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

os.umask(0o077)
raw = os.environ.get("UPLOAD_SIGNING_BUNDLE", "")
if not raw:
    with open(os.environ["GITHUB_OUTPUT"], "a") as f:
        f.write("available=false\n")
    print("Upload key not configured. No signed AAB will be distributed.")
    sys.exit(0)
key = Path(os.environ["RUNNER_TEMP"]) / "hagemashi-upload.p12"
try:
    data = json.loads(raw)
    if data["version"] != 1 or data["purpose"] != "play-upload" or data["alias"] != "hagemashi-upload":
        raise ValueError()
    password = data["password"]
    fingerprint = data["certificateSha256"]
    if not re.fullmatch(r"[a-f0-9]{64}", password) or not re.fullmatch(r"[a-f0-9]{64}", fingerprint):
        raise ValueError()
    encoded = data["keystore"]
    decoded = base64.b64decode(encoded, validate=True)
    if not 1000 <= len(decoded) <= 20000:
        raise ValueError()
    print("::add-mask::" + password)
    print("::add-mask::" + encoded)
    key.write_bytes(decoded)
    key.chmod(0o600)
    environment = dict(os.environ, UPLOAD_PASSWORD=password)
    result = subprocess.run(["keytool", "-exportcert", "-keystore", str(key), "-alias", data["alias"],
                             "-storepass:env", "UPLOAD_PASSWORD"], env=environment,
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if result.returncode or hashlib.sha256(result.stdout).hexdigest() != fingerprint:
        raise ValueError()
    with open(os.environ["GITHUB_ENV"], "a") as f:
        f.write("HAGEMASHI_UPLOAD_FILE=" + str(key) + "\nHAGEMASHI_UPLOAD_PASSWORD=" + password + "\n")
        f.write("HAGEMASHI_UPLOAD_SHA256=" + fingerprint + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as f:
        f.write("available=true\n")
    print("Upload certificate SHA-256: " + fingerprint)
except Exception:
    key.unlink(missing_ok=True)
    sys.exit("Invalid upload signing secret. No secret values or exception details are printed.")
