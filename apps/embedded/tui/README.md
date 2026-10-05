# Embedded terminal UI deployment

This is the Mosaic terminal UI board. It is independent of the Compose board: the Compose
README, `/opt/bitchat/releases`, `bitchat.service`, and their deployment behaviour remain
separate and unchanged.

`apps:embedded:tui:linkDebugExecutableLinuxArm64` produces:

```text
apps/embedded/tui/build/bin/linuxArm64/debugExecutable/bitchat-tui.kexe
apps/embedded/tui/build/bin/linuxArm64/debugExecutable/bitchat-tui.build-info
```

The release task uses the corresponding `releaseExecutable` directory. The sidecar records the
binary SHA-256 and its `identity=` line. `bitchat-tui.kexe --version` prints that line and exits
before opening `/dev/tty`, initializing Mosaic, or connecting to Bluetooth, so the deployer's
non-PTY SSH verification is safe.

## Deploy

No device name or account is stored in this repository. Set `PI_HOST` to an SSH destination or
pass `--host`; key-based SSH must work in BatchMode. `PI_USER` defaults to the user part of a
`user@host` destination and must be supplied for a bare host or SSH alias. `PI_GROUP` defaults to
`PI_USER`.

```bash
export PI_HOST=user@tui-pi
scripts/deploy-pi.sh --ui tui
scripts/deploy-pi.sh --ui tui --release
scripts/deploy-pi.sh --ui tui --no-build
scripts/deploy-pi.sh --ui tui --no-restart
scripts/deploy-pi.sh --ui tui --dry-run
PI_USER=user scripts/deploy-pi.sh --ui tui --host tui-pi
```

Each release is staged under
`/opt/bitchat-tui/releases/<sha12>[-dirty]-<debug|release>-<digest8>/`, then selected by an
atomic `/opt/bitchat-tui/releases/current` symlink. A release contains the executable, rendered
`bitchat-tui.service`, `bluetooth-bitchat-ble.conf`, `BUILD_INFO`, and `SHA256SUMS`. The release
digest covers the executable, rendered unit, and bluetoothd drop-in; it does not depend on the
deployment timestamp in `BUILD_INFO`.

`--dry-run` does the local build/stage and prints the release, `BUILD_INFO`, manifest, and staged
size without SSH, rsync, changing the current link, installing a unit, or restarting anything.

## Owner setup before the first deploy

Run this once on the device with the owner's password, replacing `sterling` only if the deploy
account is different:

```sh
sudo install -d -o sterling -g sterling -m 755 /opt/bitchat-tui /opt/bitchat-tui/releases
sudo install -o sterling -g sterling -m 644 /dev/null /opt/bitchat-tui/bitchat-tui.service
sudo usermod -aG systemd-journal sterling
sudo systemctl enable --now getty@tty2.service
# Only give tty1 away once tty2 really has a login on it: the && is load-bearing. Run this over SSH,
# or from tty2 itself - stopping tty1's getty kills a login session on tty1.
systemctl is-active getty@tty2.service && sudo systemctl disable --now getty@tty1.service
```

`bitchat-tui.service` owns tty1: there is no login there. At the device, `Alt+F2` (or `Alt+Right`)
switches to the login console on tty2 and `Alt+F1` switches back. `Ctrl+C` only restarts the app
(the unit has `Restart=always`, back after five seconds); to stop it, run
`sudo systemctl stop bitchat-tui.service` from tty2 or over SSH, and `start` to bring it back.
Enable `getty@tty2.service` before disabling tty1's getty to
keep a local login console reachable. Without tty2 (or another configured console), SSH is the
only way back in if the TUI fails. Reconnect after `usermod` so journal permissions take effect.
To return tty1 to a login console after intentionally disabling the TUI, run:

```sh
sudo systemctl enable --now getty@tty1.service
```

The deploy runs its privileged steps with `sudo -n`, so the deploy account needs them in sudoers. A
board that only runs the terminal UI has no allowlist yet (the terminal UI board had none on
2026-10-04: `/etc/sudoers.d` held only its `README`), so create one with
`sudo visudo -f /etc/sudoers.d/bitchat-tui` and the device's `/usr/bin` paths. On a board that
already carries the Compose entries, keep those and leave out the `daemon-reload` it already has:

```sudoers
sterling ALL=(root) NOPASSWD: /usr/bin/systemctl daemon-reload, /usr/bin/systemctl start bitchat-tui.service, /usr/bin/systemctl stop bitchat-tui.service, /usr/bin/systemctl restart bitchat-tui.service, /usr/bin/systemctl status bitchat-tui.service, /usr/bin/systemctl enable bitchat-tui.service, /usr/bin/systemctl disable bitchat-tui.service, /usr/bin/systemctl is-active bitchat-tui.service, /usr/bin/install -m 644 -o root -g root /opt/bitchat-tui/bitchat-tui.service /etc/systemd/system/bitchat-tui.service
```

