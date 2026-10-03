"""Builds must use the reviewed Arti commit, without altering its checkout."""
import pathlib
import subprocess
import tempfile
import unittest

COMMON = pathlib.Path(__file__).resolve().parents[1] / "data/remote/tor/native/build-common.sh"


class ArtiSourcesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.source = pathlib.Path(self.temp.name) / "source"
        self.source.mkdir()
        self.git("init", "-q")
        self.git("config", "user.name", "Test")
        self.git("config", "user.email", "test@example.invalid")
        self.commit("first")

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.source), *args], text=True).strip()

    def commit(self, value):
        (self.source / "tracked.txt").write_text(value)
        self.git("add", "tracked.txt")
        self.git("commit", "-qm", value)
        self.pin = self.git("rev-parse", "HEAD")

    def run_helper(self, function, *args):
        return subprocess.run(["bash", "-c", 'source "$1" && shift && "$@"',
                               "test", str(COMMON), function, *map(str, args)],
                              capture_output=True, text=True)

    def test_matching_pin_preserves_untracked_files(self):
        extra = self.source / "local-output"
        extra.write_text("keep")
        result = self.run_helper("ensure_arti_source", self.source, self.pin)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("keep", extra.read_text())
        self.assertEqual(self.pin, self.git("rev-parse", "HEAD"))

    def test_mismatch_rejected_without_checkout(self):
        old = self.pin
        self.commit("second")
        result = self.run_helper("ensure_arti_source", self.source, old)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match", result.stderr)
        self.assertEqual(self.pin, self.git("rev-parse", "HEAD"))

    def test_modified_tracked_source_rejected(self):
        (self.source / "tracked.txt").write_text("unreviewed")
        result = self.run_helper("ensure_arti_source", self.source, self.pin)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("tracked changes", result.stderr)

    def test_cached_source_refreshes_when_commit_changes(self):
        dest = pathlib.Path(self.temp.name) / "build-source"
        result = self.run_helper("copy_arti_source", self.source, dest)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("first", (dest / "tracked.txt").read_text())
        (self.source / "untracked").write_text("private")
        self.commit("second")
        result = self.run_helper("copy_arti_source", self.source, dest)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("second", (dest / "tracked.txt").read_text())
        self.assertFalse((dest / "untracked").exists())
        self.assertEqual(self.pin, (dest / ".bitchat-source-commit").read_text().strip())


if __name__ == "__main__":
    unittest.main()
