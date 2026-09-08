# Pi Outbound Bluetooth Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the Orange Pi open outbound BLE links to bitchat advertisers, which it currently never does.

**Architecture:** Get a reason code before changing anything. The failure string the device reports
carries no diagnostic information, so the first tasks capture HCI evidence and repair the connection
lifetime bugs that any retry or async work would otherwise turn into crashes. Only then do we change
what the radio is asked to do.

**Tech Stack:** Kotlin/Native `linuxArm64`, our gattlib fork (`fluxxion82/gattlib`, submodule at
`data/remote/transport/bluetooth/native/gattlib`), BlueZ 5.84 over D-Bus, kernel 6.12.67-sunxi64,
UART-attached Unisoc combo controller.

**Revision:** 2. Revision 1 blamed BR/EDR inquiry contention. Adversarial review by Fable and by
Codex (gpt-6-astra) independently rejected that, and the corrections are folded in below. Section 6
records what revision 1 got wrong so nobody re-derives it.

---

## 1. What the device actually does

Measured on `sterling@192.168.4.58`, release `694bb5ef7cf5-debug-ca7065ab`, over the six hours ending
2026-09-08 11:33 local. Counts move with the window, so any later tally must pin absolute boundaries
rather than reproduce these exactly.

| Outcome | Count |
|---|---|
| Outbound connect requests | 51 |
| **Outbound links established** | **0** |
| Ended `org.bluez.Error.Failed: le-connection-abort-by-local` | 34 |
| Ended in the 25 s GDBus call timeout (GIO code 24) | 17 |
| `org.bluez.Error.InProgress` | 0 |
| Attempts abandoned by our reaper | 0 |
| Inbound (GATT server) clients registered | 0 |
| Kernel `Opcode 0x2036/0x2039 failed: -16` (8 h window) | 56 |

Outbound has a 100% failure rate. This is not a tuning problem, and no change to retry cadence fixes
it on its own.

**The concurrency cap is exonerated.** The journal shows no overlapping attempts; every failure is a
lone attempt with nothing else in flight. `MAX_CONCURRENT_CONNECTS = 1` is not the cause and this plan
does not begin by loosening it.

## 2. The failure string is not evidence

`org.bluez.Error.Failed: le-connection-abort-by-local` is a catch-all. In BlueZ 5.84, `att_connect_cb`
in `src/device.c` converts every ATT socket error other than its own `ECONNABORTED` branch into
`err = -ECONNABORTED`, which `src/error.c` renders as this string. `ETIMEDOUT`, `ECONNRESET`,
`ECONNREFUSED` and `ENOTCONN` all print it. The 34 aborts therefore say nothing about local versus
remote, nor about timing.

The original socket error exists only inside `bluetoothd`. Reading it, or the HCI events beneath it,
is the only way to learn why these fail — which is why Task 1 comes before every other task.

What the journal *does* establish: BlueZ emits `Connected` only from an mgmt Device Connected event,
which the kernel sends only after a successful LE Connection Complete. A `Connected: true` appears for
these attempts, so **an HCI link came up and then died before ATT was usable**. The failure is after
link establishment, not before it.

## 3. The leading hypothesis: our own peripheral role

Both reviewers arrived here independently, from different evidence.

**Kernel evidence.** `journalctl -k` carries 56 `Bluetooth: hci0: Opcode 0x2036 failed: -16` and
`0x2039 failed: -16` lines in eight hours — LE Set Extended Advertising Parameters and LE Set Extended
Advertising Enable, refused as Command Disallowed — clustered within a second of attempts ending. In a
central-role connect the kernel issues those from `hci_le_conn_failed()` and from the advertising
pause/resume around `hci_le_create_conn_sync()`. The resume path removes an advertising instance it
cannot re-enable. So each outbound attempt is asking this firmware to stop and restart advertising,
the firmware refuses, and our advertisement can silently disappear — which is a mechanism for the zero
inbound clients in the same window, since a phone that cannot see the Pi never dials it.

