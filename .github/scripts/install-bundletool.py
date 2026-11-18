"""Fetch a pinned official public validation tool; never place it in distribution artifacts."""
import hashlib
import os
from pathlib import Path
import sys
import urllib.request

VERSION = "1.18.3"
DIGEST = "a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
try:
    path = Path(os.environ["RUNNER_TEMP"]) / "bundletool.jar"
    data = urllib.request.urlopen("https://github.com/google/bundletool/releases/download/" + VERSION + "/bundletool-all-" + VERSION + ".jar", timeout=120).read()
    if hashlib.sha256(data).hexdigest() != DIGEST:
        raise ValueError()
    path.write_bytes(data)
    with open(os.environ["GITHUB_ENV"], "a") as f:
        f.write("BUNDLETOOL_JAR=" + str(path) + "\n")
    print("Verified official bundletool " + VERSION)
except Exception:
    sys.exit("Unable to verify bundletool. No bundle will be distributed.")
