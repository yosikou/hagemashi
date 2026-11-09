"""Validate public APK metadata and certificates before either APK is distributed."""
import os
from pathlib import Path
import re
import subprocess
import sys

tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
expected = os.environ["HAGEMASHI_SIGNING_SHA256"]
for variant, package in [("debug", "com.yosikou.hagemashi"), ("diagnostic", "com.yosikou.hagemashi.diagnostic")]:
    apk = f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    signature = subprocess.run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", apk],
                               capture_output=True, text=True)
    fingerprints = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)", signature.stdout)
    if signature.returncode or fingerprints != [expected]:
        sys.exit("APK signing verification failed. Distribution stopped.")
    manifest = subprocess.run([str(tools / "aapt"), "dump", "badging", apk], capture_output=True, text=True, check=True).stdout
    if f"package: name='{package}'" not in manifest or "uses-permission: name='android.permission.INTERNET'" not in manifest:
        sys.exit("APK package or INTERNET permission check failed. Distribution stopped.")
    print(f"Verified {variant}: package={package}, INTERNET=true, certificateSha256={expected}")
