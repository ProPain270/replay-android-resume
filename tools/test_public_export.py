#!/usr/bin/env python3
"""Black-box checks with disposable, synthetic Git histories."""
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("check_public_export.py")
spec = importlib.util.spec_from_file_location("publication_guard", SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class PublicationGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.env = {k: v for k, v in os.environ.items() if not k.startswith(("GIT_AUTHOR_", "GIT_COMMITTER_"))}
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        self.git("init", "-q")
        self.git("config", "user.name", "ExampleUser")
        self.git("config", "user.email", "ExampleUser@users.noreply.github.com")
        self.write("README.md", "Reviewed example\n")
        self.commit()

    def tearDown(self):
        self.temp.cleanup()

    def git(self, *args, env=None):
        return subprocess.run(["git", "-c", "core.hooksPath=" + os.devnull, "-C", str(self.repo), *args],
                              env=env or self.env, check=True, capture_output=True)

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self, env=None):
        self.git("add", ".")
        self.git("commit", "-qm", "Synthetic test", env=env)

    def check(self):
        return guard.check(self.repo, ["HEAD"])

    def test_clean_noreply_history_passes(self):
        self.assertEqual([], self.check())

    def test_committer_fallback_is_blocked_without_printing_identity(self):
        env = dict(self.env, GIT_COMMITTER_NAME="LocalUser", GIT_COMMITTER_EMAIL="private@host.invalid")
        self.write("extra.txt", "reviewed\n")
        self.commit(env=env)
        issues = self.check()
        self.assertTrue(any("committer" in x for x in issues))
        self.assertFalse(any("LocalUser" in x or "private@host.invalid" in x for x in issues))

    def test_author_fallback_is_blocked(self):
        env = dict(self.env, GIT_AUTHOR_NAME="LocalUser", GIT_AUTHOR_EMAIL="private@host.invalid")
        self.write("extra.txt", "reviewed\n")
        self.commit(env=env)
        self.assertTrue(any("author" in x for x in self.check()))

    def test_deleted_credential_remains_blocked_in_history(self):
        self.write("temporary.txt", "ghp_" + "A" * 36)
        self.commit()
        self.git("rm", "temporary.txt")
        self.commit()
        self.assertTrue(any("credential pattern" in x for x in self.check()))

    def test_commit_message_credential_is_blocked(self):
        self.git("commit", "--allow-empty", "-qm", "ghp_" + "A" * 36)
        self.assertTrue(any("credential pattern in commit content" in x for x in self.check()))

    def test_reviewed_binary_digest_cannot_silently_change(self):
        import hashlib
        import json
        original = bytes([0, 1, 2, 3])
        (self.repo / "reviewed.bin").write_bytes(original)
        self.write("tools/public-export-policy.json", json.dumps({"allowed_binary_sha256": {
            "reviewed.bin": hashlib.sha256(original).hexdigest()}}))
        self.commit()
        self.assertEqual([], self.check())
        (self.repo / "reviewed.bin").write_bytes(bytes([0, 1, 2, 4]))
        self.commit()
        self.assertTrue(any("reviewed binary digest changed" in x for x in self.check()))

    def test_private_path_and_address_are_blocked(self):
        self.write("private.txt", "/" + "Users" + "/example/secret\n" + "192" + ".168" + ".50" + ".12\n")
        self.commit()
        issues = self.check()
        self.assertTrue(any("local home path" in x for x in issues))
        self.assertTrue(any("private network address" in x for x in issues))

    def test_generated_and_credential_files_are_blocked(self):
        self.write(".env", "EXAMPLE=value\n")
        self.write("build/generated.txt", "generated\n")
        self.commit()
        self.assertEqual(2, sum("artifact is tracked" in x for x in self.check()))

    def test_symlink_is_blocked(self):
        (self.repo / "external-link").symlink_to("README.md")
        self.commit()
        self.assertTrue(any("unsupported link" in x for x in self.check()))

    def test_deleted_ref_has_no_new_history(self):
        result = subprocess.run(["python3", str(SCRIPT), "--repo", str(self.repo), "--pre-push"],
                                input="refs/heads/test " + "0" * 40 + " refs/heads/test " + "1" * 40 + "\n",
                                capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
