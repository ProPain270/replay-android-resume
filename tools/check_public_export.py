#!/usr/bin/env python3
"""Check Git publication history without printing suspicious values.

This guard catches common accidental disclosures. It complements human review;
its patterns cannot establish the absence of every kind of private information.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

NOREPLY = re.compile(r"(?:[0-9]+\+)?(?P<login>[A-Za-z0-9_-]+(?:\[bot\])?)@users[.]noreply[.]github[.]com", re.I)
PATTERNS = {
    "credential pattern": re.compile(rb"(?:gh[pousr]_[A-Za-z0-9]{25,}|github_pat_[A-Za-z0-9_]{25,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)"),
    "local home path": re.compile(rb"/(?:Users|home)/[^/\s]+/"),
    "private network address": re.compile(rb"\b(?:10[.](?:[0-9]{1,3}[.]){2}[0-9]{1,3}|192[.]168[.][0-9]{1,3}[.][0-9]{1,3}|172[.](?:1[6-9]|2[0-9]|3[01])[.][0-9]{1,3}[.][0-9]{1,3})\b"),
    "assigned secret": re.compile(rb'''(?:password|api_key|client_secret|access_token)\s*[:=]\s*["'][^"'\r\n]{8,}["']''', re.I),
}
BLOCKED_DIRS = {".aws", ".ssh", ".gradle", ".kotlin", ".cxx", "build", "node_modules", "outputs", "work"}
BLOCKED_SUFFIXES = {".jks", ".keystore", ".pem", ".key", ".apk", ".log"}


def git(repo: Path, *args: str) -> bytes:
    return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.PIPE)


def identity_is_public(name: str, email: str) -> bool:
    match = NOREPLY.fullmatch(email)
    return match is not None and name.casefold() == match.group("login").casefold()


def check(repo: Path, tips: list[str]) -> list[str]:
    issues: set[str] = set()
    # Commit fields are NUL separated; both identities matter for every parent.
    metadata = git(repo, "log", "-z", "--format=%H%x00%an%x00%ae%x00%cn%x00%ce", *tips).split(b"\0")
    if metadata and metadata[-1] == b"":
        metadata.pop()
    if len(metadata) % 5:
        raise ValueError("Unexpected commit metadata format")
    commits: list[str] = []
    for offset in range(0, len(metadata), 5):
        sha, author, author_email, committer, committer_email = [x.decode("utf-8") for x in metadata[offset:offset + 5]]
        commits.append(sha)
        for category, pattern in PATTERNS.items():
            if pattern.search(git(repo, "cat-file", "commit", sha)):
                issues.add(f"Commit {sha[:12]}: {category} in commit content")
        for field, name, email in [("author", author, author_email), ("committer", committer, committer_email)]:
            if not identity_is_public(name, email):
                issues.add(f"Commit {sha[:12]}: {field} is not a matching GitHub noreply identity")

    policy_path = repo / "tools" / "public-export-policy.json"
    allowed_binary = json.loads(policy_path.read_text()).get("allowed_binary_sha256", {}) if policy_path.exists() else {}
    seen_files: set[tuple[str, bytes, bytes]] = set()
    seen_content: set[str] = set()
    # Inspect each historical tree so adding then deleting a secret cannot pass.
    for commit in commits:
        for record in git(repo, "ls-tree", "-r", "-z", commit).split(b"\0"):
            if not record:
                continue
            header, raw_name = record.split(b"\t", 1)
            mode, kind, raw_oid = header.split()
            oid = raw_oid.decode("ascii")
            key = (oid, raw_name, mode)
            if key in seen_files:
                continue
            seen_files.add(key)
            name = raw_name.decode("utf-8")
            path = PurePosixPath(name)
            label = f"Object {oid[:12]}"  # No suspicious filenames or contents in CI logs.
            if kind != b"blob" or mode not in {b"100644", b"100755"}:
                issues.add(f"{label}: unsupported link, submodule, or object mode")
                continue
            if (set(path.parts) & BLOCKED_DIRS or path.suffix.lower() in BLOCKED_SUFFIXES
                    or path.name == "local.properties" or path.name == ".env"
                    or path.name.startswith(".env.") and path.name != ".env.example"):
                issues.add(f"{label}: local/generated/credential artifact is tracked")
            for category, pattern in PATTERNS.items():
                if pattern.search(raw_name):
                    issues.add(f"{label}: {category} in a tracked path")
            content = git(repo, "cat-file", "blob", oid)
            if name in allowed_binary:
                if hashlib.sha256(content).hexdigest() != allowed_binary[name]:
                    issues.add(f"{label}: reviewed binary digest changed")
                continue
            if oid in seen_content:
                continue
            seen_content.add(oid)
            if b"\0" in content:
                issues.add(f"{label}: unreviewed binary content")
            for category, pattern in PATTERNS.items():
                if pattern.search(content):
                    issues.add(f"{label}: {category} in historical content")
    return sorted(issues)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--pre-push", action="store_true", help="Read Git pre-push ref updates from standard input")
    args = parser.parse_args()
    tips = ["HEAD"]
    if args.pre_push:
        tips = []
        for line in sys.stdin:
            fields = line.split()
            if len(fields) != 4 or not re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", fields[1]):
                parser.error("Unexpected pre-push ref format")
            if fields[1].strip("0"):
                tips.append(fields[1])
        if not tips:
            print("No new history to publish.")
            return 0
    try:
        issues = check(args.repo, tips)
    except (subprocess.CalledProcessError, ValueError, OSError, UnicodeError):
        print("Publication check could not inspect this history; refusing to proceed.", file=sys.stderr)
        return 2
    if issues:
        print("Publication blocked:", file=sys.stderr)
        for issue in issues:
            print("- " + issue, file=sys.stderr)
        return 1
    print("Publication history passed identity, artifact, and disclosure-pattern checks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
