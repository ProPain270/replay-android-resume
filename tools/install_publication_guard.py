#!/usr/bin/env python3
"""Install this clone's pre-push guard without replacing existing hook settings."""
from pathlib import Path
import subprocess

repo = Path(__file__).resolve().parent.parent
existing = subprocess.run(["git", "-C", str(repo), "config", "--get", "core.hooksPath"], capture_output=True)
if existing.returncode not in (0, 1):
    raise SystemExit("Could not inspect hook configuration; no settings changed.")
if existing.returncode == 0:
    raise SystemExit("Existing hook configuration detected. Integrate the guard with it manually; no settings changed.")
location = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "--git-path", "hooks/pre-push"]).decode().strip()
hook = Path(location)
if not hook.is_absolute():
    hook = repo / hook
hook.parent.mkdir(parents=True, exist_ok=True)
content = '''#!/bin/sh
set -eu
exec python3 "$(git rev-parse --show-toplevel)/tools/check_public_export.py" --pre-push
'''
if hook.exists():
    if hook.read_text() == content:
        print("Publication guard is already installed in this clone.")
        raise SystemExit(0)
    raise SystemExit("An existing pre-push hook was preserved. Integrate the guard manually.")
with hook.open("x") as output:
    output.write(content)
hook.chmod(0o755)
print("Publication guard installed in this clone. Global Git settings were not changed.")