**Kernel capability gate.** `check_pending_le_conn()` in `net/bluetooth/hci_event.c` refuses to start
an initiation when `le_num_peripheral > 0` and the controller either carries
`HCI_QUIRK_BROKEN_LE_STATES` or lacks the required LE Supported State bit. A single silent inbound ACL
— a phone connected but not yet writing, which our GATT server does not count until its first
`WriteValue` — would suppress every outgoing initiation while the application's inbound count stayed
at zero.

Both mechanisms predict the kernel log. Inquiry contention does not. Neither is confirmed; Task 1
distinguishes them.

## 4. What we do not know

- The HCI reason code for any of these failures.
- This controller's LE Supported States, whether it can be peripheral and initiator at once, and
  whether the kernel has quirked it.
- Whether an inbound ACL existed during the observation window.
- Whether our advertising instance survives an outbound attempt.

## 5. Constraints

- **One implementation agent per working tree.** A previous session deployed a build containing another
  agent's uncommitted changes; the build identity caught it.
- **Do not change the shared Kotlin BLE interfaces.** A separate agent implements Linux *desktop* BLE
  against `GattClientService`, `CentralScanningService` and `BluetoothConnectionService`. Work here
  stays inside `linuxMain`, `commonMain` policy classes, and the gattlib fork. **Task 2 and Task 3
  change gattlib**, so confirm with the owner that the desktop agent is not in the same fork before
  starting them.
- **Do not push to origin.** The owner tests and reviews first.
- **No trailers in commit messages.** Short imperative summary, body explaining why, nothing after it.
- **A C change does not ship unless gattlib is rebuilt.** `scripts/verify.sh embedded` runs only
  `:apps:embedded:linkDebugExecutableLinuxArm64`, and `apps/embedded/build.gradle.kts:63` links a
  prebuilt archive directory through `-L`. No Gradle task depends on the C sources, so editing C and
  running `verify.sh` produces a binary with the old library and no warning. Every C task must run
  `data/remote/transport/bluetooth/native/build-gattlib-linux-arm64.sh` first and record the resulting
  archive's SHA-256 in the commit body. That script `rm -rf`s its own build directory, which is fine —
  the "never clean native modules" rule is about libsodium, secp256k1, noise-c and Arti, which take
  hours; gattlib takes a minute.
- **Never `./gradlew clean`.**

---

## Task 1: Get a reason code

No code changes. Nothing else in this plan may start until this task has produced an HCI trace.

**Needs the owner.** `sterling` has `(ALL : ALL) ALL` in sudoers, but only the listed `systemctl`
invocations are `NOPASSWD`, so `btmon` needs a password and cannot be run from an automated session.
Either the owner runs the capture, or the owner grants `NOPASSWD` for `/usr/bin/btmon` first. Ask;
do not assume.

**Files:**
- Create: `docs/reviews/2026-09-08-pi-outbound-hci-trace.md`

**Step 1: Capture the unchanged failure**

With `bitchat.service` running and a phone advertising nearby:

```bash
ssh -t sterling@192.168.4.58 'sudo btmon -w /tmp/bitchat-outbound.btsnoop'
```

Leave it until the journal shows at least three completed outbound attempts:

```bash
ssh sterling@192.168.4.58 'journalctl -u bitchat.service -f | grep -E "Connect request|abort-by-local|domain 197"'
```

**Step 2: Capture the surrounding state in the same window**

```bash
ssh sterling@192.168.4.58 'journalctl -k --since "-15min" --no-pager | grep -i bluetooth' 
ssh sterling@192.168.4.58 'bluetoothctl show'
```

Sample `bluetoothctl show | grep -E "ActiveInstances|Discovering"` once a second across an attempt, so
we learn whether our advertising instance survives it.

**Step 3: Read the answers out of the trace**

```bash
ssh sterling@192.168.4.58 'btmon -r /tmp/bitchat-outbound.btsnoop' > /tmp/trace.txt
```

The trace must answer all of:

