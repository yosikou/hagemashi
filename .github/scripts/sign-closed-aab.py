"""Sign only the explicitly built test-ad release; all secret input travels through env/stdin."""
import os
from pathlib import Path
import subprocess
import sys

os.umask(0o077)
try:
    output = Path(os.environ["AAB_OUTPUT"])
    output.parent.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(["jarsigner", "-keystore", os.environ["HAGEMASHI_UPLOAD_FILE"], "-storetype", "PKCS12",
                             "-storepass:env", "HAGEMASHI_UPLOAD_PASSWORD", "-keypass:env", "HAGEMASHI_UPLOAD_PASSWORD",
                             "-sigalg", "SHA256withRSA", "-digestalg", "SHA-256", "-signedjar", str(output),
                             "app/build/outputs/bundle/release/app-release.aab", "hagemashi-upload"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if result.returncode:
        raise ValueError()
    public = subprocess.run(["keytool", "-exportcert", "-rfc", "-keystore", os.environ["HAGEMASHI_UPLOAD_FILE"],
                             "-alias", "hagemashi-upload", "-storepass:env", "HAGEMASHI_UPLOAD_PASSWORD"],
                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True).stdout
    (output.parent / "upload-certificate.pem").write_bytes(public)
    print("Signed release AAB with the separate owner-managed upload key.")
except Exception:
    Path(os.environ["AAB_OUTPUT"]).unlink(missing_ok=True)
    sys.exit("AAB signing failed. No secret values or exception details are printed.")
