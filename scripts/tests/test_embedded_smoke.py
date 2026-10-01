import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import textwrap
import unittest
import unittest.mock

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / 'scripts/embedded-smoke.py'
spec = importlib.util.spec_from_file_location('embedded_smoke', SCRIPT)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

IDENTITY = 'bitchat-embedded 1.0.0 (c19952491a94, main, clean, release, built 2026-09-30T08:00:00-07:00)'
INVOCATION = '0123456789abcdef0123456789abcdef'

# What the release binary of c199524 logged before it died (trimmed).
CRASHED = '\n'.join([
    '=== Bitchat Embedded ===',
    IDENTITY,
    'Initializing application...',
    'Application initialized',
    'Setting up display...',
    '[Renderer] Skia DirectContext created (EGL backend)',
    '[Main] Touch input ready (fd=7)',
    'Uncaught Kotlin exception: kotlin.RuntimeException: Unexpected receiver type: kotlin.String',
    '    at 1   bitchat-embedded.kexe  0x0 kfun:com.bitchat.embedded#main(kotlin.Array<kotlin.String>){} + 38163',
]) + '\n'

HEALTHY = '\n'.join([
    '=== Bitchat Embedded ===',
    IDENTITY,
    'Application initialized',
    '[Renderer] Skia DirectContext created (EGL backend)',
    '[Main] Touch input ready (fd=7)',
    '[Keyboard] Found keyboard device: /dev/input/event1 (CardKb-I2C)',
    '[Main] Keyboard input ready (fd=8)',
    '[Main] Compose scene created (800x480)',
    '[Main] Rendering initial frame...',
    '[Main] Entering event-driven loop (power-efficient mode)',
]) + '\n'


class EvaluateTests(unittest.TestCase):
    def evaluate(self, journal, state='active', now=INVOCATION, identity=IDENTITY):
        return smoke.evaluate(identity, INVOCATION, state, now, journal)

    def test_the_c199524_release_crash_fails(self):
        verdict, reason = self.evaluate(CRASHED)
        self.assertEqual('fail', verdict)
        self.assertIn('Uncaught Kotlin exception', reason)

    def test_crash_wins_even_while_systemd_still_reports_active(self):
        self.assertEqual('fail', self.evaluate(HEALTHY + CRASHED)[0])

    def test_full_startup_is_up(self):
        self.assertEqual(('up', None), self.evaluate(HEALTHY))

    def test_partial_startup_is_still_starting(self):
        self.assertEqual(('starting', None), self.evaluate(HEALTHY.split('[Main] Compose')[0], state='activating'))

    def test_identity_must_match_exactly(self):
        other = IDENTITY.replace('release', 'debug')
        self.assertEqual(('starting', None), self.evaluate(HEALTHY.replace(IDENTITY, other)))

    def test_replaced_invocation_fails(self):
        verdict, reason = self.evaluate(HEALTHY, now='fedcba9876543210fedcba9876543210')
        self.assertEqual('fail', verdict)
        self.assertIn('ended or was replaced', reason)

    def test_dead_unit_fails(self):
        self.assertEqual('fail', self.evaluate(HEALTHY, state='failed', now='')[0])
        self.assertEqual('fail', self.evaluate(HEALTHY, state='deactivating')[0])

    def test_missing_markers_are_named(self):
        missing = smoke.missing_markers(IDENTITY, CRASHED)
        self.assertEqual(["'[Main] Entering event-driven loop'"], missing)


class ArgumentTests(unittest.TestCase):
    """--timeout/--hold feed elapsed-time comparisons, so nan/inf would never terminate."""

    def _reject(self, *argv):
        argv = ['embedded-smoke.py', '--host', 'nobody@127.0.0.1', *argv]
        with unittest.mock.patch.object(sys, 'argv', argv), \
                self.assertRaises(SystemExit) as caught, \
                open(os.devnull, 'w') as devnull, \
                unittest.mock.patch.object(sys, 'stderr', devnull):
            smoke.main()
        self.assertEqual(2, caught.exception.code)

    def test_rejects_non_finite_timeout(self):
        for value in ('inf', 'nan', '-inf'):
            with self.subTest(value=value):
                self._reject('--timeout', value)

    def test_rejects_non_finite_or_negative_hold(self):
        for value in ('inf', 'nan', '-1'):
            with self.subTest(value=value):
                self._reject('--hold', value)