The `install` entry copies a file the deploy account owns into systemd's unit directory, and a unit
can run anything as root. Granting it is therefore granting that account passwordless root on the device; it is
what lets a deploy update the unit without a password, and it is the same trade the Compose board
makes.

The deployment script renders the unit with the selected account, installs it, enables/restarts
only `bitchat-tui.service`, and polls the new journal invocation for its exact identity line.
After the first installation, verify the unit and its terminal wiring on the device:

```sh
sudo systemd-analyze verify /etc/systemd/system/bitchat-tui.service
systemctl show bitchat-tui.service -p StandardInput -p StandardOutput -p StandardError -p TTYPath
```

The values must be `StandardInput=tty`, `StandardOutput=journal`, `StandardError=journal`, and
`TTYPath=/dev/tty1`. `After=multi-user.target getty@tty1.service` is also load-bearing: the
explicit target ordering suppresses systemd's implicit reverse target edge. Check the exact
loaded order after installation, using the same check as the Compose service:

```sh
systemctl show -p After --value bitchat-tui.service
systemctl show -p After --value multi-user.target
```

The second command must not list `bitchat-tui.service`. A restart cannot prove this boot-time
ordering; reboot once and verify that `bitchat-tui.service` is active, `getty@tty1.service` is
inactive, tty1 shows an interactive frame, and `journalctl -b -g 'ordering cycle'` has no output.

## One app per board

Both embedded binaries take one lock before anything else (`SingleInstanceLock` in `:apps:embedded`,
a `flock` on `~/.bitchat/instance.lock`), because they share the LoRa radio, the Bluetooth adapter,
the identity and `~/.bitchat`: two at once split what is received between them. While the unit
runs, `~/bitchat-tui.kexe` over SSH prints
`another bitchat embedded app is running (bitchat-tui, pid 812 on /dev/tty1)` on stderr and exits
with status 75 before it changes the terminal, opens `~/.bitchat/tui.log`, or touches the radio or
Bluetooth; `--version` never takes the lock. To run it by hand, stop the unit first
(`sudo systemctl stop bitchat-tui.service`) and `start` it afterwards. The lock is the kernel's, not
the file's: it ends with its holder (exit, crash, power cut), so the file left behind never blocks a
start, and a lock that cannot be set up at all only prints a warning and the app starts without it.
The other way round, a unit started while a hand-run copy holds the board is refused as well:
`RestartPreventExitStatus=75` keeps `Restart=always` from trying again every five seconds, so the
unit goes to `failed` (`status=75/TEMPFAIL` in `systemctl status`, the refusal line in the journal),
tty1 keeps whatever was on it and reads no keys, and nothing starts the unit when the other copy
exits: run `sudo systemctl start bitchat-tui.service`. The lock is per home directory, so a copy run
as another user (`sudo`) is not caught.

## Logs, Bluetooth, and rollback

Mosaic frames render on tty1. Its stdout and stderr remain in the journal:

```sh
journalctl -u bitchat-tui.service -f
systemctl is-enabled bitchat-tui.service; systemctl is-active bitchat-tui.service
```

The release carries `bluetooth-bitchat-ble.conf`, the same bluetoothd drop-in carried by the
Compose release. Install it once by hand after checking the device's bluetoothd path; otherwise
iPhones can receive pairing prompts on reconnect. The deploy script warns (without failing) if
the installed drop-in or running bluetoothd command drifts from the staged copy.

```sh
systemctl cat bluetooth.service | grep '^ExecStart='
sudo install -D -m 644 -o root -g root /opt/bitchat-tui/releases/current/bluetooth-bitchat-ble.conf \
  /etc/systemd/system/bluetooth.service.d/bitchat-ble.conf
sudo systemctl daemon-reload && sudo systemctl restart bluetooth.service && sudo systemctl restart bitchat-tui.service
```

The convenience link `~/bitchat-tui.kexe` follows the TUI `current` release. On migration from
a regular hand-installed file, the deployer moves it to `~/bitchat-tui.kexe.prev.bak` only when
that name does not exist; it never overwrites that backup and otherwise uses a dated
`.stale-YYYY-MM-DD` name. A failure to update this convenience link is only a warning.

To roll back, select a prior TUI release only; this never touches the Compose release tree:

```sh
ln -sfn /opt/bitchat-tui/releases/<old> /opt/bitchat-tui/releases/current.tmp \
  && mv -T /opt/bitchat-tui/releases/current.tmp /opt/bitchat-tui/releases/current \
  && sudo systemctl restart bitchat-tui.service
```

Successful deploys print the equivalent SSH rollback command when they replaced a different
prior TUI release. Old release directories are retained until the owner removes them manually.
