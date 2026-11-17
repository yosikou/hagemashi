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

# Enforce isolation on the actual APKs, not just Gradle source declarations.
import zipfile
sample_app = "ca-app-pub-3940256099942544~3347511713"
sample_banner = b"ca-app-pub-3940256099942544/9214589741"
for variant in ("debug", "diagnostic"):
    apk = f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    xml = subprocess.run([str(tools / "aapt"), "dump", "xmltree", apk, "AndroidManifest.xml"],
                         capture_output=True, text=True, check=True).stdout
    with zipfile.ZipFile(apk) as archive:
        dex = [archive.read(n) for n in archive.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
    sdk_present = any(b"Lcom/google/android/gms/ads/AdView;" in data for data in dex)
    banner_present = any(sample_banner in data for data in dex)
    if variant == "debug":
        if sdk_present or banner_present or "com.google.android.gms.ads.APPLICATION_ID" in xml:
            sys.exit("Normal APK unexpectedly contains advertising. Distribution stopped.")
    else:
        if not sdk_present or not banner_present or sample_app not in xml:
            sys.exit("Diagnostic test ads configuration missing. Distribution stopped.")
        for permission in ("com.google.android.gms.permission.AD_ID", "android.permission.ACCESS_ADSERVICES_AD_ID",
                           "android.permission.ACCESS_ADSERVICES_ATTRIBUTION", "android.permission.ACCESS_ADSERVICES_TOPICS"):
            if permission in xml:
                sys.exit("Diagnostic APK includes an excluded ads permission. Distribution stopped.")
    print(f"Verified {variant}: testAdsSdk={sdk_present}")
