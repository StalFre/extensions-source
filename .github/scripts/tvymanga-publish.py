"""Copy the built TvyManga APK + icon into the repo checkout and rewrite index.min.json."""

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from zipfile import ZipFile

out = Path(sys.argv[1])
(out / "apk").mkdir(parents=True, exist_ok=True)
(out / "icon").mkdir(parents=True, exist_ok=True)

info_file = next(Path("src/es/tvymanga").glob("**/keiyoushi-source-info.json"))
info = json.loads(info_file.read_text(encoding="utf-8"))
apk = next((info_file.parent / "outputs/apk").glob("*/*.apk"))
pkg = info["packageName"]
apk_name = apk.name.replace("-release.apk", ".apk")
(out / "apk" / apk_name).write_bytes(apk.read_bytes())

aapt = sorted((Path(os.environ["ANDROID_HOME"]) / "build-tools").iterdir())[-1] / "aapt"
badging = subprocess.check_output([aapt, "dump", "--include-meta-data", "badging", apk]).decode()
icon_path = re.search(r"^application-icon-320:'([^']+)'", badging, re.MULTILINE).group(1)
with ZipFile(apk) as z:
    (out / "icon" / f"{pkg}.png").write_bytes(z.read(icon_path))

entry = {
    "name": f"Tachiyomi: {info['name']}",
    "pkg": pkg,
    "apk": apk_name,
    "lang": "es",
    "code": info["versionCode"],
    "version": info["versionName"],
    "nsfw": 1 if info["contentWarning"] > 1 else 0,
    "sources": [
        {"name": s["name"], "lang": s["lang"], "id": str(s["id"]), "baseUrl": s["baseUrl"]}
        for s in info["sources"]
    ],
}

index_file = out / "index.min.json"
index = json.loads(index_file.read_text(encoding="utf-8")) if index_file.exists() else []
index = [e for e in index if e["pkg"] != pkg] + [entry]
index_file.write_text(json.dumps(index, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

# Mihon-style clients refuse a repo without repo.json and its signing key fingerprint.
repo_meta = {
    "meta": {
        "name": "StalFre",
        "shortName": "StalFre",
        "website": f"https://github.com/{os.environ['GITHUB_REPOSITORY']}",
        "signingKeyFingerprint": os.environ["SIGNING_FINGERPRINT"],
    }
}
(out / "repo.json").write_text(json.dumps(repo_meta, indent=2), encoding="utf-8")