- **LE Read Local Supported States** — can this controller be peripheral and initiator simultaneously?
- For each attempt: does **LE Extended Create Connection** get issued at all, or does the kernel
  suppress it? Suppression confirms section 3's capability gate.
- **LE Connection Complete**: status, and `Role` (central or peripheral — a peripheral role here means
  an inbound connection was bound to our outbound attempt).
- **Disconnection Complete**: the reason code. 0x3E, 0x13, 0x08 and 0x16 each point somewhere different.
- Were **LE Set Extended Advertising Enable** commands issued around the attempt, and what did the
  controller answer?
- Was an ACL already up when the attempt started?

**Step 4: Write it up and re-plan**

`docs/reviews/2026-09-08-pi-outbound-hci-trace.md` records the above verbatim, then states which of
these the evidence supports:

- **The kernel suppresses initiation while we advertise or hold a peripheral link** — the fix is to
  stop advertising for the duration of an attempt, or to accept that this controller cannot do both
  and make the Pi peripheral-only. Tasks 2 and 3 still apply; Task 5's filter change does not.
- **The link comes up and is torn down by the remote** — the peer is refusing us; investigate the
  phone side and the address type.
- **The link comes up and is torn down locally** — read which command did it.
- **Something else** — re-plan against the trace.

**Step 5: Commit**

```bash
git add docs/reviews/2026-09-08-pi-outbound-hci-trace.md
git commit -m "Record why the Pi's outbound connects fail"
```

---

## Task 2: Repair gattlib's connect teardown

A prerequisite for Tasks 3, 4 and 5, and independently worth doing: this is a live crash. It does not
depend on Task 1's outcome, so it can proceed in parallel with the capture.

**Files:**
- Modify: `data/remote/transport/bluetooth/native/gattlib/dbus/gattlib.c`

**Step 1: Fix the failure path**

`gattlib.c:277-295`: when `Connect()` fails, the code sets the device `DISCONNECTED` and frees
`device_object_path`, but leaves the `bluez_device` proxy and the `g-properties-changed` handler
registered at `:251-255` attached, and leaves `on_handle_device_property_change_id` set.

A second attempt to the same address then creates a second proxy and handler and overwrites that id,
so the first can never be disconnected. After any later successful connect, each `Connected: false`
runs `gattlib_on_disconnected_device` twice; `gattlib_connection_free` at `:320-345` frees
`dbus_objects` without NULLing it, and the second pass calls `g_list_free_full` on freed memory.

The failure path must disconnect the property handler, unref the proxy, and clear the id — the same
teardown `gattlib_connection_free` does — before `FREE_DEVICE`. `gattlib_connection_free` must NULL
`dbus_objects` after freeing it.

**Step 2: Fix the late-callback path**

`_on_device_connect` at `gattlib.c:18-53` has no state check. After a client-side timeout,
`device_object_path` has been freed and NULLed but the handler is still attached, so a late
`ServicesResolved` marks the device `CONNECTED` and fires the success callback. Kotlin then calls
`discoverServices`, and `strcmp(NULL, ...)` at `gattlib.c:607` and `gattlib_char.c:64` segfaults.

This is very likely the SEGV recorded in `CLAUDE.md` section 8 — "a few seconds after start, right
after `Discovering services`". `_on_device_connect` must ignore an event for a device whose attempt
has already been abandoned.

**Step 3: Rebuild the library**

```bash
data/remote/transport/bluetooth/native/build-gattlib-linux-arm64.sh
sha256sum data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/libgattlib.a
```

**Step 4: Build, deploy, soak**

```bash
scripts/verify.sh embedded && scripts/deploy-pi.sh
```

Leave it for two hours, then:

```bash
ssh sterling@192.168.4.58 'systemctl show bitchat.service -p NRestarts --value'
```

Expected: unchanged across the soak. Compare against the same figure before deploying — this is the
first direct test of whether the known SEGV is this bug.

**Step 5: Commit**

