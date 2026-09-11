import importlib.util
import contextlib
import io
from unittest.mock import patch
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]
MODULE = REPO / 'scripts/pi_lora_runtime.py'
if MODULE.exists():
    spec = importlib.util.spec_from_file_location('pi_lora_runtime', MODULE)
    runtime = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runtime)
else:
    runtime = None


class RuntimeFixture:
    def setUp(self):
        self.assertIsNotNone(runtime, 'runtime setup helper is not implemented')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'pi'
        self.root.mkdir()
        self.write('/etc/meshtasticd/config.yaml', 'General:\n  ConfigDirectory: /etc/meshtasticd/enabled\nLora:\n  Module: auto\n  Reset: 71\n')
        self.write('/etc/meshtasticd/enabled/radio.yaml', 'Lora:\n  spidev: spidev0.0\n  Reset:\n    pin: 71\n    gpiochip: 1\n  RF95_MAX_POWER: 17\nWebserver:\n  RootPath: /secret/web\n')
        self.write('/etc/meshtasticd/enabled/old.yaml.disabled', 'Lora:\n  Reset: 99\n')
        self.write('/etc/meshcored/meshcored.ini', '# identity comment\nadmin_password = "dont-print-this"\nlora_freq = 910.525\nlora_bw = 62.5\nspidev = /dev/spidev0.0\nlora_reset_pin = 71\n')

    def write(self, path, value):
        target = self.root / path.lstrip('/')
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(value)
        return target

    def plan(self):
        return runtime.build_plan(self.root, REPO, 'sterling', 'usb')



