#!/usr/bin/env python3
"""Run on the owner's private Termux device, never in Actions or a shared log."""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile

REPO = "yosikou/hagemashi"
SECRET = "HAGEMASHI_UPLOAD_SIGNING_BUNDLE"
ALIAS = "hagemashi-upload"


def command(args, **kwargs):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, **kwargs)
    if result.returncode:
        sys.exit("操作に失敗しました。ghの認証・repo権限とkeytoolの導入を確認してください。秘密値は表示しません。")
    return result.stdout


def main():
    os.umask(0o077)
    if os.environ.get("GITHUB_ACTIONS") or any(not shutil.which(x) for x in ["gh", "keytool"]):
        sys.exit("個人のTermuxで、gh・keytoolを導入して実行してください。")
    existing = json.loads(command(["gh", "secret", "list", "--repo", REPO, "--json", "name"]))
    if any(item["name"] == SECRET for item in existing):
        sys.exit("Secretは登録済みです。鍵を上書きせず終了します。")
    directory = Path.home() / ".local/share/hagemashi-upload-signing"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    backup = directory / "upload-signing-bundle.json"
    if backup.exists():
        data = json.loads(backup.read_text())
    else:
        password = secrets.token_hex(32)
        env = dict(os.environ, HAGEMASHI_KEY_PASSWORD=password)
        with tempfile.TemporaryDirectory(dir=directory) as temp:
            key = Path(temp) / "upload.p12"
            command(["keytool", "-genkeypair", "-keystore", str(key), "-storetype", "PKCS12",
                     "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                     "-dname", "CN=hagemashi Upload", "-storepass:env", "HAGEMASHI_KEY_PASSWORD",
                     "-keypass:env", "HAGEMASHI_KEY_PASSWORD", "-noprompt"], env=env)
            cert = command(["keytool", "-exportcert", "-keystore", str(key), "-alias", ALIAS,
                            "-storepass:env", "HAGEMASHI_KEY_PASSWORD"], env=env)
            data = {"version": 1, "purpose": "play-upload", "alias": ALIAS, "keystore": base64.b64encode(key.read_bytes()).decode("ascii"),
                    "password": password, "certificateSha256": hashlib.sha256(cert).hexdigest()}
            with backup.open("x", encoding="utf-8") as output:
                json.dump(data, output)
    if data.get("purpose") != "play-upload" or data.get("alias") != ALIAS:
        sys.exit("アップロード鍵の形式が異なります。診断鍵へ変更せず終了します。")
    os.chmod(backup, 0o600)
    command(["gh", "secret", "set", SECRET, "--repo", REPO], input=json.dumps(data).encode())
    print("アップロード鍵のSecretを登録しました。公開証明書SHA-256:", data["certificateSha256"])
    print("秘密の復旧用ファイル:", backup)
    print("このファイルはGit・共有ストレージ・チャット・Artifactsへ置かず、安全に保管してください。")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        sys.exit("アップロード鍵の準備に失敗しました。秘密値は表示しません。")
