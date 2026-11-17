"""Inspect build mode and actual IDs without installing or starting an advertisement."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile

DEMO_APP = "ca-app-pub-3940256099942544~3347511713"
DEMO_UNIT = "ca-app-pub-3940256099942544/9214589741"
LIVE_APP = "ca-app-pub-3595062014413653~8954573313"
LIVE_UNIT = "ca-app-pub-3595062014413653/1814449890"


def inspect(path, mode):
    bundle = str(path).endswith(".aab")
    prefix = "base/" if bundle else ""
    with zipfile.ZipFile(path) as z:
        descriptor = json.loads(z.read(prefix + "assets/ad-config.json"))
        app, unit = (LIVE_APP, LIVE_UNIT) if mode == "production" else (DEMO_APP, DEMO_UNIT) if mode == "test" else ("", "")
        assert descriptor == {"schema": 1, "mode": mode, "applicationId": app, "bannerUnitId": unit,
                              "privacyGate": "UMP" if mode == "production" else "TEST_ONLY"}, "wrong ad descriptor"
        dex = b"".join(z.read(n) for n in z.namelist() if re.fullmatch(r"base/dex/classes\d*\.dex" if bundle else r"classes\d*\.dex", n))
        manifest = z.read(prefix + "manifest/AndroidManifest.xml" if bundle else "AndroidManifest.xml")
        if not bundle:
            aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0/aapt"
            xml = subprocess.check_output([str(aapt), "dump", "xmltree", str(path), "AndroidManifest.xml"], text=True)
            manifest = xml.encode()
        if mode == "off":
            assert b"Lcom/google/android/gms/ads/AdView;" not in dex
            assert b"com.google.android.gms.ads.APPLICATION_ID" not in manifest
        else:
            assert b"Lcom/google/android/gms/ads/AdView;" in dex, "ad SDK missing"
            assert unit.encode() in dex, "configured banner missing from DEX"
            assert app.encode() in manifest and unit.encode() in manifest, "ad manifest IDs do not match descriptor"
            if mode == "production":
                assert b"Lcom/google/android/ump/UserMessagingPlatform;" in dex, "UMP gate missing"
            for permission in ("com.google.android.gms.permission.AD_ID", "android.permission.ACCESS_ADSERVICES_AD_ID",
                               "android.permission.ACCESS_ADSERVICES_ATTRIBUTION", "android.permission.ACCESS_ADSERVICES_TOPICS"):
                assert permission.encode() not in manifest, "excluded permission present"
        if mode != "production":
            assert LIVE_APP.encode() not in dex and LIVE_UNIT.encode() not in dex, "live ID in development DEX"
            assert LIVE_APP.encode() not in manifest and LIVE_UNIT.encode() not in manifest, "live ID in test/off manifest"
        return {"artifact": str(path), **descriptor, "manifestAndDexVerified": True}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--mode", choices=("off", "test", "production"), required=True)
    p.add_argument("--apk", required=True)
    p.add_argument("--aab")
    p.add_argument("--report")
    args = p.parse_args()
    results = [inspect(args.apk, args.mode)]
    if args.aab:
        results.append(inspect(args.aab, args.mode))
    output = json.dumps(results, indent=2)
    if args.report:
        Path(args.report).write_text(output)
    print(output)