```bash
git -C data/remote/transport/bluetooth/native/gattlib commit -am "Finish tearing down a connection attempt that failed"
git add data/remote/transport/bluetooth/native/gattlib
git commit -m "Stop a failed connect leaving a handler on a freed connection"
```

---

## Task 3: Make the reaper and gattlib agree

**Files:**
- Modify: `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZConnectionService.kt`
- Modify: `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZGattClientService.kt`

`reapExpiredAttempts` at `BlueZConnectionService.kt:335` calls `gattClient.disconnect(address)`, which
returns early at `BlueZGattClientService.kt:279` whenever there is no registry entry — always true for
an attempt that never connected. Native `gattlib_disconnect` also refuses a connection still in
`CONNECTING` (`gattlib.c:366`). So the policy releases the address while gattlib still believes an
attempt is in flight, and the next offer returns `GATTLIB_BUSY`.

Give the client service a way to abandon a never-connected attempt that actually reaches gattlib, and
have the reaper call it. If gattlib cannot cancel a `CONNECTING` device, the policy must not release
the address until gattlib reports it `DISCONNECTED` — a lie in the other direction is worse than a
delay.

Verify on the device that a reaped address is retried and does not return `GATTLIB_BUSY`.

---

## Task 4: Make outbound outcomes countable

**Files:**
- Create: `scripts/ble-outbound-report.sh`

Takes `--since`/`--until` as absolute timestamps (a relative default is fine, but the script prints the
resolved absolute window it used), and `--host`, defaulting to `$PI_HOST` then
`sterling@192.168.4.58`. From one `journalctl -u bitchat.service` fetch and one `journalctl -k` fetch,
it prints the table in section 1 — including the kernel opcode failures, which are the most
informative row.

It must also:

- print the release identity from `/opt/bitchat/releases/current/BUILD_INFO`, and refuse to report a
  window that spans a deployment;
- report the journal's earliest retained timestamp and warn when the requested window starts before it
  — journald is size-capped here and silently vacuums, so an unwarned tally undercounts;
- report `NRestarts` for the window.

Do not assert it reproduces section 1's numbers: a different window legitimately yields different
counts. Validate it instead against a fixed window with hand-counted `grep` output.

---

## Task 5: Whatever Task 1 says the fix is

Deliberately unwritten. Revision 1 specified a `Transport: le` filter change and a rediscovery change
here as though the cause were known; it was not. Fill this in from the trace.

If the trace does point at the discovery filter, note that gattlib's `SetDiscoveryFilter`
(`gattlib_adapter.c:405-440`) sets only `UUIDs` and `RSSI`, so BlueZ defaults `Transport` to `auto`;
adding `"le"` is a two-line change, but bit 2 of the filter flags is already taken by
`GATTLIB_DISCOVER_FILTER_NOTIFY_CHANGE` (`include/gattlib.h:120`) so a new flag needs a new bit. Note
also that `Transport: le` does not stop BR/EDR page or inquiry *scan*, and this adapter is discoverable
with no timeout.

---

## Task 6: Offer an advertiser again while it is still advertising

Only after Tasks 2 and 3. Raising the retry rate on top of the teardown bugs ships a crash.

`gattlib_adapter.c:276-283` re-reports a device from the PropertiesChanged handler only when its state
is `NOT_FOUND`, which is set on object-add and cleared only on object-remove (`:208`), and not even
then for active states (`gattlib_device_state_management.c:96-112`). So within one continuous scan a
device is offered to the application once per tracked object, and advertisement updates never re-offer
it. Re-offers reach us only when BlueZ evicts and re-adds the device — 51 attempts across 45 objects in
six hours, with 9 of them then declined by our own backoff.

The fix is the already-reserved `GATTLIB_DISCOVER_FILTER_NOTIFY_CHANGE` bit, which
`gattlib_adapter.c:452` already stores in `ble_scan.enabled_filters` and no C code reads. Honour it:
re-report a `DISCONNECTED` device on an advertisement property change, never one that is `CONNECTING`
or `CONNECTED`. Do not add a new struct field.

