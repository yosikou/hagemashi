import hashlib
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name("VerifyAabSignature.java").resolve()


class AabSignatureTest(unittest.TestCase):
    def test_valid_signature_wrong_certificate_unsigned_and_modified_payload(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            key = root / "synthetic.p12"
            env = dict(os.environ, SYNTHETIC_PASSWORD="synthetic-fixture-password-only")
            def command(args):
                return subprocess.run(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            result = command(["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12", "-alias", "fixture",
                              "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Synthetic Test",
                              "-storepass:env", "SYNTHETIC_PASSWORD", "-noprompt"])
            self.assertEqual(0, result.returncode)
            cert = command(["keytool", "-exportcert", "-keystore", str(key), "-alias", "fixture",
                            "-storepass:env", "SYNTHETIC_PASSWORD"]).stdout
            fingerprint = hashlib.sha256(cert).hexdigest()
            source = root / "unsigned.aab"
            with zipfile.ZipFile(source, "w") as z:
                z.writestr("base/dex/classes.dex", b"synthetic payload")
                z.writestr("META-INF/source.properties", b"synthetic metadata")
            signed = root / "signed.aab"
            result = command((["jarsigner"] if shutil.which("jarsigner") else ["java", "-m", "jdk.jartool/sun.security.tools.jarsigner.Main"]) + ["-keystore", str(key), "-storepass:env", "SYNTHETIC_PASSWORD",
                              "-signedjar", str(signed), str(source), "fixture"])
            self.assertEqual(0, result.returncode)
            def verify(path, sha):
                return command(["java", str(SCRIPT), str(path), sha]).returncode
            self.assertEqual(0, verify(signed, fingerprint))
            self.assertNotEqual(0, verify(source, fingerprint))
            self.assertNotEqual(0, verify(signed, "0" * 64))
            injected = root / "injected.aab"
            injected.write_bytes(signed.read_bytes())
            with zipfile.ZipFile(injected, "a") as z:
                z.writestr("base/unsigned-extra.txt", "must reject")
            self.assertNotEqual(0, verify(injected, fingerprint))
            modified = root / "modified.aab"
            with zipfile.ZipFile(signed) as original, zipfile.ZipFile(modified, "w") as target:
                for name in original.namelist():
                    target.writestr(name, b"modified" if name == "base/dex/classes.dex" else original.read(name))
            self.assertNotEqual(0, verify(modified, fingerprint))