class RuntimeSetupTests(RuntimeFixture, unittest.TestCase):
    def test_enabled_fragments_and_base_are_patched_without_touching_disabled_files(self):
        plan = self.plan()
        for change in plan:
            if change.path.endswith('.yaml'):
                value = runtime.load_yaml(change.content.decode())
                self.assertNotIn('Reset', value['Lora'])
                self.assertEqual('spidev1.1', value['Lora']['spidev'])
        self.assertNotIn('/etc/meshtasticd/enabled/old.yaml.disabled', [c.path for c in plan])
        radio = next(c for c in plan if c.path.endswith('/radio.yaml'))
        self.assertEqual(17, runtime.load_yaml(radio.content.decode())['Lora']['RF95_MAX_POWER'])
        self.assertIn('Webserver:\n  RootPath: /secret/web\n', radio.content.decode())

    def test_ini_patch_preserves_identity_credentials_and_rf_bytes(self):
        value = runtime.patch_meshcore((self.root / 'etc/meshcored/meshcored.ini').read_text())
        self.assertIn('# identity comment\nadmin_password = "dont-print-this"\nlora_freq = 910.525\nlora_bw = 62.5\n', value)
        self.assertNotIn('lora_reset_pin =', value)
        self.assertIn('spidev = /dev/spidev1.1', value)
        self.assertIn('lora_irq_pin = 70', value)

    def test_ini_patch_removes_reset_with_both_parser_supported_separators(self):
        text = 'lora_reset_pin 73\n  lora_reset_pin = 71\nspidev /dev/spidev0.0\ngpio_chip gpiochip0\nlora_irq_pin 75\nadmin_password = "keep me"\n'
        result = runtime.patch_meshcore(text)
        self.assertNotIn('lora_reset_pin', result)
        self.assertNotIn('spidev0.0', result)
        self.assertIn('spidev = /dev/spidev1.1', result)
        self.assertIn('gpio_chip = gpiochip1', result)
        self.assertIn('lora_irq_pin = 70', result)
        self.assertIn('admin_password = "keep me"', result)

    def test_verification_parses_whitespace_assignments_and_ignores_inline_comments(self):
        self.assertTrue(hasattr(runtime, 'meshcore_hardware_fields'))
        fields = runtime.meshcore_hardware_fields('lora_reset_pin 73 # comment\nspidev=/dev/spidev1.1\nlora_irq_pin = 70 ; comment\nadmin_password = SECRET\n')
        self.assertEqual({'lora_reset_pin': '73', 'spidev': '/dev/spidev1.1', 'lora_irq_pin': '70'}, fields)

    def test_password_required_sudo_authorization_does_not_suppress_nopasswd_rules(self):
        original_plan = runtime.build_plan
        original_files = runtime.meshtastic_files
        plans = []
        states = {'bitchat.service': {'User': 'sterling'}}

        def fixture_plan(_root, source, user, touch_profile, grant_sudo=True):
            result = original_plan(self.root, source, user, touch_profile, grant_sudo)
            plans.extend(result)
            return result

        # Root's sudo -l succeeds for a normal sudo-group user with PASSWD: ALL.
        # This is authorization to run the command, not proof of NOPASSWD.
        allowed = subprocess.CompletedProcess([], 0, 'PASSWD: ALL', '')
        with patch.object(runtime, 'unit_states', return_value=states), \
             patch.object(runtime, 'validate_runtime_config'), \
             patch.object(runtime, 'build_plan', side_effect=fixture_plan), \
             patch.object(runtime, 'meshtastic_files', side_effect=lambda _root: original_files(self.root)), \
             patch.object(runtime.os, 'geteuid', return_value=0), \
             patch.object(runtime.subprocess, 'run', return_value=allowed), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, runtime.main(['--app-user', 'sterling']))
        policy = next((item for item in plans if item.path == '/etc/sudoers.d/bitchat-lora-controller'), None)
        self.assertIsNotNone(policy, 'Exact noninteractive rules must be managed regardless of password-required authorization')
        self.assertIn('NOPASSWD:', policy.content.decode())

    def test_ambiguous_duplicate_and_aliased_yaml_refuse_edits(self):
        for content in ('Lora:\n  Reset: 71\n  Reset: 73\n', 'Lora: &radio\n  Reset: 71\nOther: *radio\n'):
            with self.subTest(content=content), self.assertRaises(ValueError):
                runtime.patch_meshtastic(content)

    def test_bad_config_directory_fails_before_changes(self):
        self.write('/etc/meshtasticd/config.yaml', 'General:\n  ConfigDirectory: relative/path\n')
        with self.assertRaises(ValueError):
            self.plan()

    def test_symlink_config_refuses_to_edit_outside_reviewed_files(self):
        path = self.root / 'etc/meshtasticd/enabled/radio.yaml'
        path.unlink()
        path.symlink_to(self.root / 'etc/meshtasticd/config.yaml')
        with self.assertRaises(ValueError):
            self.plan()

    def test_dry_run_is_read_only_and_never_prints_credentials(self):
        before = {str(p): p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result = subprocess.run(['sh', str(REPO / 'scripts/configure-pi-lora.sh'), '--root', str(self.root), '--app-user', 'sterling'], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('AUDIT', result.stdout)
        self.assertNotIn('dont-print-this', result.stdout + result.stderr)
        self.assertEqual(before, {str(p): p.read_bytes() for p in self.root.rglob('*') if p.is_file()})

    def test_explicit_stage_is_private_and_contains_restore_metadata(self):
        stage = self.root.parent / 'stage'
        runtime.stage_plan(self.plan(), self.root, stage, {})
        self.assertEqual(0o700, stage.stat().st_mode & 0o777)
        self.assertTrue((stage / 'manifest.json').is_file())
        self.assertTrue((stage / 'staged/etc/meshcored/meshcored.ini').is_file())
        manifest = json.loads((stage / 'manifest.json').read_text())
        self.assertIn('/etc/meshcored/meshcored.ini', [entry['path'] for entry in manifest['files']])
        self.assertNotIn('dont-print-this', (stage / 'manifest.json').read_text())

    def test_managed_hooks_and_sudoers_have_required_modes(self):
        reset = self.write('/usr/local/bin/reset-lora.sh', (REPO / 'scripts/meshtastic/reset-lora.sh').read_text())
        reset.chmod(0o600)
        policy = self.write('/etc/sudoers.d/bitchat-lora-controller', runtime.sudoers('sterling'))
        policy.chmod(0o600)
        plan = self.plan()
        self.assertIn(('/usr/local/bin/reset-lora.sh', 0o755), [(change.path, change.mode) for change in plan])
        self.assertIn(('/etc/sudoers.d/bitchat-lora-controller', 0o440), [(change.path, change.mode) for change in plan])

    def test_stale_plan_refuses_to_overwrite_newer_config(self):
        plan = self.plan()
        self.write('/etc/meshcored/meshcored.ini', 'admin_password = "new-value"\n')
        with self.assertRaises(ValueError):
            runtime.validate_unchanged(plan, self.root)

    def test_sudoers_contains_only_exact_controller_commands(self):
        content = runtime.sudoers('sterling')
        self.assertIn('/usr/bin/systemctl stop --no-block meshcore.service', content)
        self.assertIn('/usr/bin/systemctl start --no-block meshcored.service', content)
        self.assertIn('/usr/bin/pkill -x meshtasticd', content)
        self.assertNotIn('*', content)
        self.assertNotIn('/bin/sh', content)
        with self.assertRaises(ValueError):
            runtime.sudoers('bad user ALL=(ALL)')


class RuntimeApplyTests(RuntimeFixture, unittest.TestCase):
    def states(self):
        return {unit: dict(LoadState='loaded', ActiveState='active' if unit == 'bitchat.service' else 'inactive',
                           UnitFileState='enabled', Job='', User='sterling', ExecStart='', WorkingDirectory='/')
                for unit in runtime.UNITS}

    def fake_runner(self, states, lingering=False):
        self.calls = []
        current = {key: dict(value) for key, value in states.items()}

        def execute(args, check=True, timeout=25):
            self.calls.append(args)
            if args[0].endswith('systemctl') and args[1] == 'stop':
                current[args[2]]['ActiveState'] = 'inactive'
            if args[0].endswith('systemctl') and args[1] == 'show':
                value = current[args[2]]
                return subprocess.CompletedProcess(args, 0, '\n'.join(f'{key}={value}' for key, value in value.items()), '')
            if args[0].endswith('pgrep'):
                return subprocess.CompletedProcess(args, 0 if lingering else 1, '123' if lingering else '', '')
            return subprocess.CompletedProcess(args, 0, '', '')
        return execute

    def test_apply_stops_app_first_preserves_keyboard_and_boot_and_records_enable_states(self):
        self.write('/boot/armbianEnv.txt', 'overlays=i2c1 spi1-enable spi1-cs1-touch\n')
        plan = self.plan()
        stage = self.root.parent / 'backup'
        states = self.states()
        runtime.apply_plan(plan, self.root, stage, states, 'usb', self.fake_runner(states))
        mutations = [args for args in self.calls if args[0].endswith('systemctl') and args[1] != 'show']
        self.assertEqual(['/usr/bin/systemctl', 'stop', 'bitchat.service'], mutations[0])
        self.assertEqual(['/usr/bin/systemctl', 'start', 'bitchat.service'], mutations[-1])
        self.assertNotIn(['/usr/bin/systemctl', 'disable', 'bitchat.service'], mutations)
        self.assertNotIn(['/usr/bin/systemctl', 'stop', 'cardkb.service'], mutations)
        self.assertFalse(any('mask' in command for command in mutations))
        for unit in runtime.RADIOS:
            self.assertIn(['/usr/bin/systemctl', 'disable', unit], mutations)
        self.assertEqual('overlays=i2c1 spi1-enable spi1-cs1-touch\n', (self.root / 'boot/armbianEnv.txt').read_text())
        manifest = json.loads((stage / 'manifest.json').read_text())
        self.assertEqual('enabled', manifest['units_before']['meshtasticd.service']['UnitFileState'])
        self.assertNotIn('lora_reset_pin =', (self.root / 'etc/meshcored/meshcored.ini').read_text())

    def test_manual_owner_blocks_apply_without_overwriting_configs(self):
        original = (self.root / 'etc/meshcored/meshcored.ini').read_bytes()
        states = self.states()
        with self.assertRaises(ValueError):
            runtime.apply_plan(self.plan(), self.root, self.root.parent / 'backup', states, 'usb',
                               self.fake_runner(states, lingering=True))
        self.assertEqual(original, (self.root / 'etc/meshcored/meshcored.ini').read_bytes())
        self.assertFalse(any(args[1:2] == ['disable'] for args in self.calls))

    def test_leave_stopped_does_not_restart_app(self):
        states = self.states()
        runtime.apply_plan(self.plan(), self.root, self.root.parent / 'backup', states, 'usb',
                           self.fake_runner(states), leave_stopped=True)
        self.assertFalse(any(args[1:2] == ['start'] for args in self.calls))

    def test_absent_legacy_unit_is_a_supported_state(self):
        states = self.states()
        states['meshcore.service']['LoadState'] = 'not-found'
        execute = self.fake_runner(states)

        def missing_unit(args, **kwargs):
            result = execute(args, **kwargs)
            if args[1:3] == ['show', 'meshcore.service']:
                result.returncode = 1
            return result

        self.assertEqual('not-found', runtime.unit_states(missing_unit)['meshcore.service']['LoadState'])

    def test_alternate_config_selection_is_rejected(self):
        states = self.states()
        states['meshtasticd.service']['ExecStart'] = '{ argv[]=/usr/bin/meshtasticd -c /some/other.yaml ; }'
        with self.assertRaises(ValueError):
            runtime.validate_runtime_config(self.root, states)

    def test_canonical_config_as_working_directory_is_supported(self):
        states = self.states()
        states['meshtasticd.service']['WorkingDirectory'] = '/etc/meshtasticd'
        runtime.validate_runtime_config(self.root, states)

    def test_runtime_does_not_clear_unrelated_unit_ordering_dependencies(self):
        override = next(change for change in self.plan() if change.path.endswith('lora-input.conf'))
        self.assertNotIn('After=\n', override.content.decode())

    def test_verification_log_summaries_exclude_message_bodies_and_secrets(self):
        self.assertTrue(hasattr(runtime, 'log_summary'), 'read-only verification is not implemented')
        logs = '\n'.join(json.dumps({'MESSAGE': message}) for message in (
            'radio initialization failed; exiting', 'RF95 init result -2',
            'Incoming text: SECRET_MESSAGE', 'admin_password=SECRET_PASSWORD'))
        summary = runtime.log_summary(logs)
        self.assertIn('radio initialization failed', summary)
        self.assertIn('-2', summary)
        self.assertNotIn('SECRET', summary)


if __name__ == '__main__':
    unittest.main()
