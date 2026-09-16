#!/usr/bin/env python3
"""Prepare adversarial ZIP fixtures and verbatim production-source copies.

The small positive fixtures contain a genuine AArch64 shared-object header/code,
but are validation fixtures, not usable Vulkan drivers. The two release ZIPs are
also tested. No fixture is ever loaded as native code by this harness.
"""
import hashlib
import json
import os
import re
from pathlib import Path
import platform
import shutil
import struct
import subprocess
import urllib.request
import warnings
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
LOCK = json.loads((ROOT / "fixtures.lock.json").read_text())
# The fork this came from cross-checked the T30 pin against a sources.lock.json
# it kept for reproducible packaging. This repository commits the driver instead
# and enforces its hash at runtime from DriverBridge, so check the pin against
# that constant - the thing that actually decides whether the app will load the
# file - rather than against a second copy of the same number.
BUNDLED_T30 = REPO / "app/src/main/assets/drivers/turnip-t30.zip"
DRIVER_BRIDGE = REPO / "app/src/main/java/com/nakas/skate3/DriverBridge.kt"
ASSETS = ROOT / "app/src/main/assets"
CORPUS = ASSETS / "fixtures"
CORPUS.mkdir(parents=True, exist_ok=True)
GENERATED = ROOT / "generated"
GENERATED.mkdir(exist_ok=True)
CACHE = Path(os.environ.get("DRIVER_TEST_CACHE_DIR", REPO / "build/driver-tests/downloads"))


