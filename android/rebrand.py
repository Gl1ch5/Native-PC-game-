#!/usr/bin/env python3
"""Gives a prepared Winlator Cmod tree its own Android package id.

Upstream hard-codes its data directory (/data/data/com.winlator.cmod) in Java code,
in prebuilt native libraries and inside the system image archives (imagefs.txz,
proton-*.txz, *.tzst). Sources get a plain text replacement. In binaries the
path is replaced in place with a string of the same byte length: the new id is
padded with extra '/' characters, which Linux path resolution ignores
("/data/data/com.gl1ch5.f12012" or "/data/data/com.example.nfs////").
That is why the new id may be at most as long as "com.winlator.cmod" (17 chars).

Usage: android/rebrand.py <work-dir> <package-id>
Run it after fetch-system-assets.sh, so the system image is patched too.
"""
import io
import lzma
import os
import re
import shutil
import subprocess
import sys
import tarfile

OLD_ID = "com.winlator.cmod"
PREFIXES = ("/data/data/", "/data/user/0/")
TEXT_EXTS = {".java", ".c", ".h", ".cpp", ".kt"}


def binary_pairs(new_id):
    padded = new_id + "/" * (len(OLD_ID) - len(new_id))
    return [((p + OLD_ID).encode(), (p + padded).encode()) for p in PREFIXES]


def replace_bytes(data, pairs):
    count = 0
    for old, new in pairs:
        n = data.count(old)
        if n:
            data = data.replace(old, new)
            count += n
    return data, count


def rewrite_tar(raw_tar, pairs):
    """Returns (new tar bytes, replacements) or (None, 0) when nothing matched."""
    total = 0
    src = tarfile.open(fileobj=io.BytesIO(raw_tar), mode="r:")
    out = io.BytesIO()
    dst = tarfile.open(fileobj=out, mode="w:", format=src.format)
    for member in src:
        data = None
        if member.isfile():
            data = src.extractfile(member).read()
            data, n = replace_bytes(data, pairs)
            total += n
            member.size = len(data)
        if member.issym() or member.islnk():
            new_link, n = replace_bytes(member.linkname.encode(), pairs)
            if n:
                member.linkname = new_link.decode()
                total += n
        dst.addfile(member, io.BytesIO(data) if data is not None else None)
    dst.close()
    return (out.getvalue(), total) if total else (None, 0)


def run_filter(cmd, data=None, path=None):
    """Runs a (de)compressor CLI, feeding bytes or a file, returning stdout bytes."""
    if path is not None:
        with open(path, "rb") as f:
            return subprocess.run(cmd, stdin=f, stdout=subprocess.PIPE, check=True).stdout
    return subprocess.run(cmd, input=data, stdout=subprocess.PIPE, check=True).stdout


def patch_archive(path, pairs):
    """Rewrites a .txz/.tzst archive in place; uses multi-threaded xz/zstd CLIs when available."""
    if path.endswith((".txz", ".tar.xz")):
        if shutil.which("xz"):
            raw = run_filter(["xz", "-dc", "-T0"], path=path)
        else:
            with lzma.open(path) as f:
                raw = f.read()
        new, n = rewrite_tar(raw, pairs)
        if new is not None:
            if shutil.which("xz"):
                packed = run_filter(["xz", "-c", "-6", "-T0"], data=new)
            else:
                packed = lzma.compress(new, preset=6)
            with open(path + ".tmp", "wb") as f:
                f.write(packed)
            os.replace(path + ".tmp", path)
        return n
    if path.endswith((".tzst", ".tar.zst")):
        if shutil.which("zstd"):
            raw = run_filter(["zstd", "-dc", "-q"], path=path)
        else:
            import zstandard
            with open(path, "rb") as f:
                raw = zstandard.ZstdDecompressor().stream_reader(f).read()
        new, n = rewrite_tar(raw, pairs)
        if new is not None:
            if shutil.which("zstd"):
                packed = run_filter(["zstd", "-c", "-q", "-12", "-T0"], data=new)
            else:
                import zstandard
                packed = zstandard.ZstdCompressor(level=12, threads=-1).compress(new)
            with open(path + ".tmp", "wb") as f:
                f.write(packed)
            os.replace(path + ".tmp", path)
        return n
    return None


def sub_file(path, pattern, repl):
    with open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    new, n = re.subn(pattern, repl, text)
    if n:
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(new)
    return n


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    work, new_id = sys.argv[1], sys.argv[2]
    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", new_id):
        sys.exit(f"Invalid package id: {new_id}")
    if len(new_id) > len(OLD_ID):
        sys.exit(f"Package id '{new_id}' is {len(new_id)} chars; the limit is {len(OLD_ID)} (see the docstring)")
    if new_id == OLD_ID:
        print("Package id unchanged")
        return

    app = os.path.join(work, "app")
    main_dir = os.path.join(app, "src", "main")

    # Gradle: the application id only; the Java namespace stays com.winlator.cmod.
    n = sub_file(os.path.join(app, "build.gradle"), r'applicationId\s+"com\.winlator\.cmod"', f'applicationId "{new_id}"')
    if n != 1:
        sys.exit("applicationId not found in app/build.gradle")

    # Content provider authorities must be unique per installed app.
    sub_file(os.path.join(main_dir, "AndroidManifest.xml"), r'android:authorities="com\.winlator\.cmod\.', 'android:authorities="${applicationId}.')

    # Hard-coded data paths in sources.
    text_count = 0
    for root, _, files in os.walk(main_dir):
        for name in files:
            if os.path.splitext(name)[1] in TEXT_EXTS:
                for prefix in PREFIXES:
                    text_count += sub_file(os.path.join(root, name), re.escape(prefix + OLD_ID), prefix + new_id)
    print(f"sources: {text_count} path(s) replaced")

    # Prebuilt native libraries and asset archives.
    pairs = binary_pairs(new_id)
    leftovers = []
    for sub in ("jniLibs", os.path.join("cpp", "jniLibs"), "assets"):
        base = os.path.join(main_dir, sub)
        for root, _, files in os.walk(base):
            for name in sorted(files):
                path = os.path.join(root, name)
                rel = os.path.relpath(path, main_dir)
                n = patch_archive(path, pairs)
                if n is None:
                    with open(path, "rb") as f:
                        data = f.read()
                    data, n = replace_bytes(data, pairs)
                    if n:
                        with open(path, "wb") as f:
                            f.write(data)
                    if OLD_ID.encode() in data:
                        leftovers.append(rel)
                if n:
                    print(f"{rel}: {n} path(s) replaced")
    for rel in leftovers:
        print(f"note: {rel} still mentions {OLD_ID} outside a data path")
    print(f"Package id set to {new_id}")


if __name__ == "__main__":
    main()
