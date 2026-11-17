import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("ad_artifact", Path(__file__).with_name("ad-artifact.py"))
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class AdArtifactTest(unittest.TestCase):
    def check(self, mode, corrupt=None):
        app, unit = (mod.LIVE_APP, mod.LIVE_UNIT) if mode == "production" else (mod.DEMO_APP, mod.DEMO_UNIT) if mode == "test" else ("", "")
        cfg = {"schema": 1, "mode": mode, "applicationId": app, "bannerUnitId": unit,
               "privacyGate": "UMP" if mode == "production" else "TEST_ONLY"}
        dex = ("Lcom/google/android/gms/ads/AdView; Lcom/google/android/ump/UserMessagingPlatform; " + unit).encode() if mode != "off" else b"app classes"
        manifest = (app + unit).encode() if mode != "off" else b"manifest"
        if corrupt == "descriptor":
            cfg["mode"] = "production"
        if corrupt == "unit":
            dex = dex.replace(unit.encode(), b"wrong banner")
        if corrupt == "live":
            dex += mod.LIVE_UNIT.encode()
        if corrupt == "permission":
            manifest += b"com.google.android.gms.permission.AD_ID"
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "test.aab"
            with zipfile.ZipFile(path, "w") as z:
                z.writestr("base/assets/ad-config.json", json.dumps(cfg))
                z.writestr("base/manifest/AndroidManifest.xml", manifest)
                z.writestr("base/dex/classes.dex", dex)
            return mod.inspect(path, mode)

    def test_all_modes_can_be_inspected_without_launching_an_ad(self):
        for mode in ("off", "test", "production"):
            self.assertTrue(self.check(mode)["manifestAndDexVerified"])

    def test_wrong_descriptor_and_wrong_actual_banner_are_rejected(self):
        for corruption in ("descriptor", "unit"):
            with self.assertRaises(AssertionError):
                self.check("test", corruption)

    def test_developer_artifact_with_live_id_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check("test", "live")

    def test_unwanted_permission_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check("production", "permission")
