#!/usr/bin/env python3
"""Fails the build if something that must stay private is tracked by git.

The repository is public. A signing key, a password or an access token committed here is
leaked for good (git keeps history). No key file belongs here: the release key lives only in
the repository's Secrets on GitHub.
"""
import re
import subprocess
import sys

ALLOWED_FILES: set[str] = set()
KEY_EXTENSIONS = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".der", ".asc", ".gpg")
SECRET_NAMES = re.compile(r"(^|/)(\.env(\..*)?|keystore\.properties|secrets?(\.[a-z]+)?|.*signing-secrets.*)$", re.I)
PATTERNS = [
    ("private key", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\b(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{30,}\b|\bgithub_pat_[A-Za-z0-9_]{30,}\b")),
    ("AWS key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_\-]{35}\b")),
    ("Telegram bot token", re.compile(r"\b\d{8,10}:[A-Za-z0-9_\-]{35}\b")),
]

files = subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.splitlines()
problems = []
for f in files:
    if f in ALLOWED_FILES:
        continue
    low = f.lower()
    if low.endswith(KEY_EXTENSIONS) or SECRET_NAMES.search(f):
        problems.append(f"{f}: a key or secrets file must not be in the repository")
        continue
    try:
        with open(f, "rb") as fh:
            data = fh.read(2_000_000)
    except OSError:
        continue
    if b"\0" in data[:4096]:
        continue  # binary file
    text = data.decode("utf-8", "replace")
    for name, pattern in PATTERNS:
        if pattern.search(text):
            problems.append(f"{f}: looks like a {name}")

if problems:
    print("Private data found in tracked files:")
    for p in problems:
        print("  " + p)
    print("Remove it, change the leaked key / password / token, and only then commit again.")
    sys.exit(1)
print(f"{len(files)} tracked files checked: no keys, passwords or tokens")