def checksum(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest()


def verified(path, record):
    return path.is_file() and path.stat().st_size == record["bytes"] and checksum(path) == record["sha256"]


def driver_archive(kind):
    record = LOCK["drivers"][kind]
    candidates = [CACHE / record["filename"]]
    if kind == "t30":
        enforced = re.search(r'ZIP_HASH\s*=\s*"([0-9a-f]{64})"', DRIVER_BRIDGE.read_text())
        if enforced is None:
            raise SystemExit("Could not read ZIP_HASH from DriverBridge.kt")
        if record["sha256"] != enforced.group(1):
            raise SystemExit("T30 test pin differs from the ZIP_HASH DriverBridge enforces")
        # No download needed: the app ships this exact file.
        candidates.append(BUNDLED_T30)
    for path in candidates:
        if verified(path, record):
            return path
    if os.environ.get("DRIVER_TEST_OFFLINE") == "1":
        raise SystemExit(f"Missing verified {record['filename']}; place it in DRIVER_TEST_CACHE_DIR or allow downloads")
    CACHE.mkdir(parents=True, exist_ok=True)
    destination = CACHE / record["filename"]
    temporary = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(record["url"], headers={"User-Agent": "Skate3Android-driver-tests/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=60) as source, temporary.open("wb") as output:
            total = 0
            while block := source.read(65536):
                total += len(block)
                if total > record["bytes"]:
                    raise SystemExit(f"Downloaded {record['filename']} exceeds its pinned size")
                output.write(block)
        if not verified(temporary, record):
            raise SystemExit(f"Downloaded {record['filename']} failed its pinned size/SHA-256 check")
        temporary.replace(destination)
        return destination
    finally:
        temporary.unlink(missing_ok=True)


def pinned_ndk():
    """The NDK this repository builds against, from the one place it is declared."""
    match = re.search(r'ndkVersion\s*=\s*"([^"]+)"', (REPO / "app/build.gradle.kts").read_text())
    if match is None:
        raise SystemExit("Could not read ndkVersion from app/build.gradle.kts")
    return match.group(1)


def compile_elf_fixture():
    ndk_version = pinned_ndk()
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    ndk = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if not ndk and sdk:
        ndk = str(Path(sdk) / "ndk" / ndk_version)
    if not ndk:
        raise SystemExit("Set ANDROID_HOME (SDK with pinned NDK installed) or ANDROID_NDK_HOME")
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64"}.get(platform.system())
    if not host:
        raise SystemExit("The test build script supports macOS and Linux hosts")
    clang = Path(ndk) / "toolchains/llvm/prebuilt" / host / "bin/clang"
    if not clang.is_file():
        raise SystemExit(f"NDK clang was not found; install NDK {ndk_version} or set ANDROID_NDK_HOME")
    destination = GENERATED / "vulkan.fixture.so"
    subprocess.run([str(clang), "--target=aarch64-linux-android28", "-shared", "-fPIC", "-nostdlib",
                    "-Wl,-soname,vulkan.fixture.so", "-Wl,--build-id=none", "-Wl,-z,max-page-size=16384",
                    str(ROOT / "fixture.c"), "-o", str(destination)], check=True)
    return destination.read_bytes()


elf = compile_elf_fixture()
assert elf[:6] == b"\x7fELF\x02\x01" and elf[18:20] == b"\xb7\0"
meta = dict(schemaVersion=1, name="Fixture Alpha", packageVersion="1", author="Regression",
            minApi=28, libraryName="vulkan.test.so")


def package(name, entries=None, metadata=None):
    if metadata is None:
        metadata = meta
    if entries is None:
        entries = [("meta.json", json.dumps(metadata).encode()), ("vulkan.test.so", elf)]
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with zipfile.ZipFile(CORPUS / name, "w", zipfile.ZIP_DEFLATED) as z:
            for path, data in entries:
                z.writestr(path, data)


package("valid-alpha.zip")
package("valid-beta.zip", metadata={**meta, "name": "Fixture Beta", "packageVersion": "2"})
package("valid-extra.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("libextra.so", elf), ("LICENSE", b"Test fixture\n")])
package("empty.zip", entries=[])
(CORPUS / "not-zip.zip").write_bytes(b"This is not a zip file")
(CORPUS / "truncated.zip").write_bytes((CORPUS / "valid-alpha.zip").read_bytes()[:-25])

unsafe = {
    "parent": "../escaped.so", "absolute": "/absolute.so", "backslash": "..\\escaped.so",
    "drive": "C:/escaped.so", "dot": "nested/./same.so", "empty-part": "nested//same.so",
    "nested-parent": "nested/../../escaped.so", "too-deep": "a/b/c/d/e/file.txt",
}
for name, path in unsafe.items():
    package("path-" + name + ".zip", entries=[("meta.json", json.dumps(meta).encode()),
            ("vulkan.test.so", elf), (path, b"must not escape")])
package("duplicate-meta.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("meta.json", json.dumps(meta).encode())])
package("duplicate-library.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("vulkan.test.so", elf)])
package("case-collision.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("VULKAN.TEST.SO", elf)])
package("file-directory-conflict.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("a", b"file"), ("a/b.txt", b"child")])
package("reserved-manifest.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), (".driver-store.json", b"{}")])
with zipfile.ZipFile(CORPUS / "symlink.zip", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("meta.json", json.dumps(meta)); z.writestr("vulkan.test.so", elf)
    link = zipfile.ZipInfo("escape"); link.create_system = 3; link.external_attr = 0o120777 << 16
    z.writestr(link, "../../outside")
changed = bytearray((CORPUS / "valid-alpha.zip").read_bytes())
changed[30] = ord("X")  # Change local meta name, retaining its central name.
(CORPUS / "local-name-mismatch.zip").write_bytes(changed)
changed = bytearray((CORPUS / "valid-alpha.zip").read_bytes())
central = changed.index(b"PK\x01\x02")
changed[central + 16] ^= 1  # Declared CRC differs from the actual extracted bytes.
(CORPUS / "bad-crc.zip").write_bytes(changed)
package("entry-count.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf)] + [(f"f{i}.txt", b"x") for i in range(127)])

bad_meta = {
    "schema": {**meta, "schemaVersion": 2}, "schema-string": {**meta, "schemaVersion": "1"},
    "name-empty": {**meta, "name": "  "}, "name-long": {**meta, "name": "x" * 161},
    "version-empty": {**meta, "packageVersion": ""}, "author-empty": {**meta, "author": ""},
    "future-api": {**meta, "minApi": 999}, "fraction-api": {**meta, "minApi": 28.5},
    "string-api": {**meta, "minApi": "28"}, "zero-api": {**meta, "minApi": 0},
    "missing-library": {**meta, "libraryName": "missing.so"},
    "library-path": {**meta, "libraryName": "../vulkan.test.so"},
    "library-non-so": {**meta, "libraryName": "vulkan.test.bin"},
}
for name, value in bad_meta.items():
    package("meta-" + name + ".zip", metadata=value)
package("meta-json.zip", entries=[("meta.json", b"{"), ("vulkan.test.so", elf)])
package("meta-utf8.zip", entries=[("meta.json", b"{\xff}"), ("vulkan.test.so", elf)])
package("meta-missing.zip", entries=[("vulkan.test.so", elf)])
package("meta-large.zip", metadata={**meta, "description": "x" * (64 * 1024)})

bad_elf = {"magic": (0, 0), "class": (4, 1), "endian": (5, 2),
           "machine": (18, 62), "type": (16, 2)}
for name, (offset, value) in bad_elf.items():
    modified = bytearray(elf)
    modified[offset] = value
    package("elf-" + name + ".zip", entries=[("meta.json", json.dumps(meta).encode()),
            ("vulkan.test.so", modified)])
package("elf-short.zip", entries=[("meta.json", json.dumps(meta).encode()), ("vulkan.test.so", elf[:19])])
package("bad-extra-elf.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("libextra.so", b"not an ELF")])
package("nested-native.zip", entries=[("meta.json", json.dumps(meta).encode()),
        ("vulkan.test.so", elf), ("support/libextra.so", elf)])
for name in ("phdr-bounds", "no-dynamic", "segment-bounds"):
    modified = bytearray(elf)
    phoff = struct.unpack_from("<Q", modified, 32)[0]
    phcount = struct.unpack_from("<H", modified, 56)[0]
    if name == "phdr-bounds":
        struct.pack_into("<Q", modified, 32, len(elf) + 1)
    elif name == "no-dynamic":
        for i in range(phcount):
            off = phoff + 56 * i
            if struct.unpack_from("<I", modified, off)[0] == 2:
                struct.pack_into("<I", modified, off, 4)
    else:
        struct.pack_into("<Q", modified, phoff + 8, len(elf) + 1)
    package("elf-" + name + ".zip", entries=[("meta.json", json.dumps(meta).encode()),
            ("vulkan.test.so", modified)])

# Streaming compression avoids a 256 MiB in-memory or on-disk uncompressed fixture.
# Its declared expanded size exceeds the production cap, so extraction must reject.
with zipfile.ZipFile(CORPUS / "expanded-bomb.zip", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("meta.json", json.dumps(meta))
    z.writestr("vulkan.test.so", elf)
    with z.open("padding.bin", "w", force_zip64=True) as entry:
        block = b"\0" * (1024 * 1024)
        for _ in range(256):
            entry.write(block)
        entry.write(b"x")

(ASSETS / "drivers").mkdir(exist_ok=True)
t30 = driver_archive("t30")
r7 = driver_archive("r7")
shutil.copyfile(t30, ASSETS / "drivers/turnip-t30.zip")
shutil.copyfile(t30, CORPUS / "release-t30.zip")
shutil.copyfile(r7, CORPUS / "release-r7.zip")

sources = {}
for name in ("DriverStore.kt", "DriverBridge.kt"):
    origin = REPO / "app/src/main/java/com/nakas/skate3" / name
    copy = ROOT / "app/src/production/java/com/nakas/skate3" / name
    copy.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(origin, copy)
    sources[origin.relative_to(REPO).as_posix()] = hashlib.sha256(origin.read_bytes()).hexdigest()
manifest = {"production_sources": sources, "fixtures": {
    p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()}
    for p in sorted(CORPUS.glob("*.zip"))},
    "elf_fixture_source_sha256": checksum(ROOT / "fixture.c"),
    "elf_fixture_sha256": hashlib.sha256(elf).hexdigest(),
    "scope": "Verbatim production Kotlin; real Android APIs. No native library is loaded."}
(ROOT / "build-inputs.json").write_text(json.dumps(manifest, indent=2) + "\n")
(ASSETS / "build-inputs.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"Prepared {len(manifest['fixtures'])} fixtures and two verbatim production Kotlin files")
