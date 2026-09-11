import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'apps/embedded/systemd/wait-for-input-devices.sh'


class WaitForInputTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.env = dict(os.environ, BITCHAT_INPUT_SYSFS_ROOT=str(self.root / 'sys'),
                        BITCHAT_INPUT_DEV_ROOT=str(self.root / 'dev'))
        (self.root / 'dev').mkdir()

    def device(self, event, name, readable=True):
        directory = self.root / 'sys' / event / 'device'
        directory.mkdir(parents=True)
        (directory / 'name').write_text(name + '\n')
        if readable:
            (self.root / 'dev' / event).write_text('')
        else:
            # A dangling event symlink exists, but cannot be opened (also valid as root).
            (self.root / 'dev' / event).symlink_to('unavailable')

    def run_script(self, timeout='0'):
        result = subprocess.run(['sh', str(SCRIPT), timeout], env=self.env,
                                text=True, capture_output=True, timeout=3)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout

    def test_usb_screen_and_keyboard_return_promptly(self):
        self.device('event1', 'CardKb-I2C')
        self.device('event2', 'QDtech MPI5001')
        self.assertIn('ready after 0 s', self.run_script())

    def test_resistive_screen_remains_supported(self):
        self.device('event1', 'CardKb-I2C')
        self.device('event2', 'XPT2046 Touchscreen')
        self.assertIn('ready after 0 s', self.run_script())

    def test_selected_touch_override_is_exact(self):
        self.device('event1', 'CardKb-I2C')
        self.device('event2', 'QDtech MPI5001')
        self.env['BITCHAT_TOUCH_NAME'] = 'Another touch'
        self.assertIn("touch 'Another touch'", self.run_script())
        self.device('event3', 'Another touch')
        self.assertIn('ready after 0 s', self.run_script())

    def test_unreadable_event_does_not_count_as_ready(self):
        self.device('event1', 'CardKb-I2C')
        self.device('event2', 'QDtech MPI5001', readable=False)
        output = self.run_script()
        self.assertIn('no readable touch', output)
        self.assertNotIn('no readable keyboard', output)
        self.assertIn('starting anyway', output)

    def test_missing_nodes_report_both(self):
        output = self.run_script()
        self.assertIn("keyboard 'CardKb-I2C'", output)
        self.assertIn('touch', output)
        self.assertIn('after 0 s', output)

    def test_invalid_timeout_defaults_to_twenty(self):
        self.device('event1', 'CardKb-I2C')
        self.device('event2', 'QDtech MPI5001')
        output = self.run_script('-1')
        self.assertIn('using 20 s', output)
        self.assertIn('ready after 0 s', output)

    def test_timeout_above_twenty_is_clamped(self):
        self.device('event1', 'CardKb-I2C')
        self.device('event2', 'QDtech MPI5001')
        output = self.run_script('999999999999999999999999')
        self.assertIn('limiting timeout to 20 s', output)
        self.assertIn('ready after 0 s', output)


if __name__ == '__main__':
    unittest.main()
