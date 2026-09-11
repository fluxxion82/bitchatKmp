#!/usr/bin/env python3
"""Explicit Orange Pi PCB runtime maintenance. Never touches SPI/PMIC/keyboard setup.

PyYAML is required (Debian/Armbian: python3-yaml). Shell entry points call this
helper; an ordinary app deployment does not invoke its apply operation.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from typing import NamedTuple

try:
    import yaml
except ImportError:
    raise SystemExit('Requires Python PyYAML (Armbian package python3-yaml); no changes made.')

RADIOS = ('meshtasticd.service', 'meshcored.service', 'meshcore.service')
UNITS = ('bitchat.service',) + RADIOS + ('xpt2046-touch.service', 'cardkb.service')
HW = {'Module': 'RF95', 'spidev': 'spidev1.1', 'spiSpeed': 500000,
      'gpiochip': 1, 'IRQ': {'pin': 70, 'gpiochip': 1, 'line': 70}}
MAIN = '/etc/meshtasticd/config.yaml'
INI = '/etc/meshcored/meshcored.ini'


class UniqueLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    pairs = loader.construct_pairs(node, deep=deep)
    keys = [key for key, _ in pairs]
    if len(set(keys)) != len(keys):
        raise ValueError('Duplicate YAML keys require manual review')
    return dict(pairs)


UniqueLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def load_yaml(text):
    try:
        # Anchors/merges could make a hardware edit change another section too.
        if any(isinstance(token, (yaml.tokens.AnchorToken, yaml.tokens.AliasToken))
               for token in yaml.scan(text)):
            raise ValueError('YAML anchors/aliases require manual review')
        value = yaml.load(text, Loader=UniqueLoader) or {}
        if not isinstance(value, dict):
            raise ValueError('YAML root must be a mapping')
        return value
    except yaml.YAMLError:
        # Parser exceptions include source lines, potentially containing credentials.
        raise ValueError('Invalid or unsupported YAML; inspect locally without logging secrets') from None


def patch_meshtastic(text):
    before = load_yaml(text)
    lora = before.get('Lora') or {}
    if not isinstance(lora, dict):
        raise ValueError('Lora must be a mapping')
    after = copy.deepcopy(before)
    after['Lora'] = {**lora, **HW}
    after['Lora'].pop('Reset', None)
    node = yaml.compose(text)
    rendered = yaml.safe_dump({'Lora': after['Lora']}, sort_keys=False)
    if node:
        for key, value in node.value:
            if key.value != 'Lora':
                continue
            if key.start_mark.column != 0:
                raise ValueError('Use block-style top-level YAML before applying this profile')
            start = key.start_mark.index
            end = value.end_mark.index
            if value.end_mark.column:
                newline = text.find('\n', end)
                end = len(text) if newline < 0 else newline + 1
            result = text[:start] + rendered + text[end:]
            if load_yaml(result) != after:
                raise ValueError('Cannot safely isolate Lora block; no changes made')
            return result
    result = text + ('\n' if text and not text.endswith('\n') else '') + rendered
    if load_yaml(result) != after:
        raise ValueError('Cannot safely append Lora profile')
    return result


def meshcore_assignment(line):
    # LinuxConfig::load accepts both "key = value" and "key value" and removes
    # inline #/; comments. Match that grammar so no active reset assignment survives.
    match = re.match(r'^\s*([A-Za-z_][A-Za-z_0-9]*)(?:\s+|=)[\s=]*(.*)', line)
    if not match:
        return None, None
    value = re.split(r'[#;\r\n]', match.group(2), maxsplit=1)[0].strip()
    return match.group(1), value


def meshcore_hardware_fields(text):
    fields = {}
    for line in text.splitlines():
        key, value = meshcore_assignment(line)
        if key in ('spidev', 'gpio_chip', 'lora_irq_pin', 'lora_reset_pin'):
            fields[key] = value
    return fields


def patch_meshcore(text):
    desired = {'spidev': '/dev/spidev1.1', 'gpio_chip': 'gpiochip1', 'lora_irq_pin': '70'}
    seen = set()
    output = []
    # This fork accepts a flat INI. Preserve every non-hardware line byte-for-byte.
    for line in text.splitlines(keepends=True):
        key, _ = meshcore_assignment(line)
        if key == 'lora_reset_pin':
            continue
        if key in desired:
            if key not in seen:
                output.append(f'{key} = {desired[key]}\n')
                seen.add(key)
        else:
            output.append(line)
    if output and not output[-1].endswith('\n'):
        output[-1] += '\n'
    output.extend(f'{key} = {value}\n' for key, value in desired.items() if key not in seen)
    return ''.join(output)


def rooted(root, path):
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('Configuration paths must be absolute without parent traversal')
    target = root / str(path).lstrip('/')
    for part in [target, *target.parents]:
        if part == root:
            break
        if part.is_symlink():
            raise ValueError(f'Symlink requires manual review: {path}')
    return target


def meshtastic_files(root):
    base = rooted(root, MAIN)
    value = load_yaml(base.read_text())
    directory = value.get('General', {}).get('ConfigDirectory', '')
    files = [MAIN]
    if directory:
        folder = rooted(root, directory)
        if not folder.is_dir():
            raise ValueError('Configured Meshtastic ConfigDirectory is not a directory')
        # Firmware loads every immediate *.yaml entry, not *.yml or recursive files.
        for child in sorted(folder.iterdir()):
            if child.name.endswith('.yaml'):
                logical = str(Path(directory) / child.name)
                path = rooted(root, logical)
                if not path.is_file():
                    raise ValueError(f'Enabled YAML entry is not a regular file: {logical}')
                files.append(logical)
    return files


class Change(NamedTuple):
    path: str
    original: bytes | None
    content: bytes
    mode: int


def sudo_commands():
    return ([f'/usr/bin/systemctl stop --no-block {unit}' for unit in RADIOS]
            + [f'/usr/bin/systemctl {action} {unit}' for action in ('reset-failed', 'start --no-block')
               for unit in RADIOS[:2]]
            + [f'/usr/bin/pkill -x {name}' for name in ('meshtasticd', 'meshcored')])


def sudoers(user):
    if not re.fullmatch(r'[a-z_][a-z0-9_-]*\$?', user):
        raise ValueError('App user must be a plain Linux account name')
    return ('# Exact commands used by the app LoRa controller; no shell or wildcard grants.\n'
            + '\n'.join(f'{user} ALL=(root) NOPASSWD: {command}' for command in sudo_commands()) + '\n')


def build_plan(root, source, app_user, touch_profile, grant_sudo=True):
    changes = []

    def add(path, content, mode=None):
        dest = rooted(root, path)
        original = dest.read_bytes() if dest.exists() else None
        encoded = content.encode() if isinstance(content, str) else content
        current_mode = (dest.stat().st_mode & 0o777) if dest.exists() else None
        install_mode = mode if mode is not None else (current_mode if current_mode is not None else 0o644)
        if original != encoded or current_mode != install_mode:
            changes.append(Change(path, original, encoded, install_mode))

    for path in meshtastic_files(root):
        text = rooted(root, path).read_text()
        if path == MAIN or 'Lora' in load_yaml(text):
            add(path, patch_meshtastic(text))
    add(INI, patch_meshcore(rooted(root, INI).read_text()))
    for daemon in RADIOS[:2]:
        path = f'apps/embedded/systemd/{daemon}.d/bitchat-lora.conf'
        add(f'/etc/systemd/system/{daemon}.d/bitchat-lora.conf', (source / path).read_bytes())
    add('/usr/local/bin/reset-lora.sh', (source / 'scripts/meshtastic/reset-lora.sh').read_bytes(), 0o755)
    # Do not rewrite the versioned release or its manifest. deploy-pi.sh ships the
    # updated input script/base unit. This drop-in also fixes the current release now.
    add('/usr/local/libexec/bitchat/wait-for-input-devices.sh',
        (source / 'apps/embedded/systemd/wait-for-input-devices.sh').read_bytes(), 0o755)
    override = ('[Unit]\n# Preserve target ordering that prevents the CardKB boot cycle.\n'
                'After=multi-user.target cardkb.service\n\n[Service]\nExecStartPre=\n'
                'ExecStartPre=/usr/local/libexec/bitchat/wait-for-input-devices.sh\n')
    if touch_profile == 'usb':
        override += 'Environment="BITCHAT_TOUCH_NAME=QDtech MPI5001"\n'
    elif touch_profile == 'resistive':
        override += 'Environment="BITCHAT_TOUCH_NAME=XPT2046 Touchscreen"\n'
    else:
        raise ValueError('Unsupported touch profile')
    add('/etc/systemd/system/bitchat.service.d/lora-input.conf', override)
    if grant_sudo:
        add('/etc/sudoers.d/bitchat-lora-controller', sudoers(app_user), 0o440)
    return changes


def digest(data):
    return hashlib.sha256(data).hexdigest() if data is not None else None


def validate_unchanged(plan, root):
    for change in plan:
        path = rooted(root, change.path)
        current = path.read_bytes() if path.exists() else None
        if current != change.original:
            raise ValueError(f'File changed since audit; rerun setup: {change.path}')


def stage_plan(plan, root, stage, states):
    stage.mkdir(mode=0o700, parents=True, exist_ok=False)
    os.chmod(stage, 0o700)
    files = []
    for change in plan:
        relative = change.path.lstrip('/')
        target = stage / 'staged' / relative
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        target.write_bytes(change.content)
        os.chmod(target, 0o600)
        entry = {'path': change.path, 'existed': change.original is not None,
                 'original_sha256': digest(change.original), 'staged_sha256': digest(change.content),
                 'install_mode': oct(change.mode)}
        if change.original is not None:
            original = rooted(root, change.path).stat()
            entry.update(uid=original.st_uid, gid=original.st_gid, original_mode=oct(original.st_mode & 0o777))
            backup = stage / 'original' / relative
            backup.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            backup.write_bytes(change.original)
            os.chmod(backup, 0o600)
        files.append(entry)
    manifest = {'created_utc': datetime.now(timezone.utc).isoformat(), 'files': files,
                'units_before': states,
                'restore': ['Stop bitchat and radio units before restoring.',
                            'For each existing file, copy original/PATH to /PATH and restore recorded mode/uid/gid.',
                            'For each previously absent file, remove only the listed /PATH.',
                            'Run systemctl daemon-reload; restore each recorded UnitFileState (enabled/disabled/masked) explicitly.',
                            'Restart bitchat only if previously active. Never start multiple radio owners.',
                            'Keep SPI1.1 when rolling back unrelated app/input behavior; SPI0 was already known wrong.']}
    (stage / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    os.chmod(stage / 'manifest.json', 0o600)


def run(args, check=True, timeout=25):
    try:
        result = subprocess.run(args, text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise ValueError(f'Command timed out: {args[0]} {args[1]}') from None
    if check and result.returncode:
        # Avoid printing systemctl ExecStart/Environment or parser output containing secrets.
        raise ValueError(f'Command failed ({result.returncode}): {args[0]} {args[1]}')
    return result


def unit_states(runner=run):
    result = {}
    properties = 'LoadState,ActiveState,SubState,UnitFileState,Job,User,WorkingDirectory,ExecStart'
    for unit in UNITS:
        response = runner(['/usr/bin/systemctl', 'show', unit, '--property=' + properties], check=False)
        result[unit] = dict(line.split('=', 1) for line in response.stdout.splitlines() if '=' in line)
        if response.returncode and result[unit].get('LoadState') != 'not-found':
            raise ValueError(f'Cannot inspect {unit}; no changes made')
    return result


def validate_runtime_config(root, states):
    for unit in RADIOS[:2]:
        info = states[unit]
        if info.get('LoadState') != 'loaded':
            raise ValueError(f'Install {unit} before configuring this profile')
        if info.get('UnitFileState') in ('masked', 'masked-runtime'):
            raise ValueError(f'{unit} is masked; review and unmask it before setup')
    # Refuse a different config selection rather than silently editing unused files.
    info = states['meshtasticd.service']
    command = info.get('ExecStart', '')
    match = re.search(r'(?:^|\s)(?:-c\s+|--config(?:=|\s+))([^\s;]+)', command)
    if match and match.group(1) != MAIN:
        raise ValueError('meshtasticd selects another config; review it before setup')
    working = info.get('WorkingDirectory') or '/'
    local_config = str(Path(working) / 'config.yaml')
    if not match and local_config != MAIN and rooted(root, local_config).exists():
        raise ValueError('Working-directory config.yaml overrides /etc/meshtasticd/config.yaml')
    command = states['meshcored.service'].get('ExecStart', '')
    match = re.search(r'(?:^|\s)(?:-c\s+|--config(?:=|\s+))([^\s;]+)', command)
    if match and match.group(1) != INI:
        raise ValueError('meshcored selects another config; review it before setup')


def install_plan(plan, root):
    validate_unchanged(plan, root)
    for change in plan:
        target = rooted(root, change.path)
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(prefix='.' + target.name + '.', dir=target.parent)
        try:
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(change.content)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, change.mode)
            if target.exists():
                stat = target.stat()
                os.chown(temporary, stat.st_uid, stat.st_gid)
            os.replace(temporary, target)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def apply_plan(plan, root, stage, states, touch_profile, runner=run, leave_stopped=False):
    validate_unchanged(plan, root)
    stage_plan(plan, root, stage, states)
    sudo_file = stage / 'staged/etc/sudoers.d/bitchat-lora-controller'
    if sudo_file.exists():
        runner(['/usr/sbin/visudo', '-cf', str(sudo_file)])
    # Capture backup before stopping anything. A later error leaves the app stopped
    # and points to the manifest; never automatically start a conflicting daemon.
    runner(['/usr/bin/systemctl', 'stop', 'bitchat.service'])
    stopped = RADIOS + (('xpt2046-touch.service',) if touch_profile == 'usb' else ())
    for unit in stopped:
        if states.get(unit, {}).get('LoadState') != 'not-found':
            runner(['/usr/bin/systemctl', 'stop', unit])
    current = unit_states(runner)
    for unit in ('bitchat.service',) + stopped:
        info = current[unit]
        if info.get('LoadState') == 'not-found':
            continue
        if info.get('ActiveState') not in ('inactive', 'failed') or info.get('Job') not in ('', '0', '[not set]'):
            raise ValueError(f'{unit} has not stopped; configuration remains unchanged. Backup: {stage}')
    for daemon in ('meshtasticd', 'meshcored'):
        process = runner(['/usr/bin/pgrep', '-x', daemon], check=False)
        if process.returncode != 1:
            raise ValueError(f'Cannot prove {daemon} is absent; stop manual owner before applying. Backup: {stage}')
    install_plan(plan, root)
    for unit in stopped:
        if states.get(unit, {}).get('LoadState') != 'not-found':
            runner(['/usr/bin/systemctl', 'disable', unit])
    runner(['/usr/bin/systemctl', 'daemon-reload'])
    runner(['/usr/bin/systemd-analyze', 'verify', 'bitchat.service', *RADIOS[:2]])
    if states['bitchat.service'].get('ActiveState') == 'active' and not leave_stopped:
        runner(['/usr/bin/systemctl', 'start', 'bitchat.service'])


def log_summary(json_lines):
    """Whitelist failure signatures; never emit application messages or credentials."""
    counts = {}
    patterns = (r'radio initialization failed', r'RF95 init result[ :()=-]*-?\d+',
                r'radio not found', r'configuration load failed', r'GPIO binding failed',
                r'Found ordering cycle', r'Start request repeated too quickly')
    for line in json_lines.splitlines():
        try:
            message = json.loads(line).get('MESSAGE', '')
        except (ValueError, AttributeError):
            continue
        if not isinstance(message, str):
            continue
        for pattern in patterns:
            match = re.search(pattern, message, re.IGNORECASE)
            if match:
                counts[match.group(0)] = counts.get(match.group(0), 0) + 1
    return json.dumps(counts, sort_keys=True)


def file_digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(block)
    return checksum.hexdigest()


def verify_runtime(runner=run):
    print('READ-ONLY LoRa evidence; no service changes, socket connections, GPIO requests or radio traffic.')
    states = unit_states(runner)
    for unit, state in states.items():
        fields = ('LoadState', 'ActiveState', 'SubState', 'UnitFileState', 'Job')
        print(unit + ': ' + ', '.join(f'{key}={state.get(key, "unavailable")}' for key in fields))
        if unit in RADIOS[:2]:
            status = runner(['/usr/bin/systemctl', 'show', unit,
                             '--property=Result,ExecMainStatus,NRestarts,CPUUsageNSec,Restart,RestartPreventExitStatus,TimeoutStopUSec'], check=False)
            print(status.stdout.strip() if status.returncode == 0 else 'Additional service status unavailable')
    try:
        for path in meshtastic_files(Path('/')):
            block = load_yaml(Path(path).read_text()).get('Lora', {})
            if block:
                fields = {key: block.get(key) for key in HW}
                fields['Reset_present'] = 'Reset' in block
                print(path + ': ' + json.dumps(fields, sort_keys=True))
        text = Path(INI).read_text()
        print(INI + ': ' + json.dumps(meshcore_hardware_fields(text), sort_keys=True))
    except (ValueError, OSError) as error:
        print(f'Config audit unavailable: {error}')
    print('SPI device nodes: ' + ', '.join(str(path) for path in sorted(Path('/dev').glob('spidev*'))))
    for path in (Path('/usr/bin/meshtasticd'), Path('/usr/local/bin/meshcored'),
                 Path('/opt/bitchat/releases/current/bitchat-embedded.kexe')):
        try:
            print(f'SHA256 {path}: {file_digest(path)}')
        except OSError:
            print(f'SHA256 {path}: unavailable')
    release = Path('/opt/bitchat/releases/current')
    if release.is_symlink():
        print('App release: ' + os.readlink(release))
    for name in Path('/sys/class/input').glob('event*/device/name'):
        try:
            value = name.read_text().strip()
            if value in ('CardKb-I2C', 'QDtech MPI5001', 'XPT2046 Touchscreen'):
                print(f'Input: {name.parent.parent.name} {value}')
        except OSError:
            pass
    try:
        for line in Path('/sys/kernel/debug/gpio').read_text().splitlines():
            if re.search(r'gpio-(70|71|73)\b', line):
                print('GPIO ownership: ' + line.strip())
    except OSError:
        print('GPIO ownership: unavailable (rerun verification with sudo to read debugfs)')
    for path in ('/usr/bin/ss', '/bin/ss'):
        if Path(path).exists():
            listeners = runner([path, '-ltnp'], check=False)
            print('Radio TCP listeners (no connection probe):')
            for line in listeners.stdout.splitlines():
                if re.search(r':(?:5000|4403)\s', line):
                    print(line)
            break
    for unit in ('bitchat.service',) + RADIOS[:2]:
        journal = runner(['/usr/bin/journalctl', '-b', '-u', unit, '-n', '200', '--no-pager', '-o', 'json'], check=False)
        print(f'{unit} recent failure signatures: ' + (log_summary(journal.stdout) if journal.returncode == 0 else 'journal unavailable'))
    print('Service state is not proof of radio identification or over-the-air messaging.')
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description='Audit the Orange Pi PCB profile without mutation; use --apply explicitly. Run on the Pi after copying this repository/package. Never transmits or probes the radio.')
    parser.add_argument('--verify', action='store_true', help='read-only service/config/hash/input/failure evidence; no radio probe')
    parser.add_argument('--apply', action='store_true', help='apply reviewed hardware fields/drop-ins and disable daemon autostart (requires root)')
    parser.add_argument('--stage', type=Path, help='write private staged files/backups and manifest to a new directory; audit alone writes nothing')
    parser.add_argument('--root', type=Path, default=Path('/'), help='offline fixture root for audit/staging only; apply requires /')
    parser.add_argument('--app-user', help='service account (default: live bitchat.service User)')
    parser.add_argument('--touch-profile', choices=('usb', 'resistive'), default='usb')
    parser.add_argument('--leave-stopped', action='store_true', help='leave app stopped after maintenance for a coordinated binary deployment')
    args = parser.parse_args(argv)
    root = args.root.resolve()
    stage = None
    try:
        if args.verify:
            if args.apply or args.stage or root != Path('/'):
                raise ValueError('--verify cannot be combined with apply, stage, or an offline root')
            return verify_runtime()
        if args.apply and (root != Path('/') or os.geteuid() != 0):
            raise ValueError('--apply requires root on the actual Pi; --root is audit-only')
        states = unit_states() if root == Path('/') else {}
        user = args.app_user or states.get('bitchat.service', {}).get('User')
        if not user:
            raise ValueError('Specify --app-user for offline audit or an app unit without User')
        if states:
            validate_runtime_config(root, states)
        # Root's sudo -l proves authorization, not NOPASSWD execution for the app.
        # Always manage the exact scoped rules; unchanged content/mode stays a no-op.
        plan = build_plan(root, Path(__file__).resolve().parents[1], user, args.touch_profile)
        print('APPLY' if args.apply else 'AUDIT (no runtime changes)')
        print('PCB: SPI1.1, IRQ gpiochip1/70; software reset omitted; PMIC and SPI overlays untouched.')
        print('Loaded Meshtastic files: ' + ', '.join(meshtastic_files(root)))
        for change in plan:
            print(f'  {change.path}: {"create" if change.original is None else "update"} (content withheld)')
        print('Disable autostart: ' + ', '.join(RADIOS) + (', xpt2046-touch.service' if args.touch_profile == 'usb' else ''))
        print('Controller sudo policy: exact scoped NOPASSWD rules managed idempotently')
        if args.apply:
            stage = args.stage or Path('/var/backups/bitchat-lora') / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
            apply_plan(plan, root, stage, states, args.touch_profile, leave_stopped=args.leave_stopped)
            print(f'Applied; rollback files and recorded enable states: {stage}/manifest.json')
        elif args.stage:
            stage_plan(plan, root, args.stage, states)
            print(f'Private stage and restore manifest: {args.stage}')
        return 0
    except (ValueError, OSError) as error:
        print(f'configure-pi-lora: {error}', file=sys.stderr)
        if stage and stage.exists():
            print(f'Maintenance incomplete; inspect {stage}/manifest.json before restarting the app.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
