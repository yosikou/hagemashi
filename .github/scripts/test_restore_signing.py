import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("restore-signing.py")


class RestoreSigningTest(unittest.TestCase):
    def restore(self, root, bundle):
        output = root / "outputs"
        environment = root / "environment"
        output.write_text("")
        environment.write_text("")
        result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True,
                                env=dict(os.environ, RUNNER_TEMP=str(root), GITHUB_OUTPUT=str(output),
                                         GITHUB_ENV=str(environment), SIGNING_BUNDLE=bundle))
        return result, output.read_text(), environment.read_text()

    def test_missing_secret_never_generates_distribution_key(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, output, environment = self.restore(root, "")
            self.assertEqual(0, result.returncode)
            self.assertEqual("available=false\n", output)
            self.assertEqual("", environment)
            self.assertFalse((root / "hagemashi-signing.p12").exists())

    def test_invalid_secret_fails_without_echoing_secret(self):
        with tempfile.TemporaryDirectory() as directory:
            result, output, environment = self.restore(Path(directory), "synthetic-private-secret")
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("synthetic-private-secret", result.stdout + result.stderr)
            self.assertNotIn("Traceback", result.stderr)
            self.assertEqual("", output)
            self.assertEqual("", environment)

    def test_same_secret_restores_same_key_across_runs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            key = root / "test.p12"
            password = "0" * 64  # Synthetic test fixture only; never used to sign distribution APKs.
            env = dict(os.environ, TEST_SIGNING_PASSWORD=password)
            result = subprocess.run(["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
                                     "-alias", "hagemashi-diagnostic", "-keyalg", "RSA", "-keysize", "2048",
                                     "-validity", "1", "-dname", "CN=Synthetic Test", "-storepass:env",
                                     "TEST_SIGNING_PASSWORD", "-keypass:env", "TEST_SIGNING_PASSWORD", "-noprompt"],
                                    env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            self.assertEqual(0, result.returncode)
            cert = subprocess.run(["keytool", "-exportcert", "-keystore", str(key), "-alias", "hagemashi-diagnostic",
                                   "-storepass:env", "TEST_SIGNING_PASSWORD"], env=env,
                                  capture_output=True, check=True).stdout
            bundle = json.dumps(dict(version=1, keystore=base64.b64encode(key.read_bytes()).decode(),
                                     password=password, certificateSha256=hashlib.sha256(cert).hexdigest()))
            for run in ["first", "second"]:
                temporary = root / run
                temporary.mkdir()
                result, output, environment = self.restore(temporary, bundle)
                self.assertEqual(0, result.returncode)
                self.assertEqual("available=true\n", output)
                self.assertEqual(key.read_bytes(), (temporary / "hagemashi-signing.p12").read_bytes())
                self.assertEqual(0o600, (temporary / "hagemashi-signing.p12").stat().st_mode & 0o777)
                self.assertIn("HAGEMASHI_SIGNING_PASSWORD=", environment)
                lines = [line for line in result.stdout.splitlines() if not line.startswith("::add-mask::")]
                self.assertNotIn(password, "\n".join(lines))
                self.assertNotIn(bundle, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
