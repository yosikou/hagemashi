"""Restore only an explicitly configured Actions secret; never generate a CI distribution key."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

os.umask(0o077)
raw = os.environ.get("SIGNING_BUNDLE", "")
if not raw:
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("available=false\n")
    print("Signing secret not configured. Checks will run; APK distribution is disabled.")
    sys.exit(0)
try:
    data = json.loads(raw)
    if data["version"] != 1:
        raise ValueError()
    password = data["password"]
    if not re.fullmatch(r"[a-f0-9]{64}", password) or not re.fullmatch(r"[a-f0-9]{64}", data["certificateSha256"]):
        raise ValueError()
    key_bytes = base64.b64decode(data["keystore"], validate=True)
    if not 1000 <= len(key_bytes) <= 20000:
        raise ValueError()
    print("::add-mask::" + password)
    print("::add-mask::" + data["keystore"])
    key = Path(os.environ["RUNNER_TEMP"]) / "hagemashi-signing.p12"
    key.write_bytes(key_bytes)
    key.chmod(0o600)
    result = subprocess.run(["keytool", "-exportcert", "-keystore", str(key), "-alias", "hagemashi-diagnostic",
                             "-storepass:env", "HAGEMASHI_KEY_PASSWORD"],
                            env=dict(os.environ, HAGEMASHI_KEY_PASSWORD=password),
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    if result.returncode != 0 or hashlib.sha256(result.stdout).hexdigest() != data["certificateSha256"]:
        raise ValueError()
    with open(os.environ["GITHUB_ENV"], "a") as output:
        output.write("HAGEMASHI_SIGNING_FILE=" + str(key) + "\nHAGEMASHI_SIGNING_PASSWORD=" + password + "\n")
        output.write("HAGEMASHI_SIGNING_SHA256=" + data["certificateSha256"] + "\n")
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("available=true\n")
    print("Stable signing certificate SHA-256: " + data["certificateSha256"])
except Exception:
    sys.exit("Invalid signing secret. No secret values or exception details will be printed.")