Three things this makes worse and must be handled in the same task:

- **Threads.** `gattlib_common.c:268-302` spawns a `GThread` per callback and overwrites
  `handler->thread` without unref. One thread creation plus a Kotlin/Native runtime attach per
  advertisement per peer, and a leaked `GThread` each.
- **Logging.** `BlueZConnectionService.kt:247` logs every policy Skip at debug and
  `BinaryProtocol.linux.kt:19` prints debug unconditionally, so this writes one journal line per
  advertisement per peer into a size-capped journal.
- **Unsynchronized state.** `BlueZScanningService`'s `discoveredDevices` and `deviceDiscoveryTime` are
  plain mutable collections written from gattlib's threads.

The policy tests in `CentralLinkPolicyTest` should pin the rate-limiting behaviour, but be honest that
they already pass against the current policy: they are a regression pin, not TDD, and they exercise
none of the native hazards above.

---

## Task 7: Re-tune the policy against measured data

Last, and only with a working link. Revisit `MAX_CONCURRENT_CONNECTS`, `MAX_CENTRAL_LINKS`,
`BASE_BACKOFF_MS` and `CONNECT_TIMEOUT_MS` against Task 4's tally, and rewrite the KDoc in
`CentralLinkPolicy` and `BlueZConnectionService` to describe what the device actually does.

Two claims in that KDoc are currently unsupported and should be corrected or removed. "Zero
`InProgress` proves the backoff works" is weak: with roughly one attempt per fresh object there was
little opportunity for it to occur. And `BlueZConnectionService`'s claim that BlueZ needs its discovery
session left running is not established by the experiment it cites — `BlueZScanningService.stopScan`
also calls `manager.stopMainLoop()`, so that experiment stopped gattlib's entire event dispatch, not
just the scan.

---

## 6. What revision 1 got wrong

Kept so nobody re-derives it.

- **"BR/EDR inquiry contention causes `le-connection-abort-by-local`."** False reasoning: that string
  is BlueZ's catch-all for nearly any ATT connect error (section 2). Inquiry may still cost us
  advertisement receptions, but nothing observed makes it the cause.
- **"`60:76:5B` advertised for six hours and got one attempt."** Wrong. That object lived eight
  minutes, from 11:14:13 to 11:22:36, with about 93 RSSI updates. The starvation finding survives; the
  example did not, and neither did "the advertiser with the best signal is retried least".
- **"Our backoff governs almost nothing."** Overstated: 9 `within backoff` declines appear in the same
  window.
- **"The link came up after BlueZ failed the call."** Unsupported. `gattlib.c:201` holds
  `m_gattlib_mutex` across the synchronous `Connect()`, so queued `Connected` signals drain
  milliseconds after it returns. Journal ordering here is an artifact, and revision 1's Task 6 was
  built on it.
- **"The reaper never fires because the blocking call returns first."** A tautology given zero
  successful connects.
- **"`scripts/verify.sh embedded` then `deploy-pi.sh` ships a C change."** It does not (section 5).

## 7. Definition of done

- `docs/reviews/2026-09-08-pi-outbound-hci-trace.md` records the HCI reason code, so the next person
  does not repeat the capture.
- `scripts/ble-outbound-report.sh` over a fixed window shows established > 0 with a phone nearby.
- A message sent from the Pi reaches a **phone running the KMP Android build** and not connected to the
  Pi's GATT server. It must be the KMP build: `CLAUDE.md` section 8 records that upstream Android and
  iOS clients reject KMP mesh traffic, so an upstream phone cannot demonstrate this.
- Inbound is explained: either a phone-visible advertisement survives an outbound attempt, or we know
  why it does not.
- `NRestarts` is unchanged across a two-hour soak and no SEGV appears.
- The KDoc in `CentralLinkPolicy` and `BlueZConnectionService` describes the device's real behaviour.
- Nothing pushed to origin.
