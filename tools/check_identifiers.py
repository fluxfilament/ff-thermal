#!/usr/bin/env python3
# Copyright (C) 2026 Damirusnik
# SPDX-License-Identifier: GPL-3.0-or-later
"""
Refuses to let a camera's identity into this public repository.

Two rules:

1. Known identifiers: serial numbers of the development camera, of other units
   seen, and of the test phone. The list lives outside the repository, because
   publishing it would publish the serials. It is read from the FFT_IDENTIFIERS
   environment variable (newline- or comma-separated; CI gets it from a
   repository secret) and from ~/.config/ff-thermal/identifiers (one per line,
   # starts a comment). Text and binary files are both searched: a screenshot,
   a log dump or a JPEG carries a serial as plain ASCII.

2. FLIR JPEGs: any JPEG with FLIR's own metadata block. Every one of them
   carries the serial number of the camera that took it, whoever's camera
   that was, so none belongs in the repository whatever the list says.

Hits are reported with the identifier masked, since CI logs are public.

Usage:
  check_identifiers.py --staged    what is about to be committed (pre-commit hook)
  check_identifiers.py --tree      every tracked file
  check_identifiers.py --history   every file in every commit (CI)
"""
import os
import re
import subprocess
import sys

LIST_FILE = os.path.expanduser("~/.config/ff-thermal/identifiers")
FLIR_APP1 = re.compile(rb"\xff\xe1..FLIR\x00", re.S)


def identifiers():
    found = set()
    for chunk in re.split(r"[\n,]", os.environ.get("FFT_IDENTIFIERS", "")):
        if chunk.strip():
            found.add(chunk.strip())
    if os.path.exists(LIST_FILE):
        with open(LIST_FILE, encoding="utf-8") as f:
            for line in f:
                line = line.split("#", 1)[0].strip()
                if line:
                    found.add(line)
    # Anything shorter would match by accident too often to be worth a refusal.
    return sorted(i for i in found if len(i) >= 5)


def mask(identifier):
    return identifier[:2] + "*" * (len(identifier) - 2)


def git(*args, data=None):
    return subprocess.run(["git", *args], input=data, capture_output=True, check=True).stdout


def read_blobs(specs):
    """Contents of git objects, one per spec, through a single cat-file process."""
    if not specs:
        return []
    out = git("cat-file", "--batch", data=b"".join(s.encode() + b"\n" for s in specs))
    blobs, at = [], 0
    for _ in specs:
        header_end = out.index(b"\n", at)
        header = out[at:header_end].split()
        if header[-1] == b"missing":
            blobs.append(None)
            at = header_end + 1
            continue
        size = int(header[2])
        blobs.append(out[header_end + 1:header_end + 1 + size])
        at = header_end + 1 + size + 1
    return blobs


def staged():
    names = git("diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z").split(b"\0")
    paths = [n.decode() for n in names if n]
    return list(zip(paths, read_blobs([":" + p for p in paths])))


def tree():
    paths = [n.decode() for n in git("ls-files", "-z").split(b"\0") if n]
    return list(zip(paths, read_blobs([":" + p for p in paths])))


def history():
    seen, items = set(), []
    for line in git("rev-list", "--objects", "--all").decode().splitlines():
        sha, _, path = line.partition(" ")
        if path and sha not in seen:
            seen.add(sha)
            items.append((sha, path))
    kinds = git("cat-file", "--batch-check=%(objecttype)",
                data="".join(s + "\n" for s, _ in items).encode()).decode().split()
    blobs = [(s, p) for (s, p), k in zip(items, kinds) if k == "blob"]
    return [(p, b) for (_, p), b in zip(blobs, read_blobs([s for s, _ in blobs]))]


def check(files, ids):
    patterns = [(i, re.compile(rb"(?<![A-Za-z0-9])" + re.escape(i.encode()) + rb"(?![A-Za-z0-9])"))
                for i in ids]
    problems = []
    for path, data in files:
        if data is None:
            continue
        for identifier, pattern in patterns:
            if pattern.search(data):
                problems.append(f"{path}: contains identifier {mask(identifier)}")
        if data.startswith(b"\xff\xd8") and FLIR_APP1.search(data):
            problems.append(f"{path}: FLIR JPEG; its metadata carries the camera's serial number")
    return problems


def main():
    modes = {"--staged": staged, "--tree": tree, "--history": history}
    if len(sys.argv) != 2 or sys.argv[1] not in modes:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    ids = identifiers()
    if not ids:
        print("check_identifiers: no identifier list found; only the FLIR JPEG rule ran. "
              f"Set FFT_IDENTIFIERS or write {LIST_FILE}.", file=sys.stderr)
    files = modes[sys.argv[1]]()
    problems = check(files, ids)
    for p in problems:
        print("check_identifiers: " + p, file=sys.stderr)
    if problems:
        print("check_identifiers: refused. Remove the identifier, or the file, and try again.",
              file=sys.stderr)
        return 1
    print(f"check_identifiers: {len(files)} files, {len(ids)} identifiers, clean.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
