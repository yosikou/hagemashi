"""Validate release structure, identity, API, test ads and optional upload-key signature."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).parent
spec = importlib.util.spec_from_file_location("ad_artifact", ROOT / "ad-artifact.py")
ad = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ad)
ANDROID = "{http://schemas.android.com/apk/res/android}"
PHASE = "START"


def tool(*args):
    result = subprocess.run(["java", "-jar", os.environ["BUNDLETOOL_JAR"], *args], capture_output=True, text=True)
    if result.returncode:
        raise ValueError("Bundletool validation failed")
    return result.stdout


def verify(args):
    global PHASE
    path = Path(args.aab)
    PHASE = "BUNDLETOOL_VALIDATE"
    tool("validate", "--bundle=" + str(path))
    PHASE = "MANIFEST_IDENTITY"
    manifest = ET.fromstring(tool("dump", "manifest", "--bundle=" + str(path), "--module=base"))
    if manifest.attrib.get("package") != "com.yosikou.hagemashi":
        raise ValueError("Unexpected package")
    if manifest.attrib.get(ANDROID + "versionCode") != str(args.version_code) or manifest.attrib.get(ANDROID + "versionName") != args.version_name:
        raise ValueError("Unexpected version")
    sdk = manifest.find("uses-sdk")
    if sdk is None or sdk.attrib.get(ANDROID + "targetSdkVersion") != "36" or sdk.attrib.get(ANDROID + "minSdkVersion") != "26":
        raise ValueError("Unexpected SDK levels")
    application = manifest.find("application")
    if application is None or application.attrib.get(ANDROID + "debuggable", "false") != "false":
        raise ValueError("Release is debuggable")
    PHASE = "TEST_ADS"
    ad.inspect(path, "test")
    PHASE = "PAYLOAD_AND_COMPONENTS"
    libraries = []
    with zipfile.ZipFile(path) as z:
        if z.testzip() is not None:
            raise ValueError("Corrupt ZIP entry")
        for required in ("BundleConfig.pb", "base/manifest/AndroidManifest.xml", "base/resources.pb", "base/dex/classes.dex"):
            if required not in z.namelist():
                raise ValueError("Incomplete bundle structure")
        info = json.loads(z.read("base/assets/build-info.json"))
        if info != {"sourceCommit": args.commit, "versionCode": args.version_code, "versionName": args.version_name,
                    "targetSdk": 36, "minSdk": 26, "minifyEnabled": False, "shrinkResources": False, "languageSplitsEnabled": False}:
            raise ValueError("Build metadata mismatch")
        dex = b"".join(z.read(n) for n in z.namelist() if n.startswith("base/dex/") and n.endswith(".dex"))
        for name in ("MainActivity", "ConnectionRepository", "HealthConnectSource", "ScheduledGenerationService",
                     "ReservationReceiver", "AnswerStore", "SettingsStore", "GenerationSendGate"):
            if ("Lcom/yosikou/hagemashi/" + name + ";").encode() not in dex:
                raise ValueError("Required component removed")
        for name in z.namelist():
            if name.endswith(".so") and ("/arm64-v8a/" in name or "/x86_64/" in name):
                data = z.read(name)
                if data[:6] != b"\x7fELF\x02\x01":
                    raise ValueError("Unsupported native library encoding")
                offset = struct.unpack_from("<Q", data, 32)[0]
                size, count = struct.unpack_from("<HH", data, 54)
                for index in range(count):
                    header = struct.unpack_from("<IIQQQQQQ", data, offset + size * index)
                    if header[0] == 1 and header[-1] < 16384:
                        raise ValueError("Native library is not 16 KB aligned")
                libraries.append(name)
    PHASE = "BUNDLE_CONFIG"
    config_text = tool("dump", "config", "--bundle=" + str(path))
    config = json.loads(config_text)
    dimensions = config.get("optimizations", {}).get("splitsConfig", {}).get("splitDimension", [])
    if not any(d.get("value") == "LANGUAGE" and d.get("negate") is True for d in dimensions):
        raise ValueError("Language splitting must remain disabled")
    if libraries and "PAGE_ALIGNMENT_16K" not in config_text:
        raise ValueError("Bundle packaging is not 16 KB aligned")
    PHASE = "UPLOAD_SIGNATURE"
    if args.certificate:
        result = subprocess.run(["java", str(ROOT / "VerifyAabSignature.java"), str(path), args.certificate],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        if result.returncode:
            raise ValueError("Upload signature rejected")
    report = {"file": path.name, **info, "applicationId": "com.yosikou.hagemashi", "ads": "test",
              "admobApplicationId": ad.DEMO_APP, "bannerUnitId": ad.DEMO_UNIT,
              "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "bundletoolVersion": "1.18.3",
              "bundleStructureValidated": True, "requiredComponentsPresent": True,
              "aligned64BitNativeLibraries": libraries, "uploadSignatureVerified": bool(args.certificate),
              "uploadCertificateSha256": args.certificate, "readyForUpload": bool(args.certificate)}
    Path(args.report).write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--aab", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--commit", required=True)
    p.add_argument("--certificate")
    p.add_argument("--report", required=True)
    args = p.parse_args()
    try:
        verify(args)
    except Exception:
        # No certificate, payload, secret or arbitrary tool exception is printed on failure.
        sys.exit("Closed-test AAB verification failed at fixed stage: " + PHASE)
