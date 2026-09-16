#!/usr/bin/env python3
"""Verify test artifact identity and bind its verbatim production inputs."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parent
repo = root.parents[1]
inputs = json.loads((root / "build-inputs.json").read_text())
apk = root / "app/build/outputs/apk/debug/app-debug.apk"
sha = lambda p: hashlib.sha256(Path(p).read_bytes()).hexdigest()
for origin, expected in inputs["production_sources"].items():
    assert sha(repo / origin) == expected, f"Production changed during test build: {origin}"
    copied = root / "app/src/production/java/com/nakas/skate3" / Path(origin).name
    assert sha(copied) == expected, f"Test production copy differs: {copied}"
with zipfile.ZipFile(apk) as archive:
    assert archive.testzip() is None, "Bad APK ZIP"
    assert len(archive.namelist()) == len(set(archive.namelist())), "Duplicate APK entries"
    assert not any(name.startswith("lib/") for name in archive.namelist()), "Harness must not load native driver/game code"
    packaged = json.loads(archive.read("assets/build-inputs.json"))
    assert packaged == inputs, "Packaged source/fixture manifest differs"
    for name, record in inputs["fixtures"].items():
        content = archive.read("assets/fixtures/" + name)
        assert len(content) == record["bytes"] and hashlib.sha256(content).hexdigest() == record["sha256"], name
record = {
    "artifact_verified": True, "device_tests_run": False,
    "apk": apk.relative_to(root).as_posix(), "bytes": apk.stat().st_size, "sha256": sha(apk),
    "production_sources": inputs["production_sources"], "fixtures": len(inputs["fixtures"]),
    "scope": "APK integrity, exact production-source copy binding and fixtures only. No device tests asserted by this script."
}
(root / "build-verification.json").write_text(json.dumps(record, indent=2) + "\n")
print(json.dumps(record, indent=2))