class ParseTests(unittest.TestCase):
    def test_build_info(self):
        values = smoke.parse_build_info(f'name=bitchat-embedded\nbuild=release\nidentity={IDENTITY}\n')
        self.assertEqual('release', values['build'])
        self.assertEqual(IDENTITY, values['identity'])

    def test_poll_output(self):
        state, invocation, journal = smoke.parse_poll(f'state=active\ninvocation={INVOCATION}\n__journal__\n{HEALTHY}')
        self.assertEqual(('active', INVOCATION, HEALTHY), (state, invocation, journal))

    def test_poll_output_without_separator_is_rejected(self):
        with self.assertRaises(ValueError):
            smoke.parse_poll('state=active\n')


FAKE_SSH = textwrap.dedent('''\
    #!/usr/bin/env python3
    # Fake ssh for embedded-smoke.py: answers its remote commands from files in $FAKE_DEVICE.
    import os, sys
    device = os.environ['FAKE_DEVICE']
    command = sys.argv[-1]
    def read(name):
        with open(os.path.join(device, name)) as handle:
            return handle.read()
    with open(os.path.join(device, 'commands'), 'a') as log:
        log.write(command + '\\n')
    if command.startswith('cat '):
        sys.stdout.write(read('BUILD_INFO'))
    elif command.startswith('sudo -n systemctl restart'):
        pass
    elif command.startswith('systemctl show -p InvocationID'):
        print(read('invocation').strip())
    elif command.startswith('echo state='):
        sys.stdout.write('state=' + read('state') + 'invocation=' + read('invocation') + '__journal__\\n' + read('journal'))
    else:
        sys.exit(1)
''')


class EndToEndTests(unittest.TestCase):
    """The whole script against a fake ssh, so the restart, the poll loop and the exit code are covered."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.device = root / 'device'
        self.device.mkdir()
        bin_dir = root / 'bin'
        bin_dir.mkdir()
        (bin_dir / 'ssh').write_text(FAKE_SSH)
        (bin_dir / 'ssh').chmod(0o755)
        self.env = dict(os.environ, PATH=f'{bin_dir}{os.pathsep}{os.environ["PATH"]}',
                        FAKE_DEVICE=str(self.device), PI_HOST='pi@device', PYTHONDONTWRITEBYTECODE='1')
        self.write('BUILD_INFO', f'name=bitchat-embedded\nbuild=release\nrelease=c19952491a94-release-19d52850\nidentity={IDENTITY}\n')
        self.write('invocation', INVOCATION + '\n')
        self.write('state', 'active\n')

    def write(self, name, text):
        (self.device / name).write_text(text)

    def run_smoke(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], env=self.env, text=True,
                              capture_output=True, timeout=30)

    def test_crashed_release_fails_and_shows_the_journal(self):
        self.write('journal', CRASHED)
        result = self.run_smoke('--build', 'release', '--timeout', '5', '--hold', '0')
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn('Unexpected receiver type: kotlin.String', result.stdout)
        self.assertIn('RESULT: FAIL', result.stdout)
        self.assertIn('sudo -n systemctl restart bitchat.service', (self.device / 'commands').read_text())

    def test_healthy_release_passes(self):
        self.write('journal', HEALTHY)
        result = self.run_smoke('--build', 'release', '--timeout', '5', '--hold', '0')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('RESULT: PASS', result.stdout)

    def test_wrong_build_fails(self):
        self.write('journal', HEALTHY)
        result = self.run_smoke('--build', 'debug', '--timeout', '5', '--hold', '0')
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("FAIL: BUILD_INFO says build=debug", result.stdout)

    def test_no_restart_does_not_restart(self):
        self.write('journal', HEALTHY)
        result = self.run_smoke('--no-restart', '--timeout', '5', '--hold', '0')
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn('systemctl restart', (self.device / 'commands').read_text())

    def test_startup_that_never_finishes_times_out(self):
        self.write('journal', HEALTHY.split('[Main] Compose')[0])
        result = self.run_smoke('--timeout', '0', '--hold', '0')
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("not up within 0 s, missing: '[Main] Entering event-driven loop'", result.stdout)


if __name__ == '__main__':
    unittest.main()
