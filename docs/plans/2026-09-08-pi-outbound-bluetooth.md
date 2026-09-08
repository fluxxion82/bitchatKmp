# Pi Outbound Bluetooth Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Understand and fix the degradation that stops the Orange Pi opening outbound BLE links, and
crash-proof the path that carries them.

**Architecture:** The Pi's BLE stack works and then stops working. Nothing here changes what the radio
is asked to do until we have evidence captured *while it is failing*, which we have never had. So the
work is: a health signal, a tally that uses it, a watch that turns the next occurrence into a capture,
and the lifetime bugs that are worth fixing whatever the cause turns out to be.

**Tech Stack:** Kotlin/Native `linuxArm64`, our gattlib fork (`fluxxion82/gattlib`, submodule at
`data/remote/transport/bluetooth/native/gattlib`), BlueZ 5.84 over D-Bus, kernel 6.12.67-sunxi64,
UART-attached Spreadtrum combo controller.

**Revision:** 3. Revisions 1 and 2 were both built on the premise that outbound BLE never works on this
device. That premise is false — see section 1.3, where the Pi is captured carrying a sustained
bidirectional mesh link. Section 6 records every wrong turn so nobody re-derives them.

---

## 1. What the device actually does

All measurements from `sterling@192.168.4.58` on 2026-09-08. The Pi runs the `apps/embedded` build; the
peer is a Pixel 4 XL running the KMP Android build (`com.bitchat.android`) with the app foregrounded.

### 1.1 Degraded

Six hours ending 11:33, on release `694bb5ef7cf5-debug-ca7065ab`, with the Pi up about 29 hours:

| Outcome | Count |
|---|---|
| Outbound connect requests | 51 |
| Outbound links established | 0 |
| Ended `org.bluez.Error.Failed: le-connection-abort-by-local` | 34 |
| Ended in the 25 s GDBus call timeout | 17 |
| `org.bluez.Error.InProgress` | 0 |
| Inbound GATT clients registered | 0 |

The hour after deploying `b64d3e56e7c2` at 12:53 was the same: 16 requests, 0 established, 15 of them
reaching `Connected: true` and losing the link immediately, and **16 kernel advertising failures** —
exactly one per attempt.

### 1.2 Healthy

Since the reboot at 14:39, uptime 13 minutes:

| | |
|---|---|
| Connect requests / established | 1 / 1 |
| Failed | 0 |
| Mesh peers | 2 |
| Broadcasts with real targets | 82 |
| Handshakes | 14 |
| SEGV | 0 |
| Kernel advertising failures | **0** |

Direct messages work. The owner confirms the Pi is connected to both Android apps.

### 1.3 The capture that ended the "outbound is broken" theory

`btmon` over 224 seconds ending 14:39, immediately before the reboot:

```text
39 x  ATT: Write Command (0x52) len 258            Pi -> peer
39 x  ATT: Handle Value Notification (0x1b) len 258    peer -> Pi
      Handle: 0x0088, one ACL connection (handle 16)
```

One connection, saturated with mesh traffic in both directions, for the whole window. No scanning, no
connection attempts and no disconnections in it. The outbound path — central role, GATT client, our
service characteristic — works completely.

### 1.4 The timeline that matters

| Time | Event | Outbound |
|---|---|---|
| 05:12–11:33 | ~29 h uptime, release `694bb5e` | 0 / 51 |
| 12:53 | deploy `b64d3e5`, service restarted | 0 / 16 over the next hour |
| 14:00–14:25 | central-only run (`BITCHAT_BLE_NO_ADVERTISE=1`) | 0 / 4 |
| ~14:30 | service restarted | recovered |
| 14:35–14:39 | btmon capture | one healthy link, 224 s of traffic |
| 14:39 | reboot | 1 / 1, two peers, DMs working |

Read it carefully, because it rules out the easy answers. A service restart at 12:53 did **not** fix
it. The device stayed broken for an hour afterwards and through a 25-minute central-only run. It
recovered around 14:30, before the reboot. So this is neither an app warm-up nor "restart fixes it":
something at the system level goes bad, survives an app restart, and clears on its own or on a reboot.

## 2. The health signal

`Bluetooth: hci0: Opcode 0x2036 failed: -16` and `0x2039 failed: -16` in `journalctl -k` — LE Set
Extended Advertising Parameters and Enable, refused as Command Disallowed.

- **Degraded:** exactly one per outbound connect request. 16 for 16 over the measured hour, 56 over the
  preceding eight hours.
- **Healthy:** zero, with advertising on and connects succeeding.

They are a symptom, not the cause: suppressing advertising entirely removed them and outbound still
failed 0 for 4 (section 6). But they are a cheap, unambiguous detector of the bad state, and detecting
the transition is the thing we have never managed to do.

## 3. What we do not know

- What degrades. It survives an app restart, so it is in the kernel, the controller or bluetoothd, not
  in our process.
- How long it takes. One observation of roughly a day of uptime is not a rate.
- Whether it correlates with the BlueZ device cache growing (RPA rotation mints a new device object per
  peer every few minutes; the cache was 8+ entries when sick and 3 shortly after boot), with kernel
  advertising instances being removed and not restored, or with something else entirely.
- The HCI disconnect reason for a failed attempt. Task 1 of revision 2 never got one: by the time
  `btmon` was available the device had recovered, so the capture we have is of the healthy state.

## 4. Constraints

- **One implementation agent per working tree.**
- **Do not change the shared Kotlin BLE interfaces.** A separate agent implements Linux *desktop* BLE
  against `GattClientService`, `CentralScanningService` and `BluetoothConnectionService`. Work here
  stays inside `linuxMain`, `commonMain` policy classes, and the gattlib fork. Confirm with the owner
  before touching the fork, in case that agent is in it too.
- **Do not push to origin.** The owner tests and reviews first.
- **No trailers in commit messages.**
- **A C change does not ship unless gattlib is rebuilt.** `scripts/verify.sh embedded` runs only the
  Kotlin link task and `apps/embedded/build.gradle.kts:63` links a prebuilt archive through `-L`, so
  editing C and running `verify.sh` silently ships the old library. Rebuild with:
  ```bash
  docker run --platform linux/amd64 --rm \
    -v "$PWD/data/remote/transport/bluetooth/native:/build" \
    bitchat-linux-arm64-cross bash /build/build-gattlib-linux-arm64.sh
  ```
  About 25 seconds. Record the archive's SHA-256 in the commit body and check it changed.
- **Never `./gradlew clean`.**
- `btmon` now runs unprivileged via `/etc/sudoers.d/50-btmon` (`sudo -n btmon`). Note the grant covers
  `btmon` only, so `sudo timeout … btmon` is refused; run it attached to a pty
  (`ssh -tt … 'sudo -n btmon -w FILE'`) with a client-side `timeout`, and keep its stdout on a pipe —
  a backgrounded run with stdout to `/dev/null` silently captures nothing.

---

## Task 4: Make outbound health countable *(next)*

**Files:**
- Create: `scripts/ble-outbound-report.sh`

**Step 1: Write the script**

Takes `--since`/`--until` (relative default, but it prints the resolved absolute window it used) and
`--host`, defaulting to `$PI_HOST` then `sterling@192.168.4.58`. From one
`journalctl -u bitchat.service` fetch and one `journalctl -k` fetch it prints:

- connect requests, established, `Failed to initiate`, abort-by-local, GDBus timeouts, `InProgress`;
- reaper abandonments and `attempt already abandoned` guard hits;
- inbound GATT clients, mesh peer count, broadcasts with a non-zero target count;
- **kernel advertising failures, and the ratio of them to connect requests** — the section 2 signal;
- `NRestarts` and any `status=11/SEGV`;
- the release identity from `/opt/bitchat/releases/current/BUILD_INFO`, refusing to report a window
  that spans a deployment;
- the journal's earliest retained timestamp, with a warning when the window starts before it. journald
  here is size-capped and vacuums silently, so an unwarned tally undercounts.

It ends with one line: `HEALTHY` when established > 0 and the advertising ratio is 0, `DEGRADED` when
the ratio is at or near 1 per request, `IDLE` when there were no requests at all.

Do not assert it reproduces any table in section 1 — a different window legitimately differs. Validate
it against a fixed window with hand-counted `grep`.

**Step 2: Validate against both states**

Run it over the degraded window and the healthy one:

```bash
scripts/ble-outbound-report.sh --since 2026-09-08T05:12 --until 2026-09-08T11:33   # expect DEGRADED
scripts/ble-outbound-report.sh --since 2026-09-08T14:39                            # expect HEALTHY
```

**Step 3: Commit**

```bash
git add scripts/ble-outbound-report.sh
git commit -m "Report whether the Pi's outbound BLE is healthy"
```

**Step 4: Arm the watch**

A monitor on the kernel signal, so the next degradation is caught as it happens rather than found
hours later:

```bash
ssh sterling@192.168.4.58 "journalctl -k -f -n 0" | grep --line-buffered -E "Opcode 0x203[69] failed"
```

On the first hit, capture immediately — this is the evidence revision 2's Task 1 was meant to produce:

```bash
timeout 240 ssh -tt sterling@192.168.4.58 'sudo -n btmon -w /tmp/degraded.btsnoop' | cat > /tmp/live.txt
```

Then read the LE Connection Complete `Status`/`Role` and the Disconnection Complete `Reason` for a
failed attempt, and write them up in `../docs/reviews/2026-09-08-pi-outbound-degradation.md`.

---

## Task 3: Make the reaper and gattlib agree *(after Task 4)*

**Files:**
- Modify: `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZConnectionService.kt`
- Modify: `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZGattClientService.kt`

`reapExpiredAttempts` (`BlueZConnectionService.kt:335`) calls `gattClient.disconnect(address)`, which
returns early at `BlueZGattClientService.kt:279` when there is no registry entry — always true for an
attempt that never connected. Native `gattlib_disconnect` also refuses a connection still in
`CONNECTING` (`gattlib.c:366`). So the policy releases the address while gattlib still holds the
attempt, and the next offer for it returns `GATTLIB_BUSY`.

Give the client service a way to abandon a never-connected attempt that actually reaches gattlib, and
have the reaper use it. If gattlib cannot cancel a `CONNECTING` device, the policy must not release the
address until gattlib reports `DISCONNECTED` — disagreeing in that direction is worse than waiting.

Note this path is currently unexercised: with the stack healthy the reaper never fires, and while
degraded every attempt returned a callback. Verify by inducing it rather than by waiting for it.

---

## Task 6: Offer an advertiser again while it is still advertising *(deferred)*

Real, and lower priority than revision 2 assumed. `gattlib_adapter.c:276-283` re-reports a device from
the PropertiesChanged handler only when its state is `NOT_FOUND`, which object-add leaves as
`DISCONNECTED` and only object-remove restores. So within one continuous scan a device is offered once
per tracked object and advertisement updates never re-offer it; re-offers arrive only through BlueZ
evicting and re-adding the device.

With a healthy stack one offer is enough — the single attempt since boot connected. What this costs us
is recovery from a *transient* failure, where we currently wait for BlueZ object churn instead of
retrying a peer we can still hear.

The fix is the already-reserved `GATTLIB_DISCOVER_FILTER_NOTIFY_CHANGE` bit (`include/gattlib.h:120`),
which `gattlib_adapter.c:452` stores in `ble_scan.enabled_filters` and no C code reads. Honour it:
re-report a `DISCONNECTED` device on an advertisement property change, never one that is `CONNECTING`
or `CONNECTED`. Do not add a new struct field, and pass the bit from both the filtered and fallback
scans in `BlueZScanningService.kt`.

Three hazards to handle in the same task, all raised in review and none yet addressed:

- **Threads.** `gattlib_common.c:268-302` spawns a `GThread` per callback and overwrites
  `handler->thread` without unref — one thread creation and one Kotlin/Native runtime attach per
  advertisement per peer, and a leaked `GThread` each.
- **Logging.** `BlueZConnectionService.kt:247` logs every policy Skip at debug and
  `BinaryProtocol.linux.kt:19` prints debug unconditionally, so this writes one journal line per
  advertisement per peer into a size-capped journal.
- **Unsynchronized state.** `BlueZScanningService`'s `discoveredDevices` and `deviceDiscoveryTime` are
  plain mutable collections written from gattlib's threads.

---

## Task 7: Re-tune the policy against measured data *(deferred)*

Only once the degradation is understood. Revisit `MAX_CONCURRENT_CONNECTS`, `MAX_CENTRAL_LINKS`,
`BASE_BACKOFF_MS` and `CONNECT_TIMEOUT_MS` against Task 4's tally, and rewrite the KDoc in
`CentralLinkPolicy` and `BlueZConnectionService`, which currently argues from anecdotes that this
plan's own measurements do not support. In particular `BlueZConnectionService`'s claim that BlueZ needs
its discovery session left running is not established by the experiment it cites:
`BlueZScanningService.stopScan` also calls `manager.stopMainLoop()`, so that experiment stopped
gattlib's entire event dispatch, not just the scan.

---

## Completed

### Task 2: Repair gattlib's connect teardown — done 2026-09-08

Fork commit `9e724bc`, pointer bump `b64d3e5`.

BlueZ can complete a connection whose `Connect()` call it has already failed, and this device does:
`Connect()` returned `le-connection-abort-by-local` and `ServicesResolved` turned true a fraction of a
second later, with the peer's services and name resolved. The failure path freed `device_object_path`
and left the proxy and its `g-properties-changed` handler attached, so that late signal ran
`_on_device_connect()` against an abandoned attempt, reported success, and the caller's service
discovery dereferenced the NULL path. `status=11/SEGV`, within a second of the first outbound link that
actually worked.

This is the crash in `CLAUDE.md` section 8 — "a few seconds after start, right after
`Discovering services`" — and it reproduced on demand by putting the Pixel next to the Pi with the app
foregrounded.

The fix tears the attempt's D-Bus resources down where it fails, ignores a signal that arrives for an
attempt already abandoned (the handler disconnect alone is not enough, because a signal already being
dispatched can be waiting on the mutex while the teardown runs), and NULLs `dbus_objects` after freeing
it. Zero SEGV since, across both the degraded and healthy states.

### `BITCHAT_BLE_NO_ADVERTISE` — done 2026-09-08

Commit `af50010`. Suppresses the advertisement so the Pi runs central-only, keeping the GATT server.
Written to test the peripheral-role hypothesis; kept because it is the cheapest way to take the
peripheral role out of any future experiment.

---

## 5. Definition of done

- `scripts/ble-outbound-report.sh` distinguishes the two states and is validated against both.
- A `btmon` capture exists of a **failed** attempt, with its LE Connection Complete `Status`/`Role` and
  Disconnection Complete `Reason`, written up in `../docs/reviews/`.
- The degradation has a mechanism, or a documented reason we stopped looking.
- `NRestarts` unchanged and no SEGV across a soak in the healthy state.
- The KDoc in `CentralLinkPolicy` and `BlueZConnectionService` describes the device's real behaviour.
- Nothing pushed to origin without the owner's say-so.

## 6. What earlier revisions got wrong

Kept so nobody re-derives them. Every one of these was stated with more confidence than the evidence
supported.

- **"Outbound never connects."** The premise of revisions 1 and 2, and false. Section 1.3 shows the Pi
  carrying a saturated bidirectional mesh link. Two adversarial reviews attacked the causal story built
  on this premise; neither reviewer nor author questioned the premise itself.
- **"BR/EDR inquiry contention causes `le-connection-abort-by-local`."** Revision 1. That string is
  BlueZ's catch-all: `att_connect_cb` in `src/device.c` converts nearly any ATT connect error into
  `-ECONNABORTED`, so it carries no diagnostic information at all.
- **"Our own peripheral role blocks outbound initiation."** Revision 2's leading hypothesis, from the
  1:1 kernel advertising failures and `check_pending_le_conn()`. Refuted on hardware: running
  central-only took the kernel failures to zero and outbound still failed 0 for 4.
- **"`60:76:5B` advertised for six hours and got one attempt."** That object lived eight minutes. The
  starvation finding in Task 6 survives; the example and "the advertiser with the best signal is
  retried least" did not.
- **"Our backoff governs almost nothing."** Overstated: 9 `within backoff` declines in the same window.
- **"The link came up after BlueZ failed the call."** Unsupported as stated. `gattlib.c:201` holds
  `m_gattlib_mutex` across the synchronous `Connect()`, so queued `Connected` signals drain
  milliseconds after it returns and journal ordering proves nothing about physical timing. The late
  completion is real — the SEGV depended on it — but the trace, not the ordering, is what shows it.
- **"Zero inbound clients."** The metric was wrong: `Server client connected` fires on a central's
  first `WriteValue`, so a connected phone that has not yet written is invisible to it.
- **"The upstream Android app cannot talk to the Pi at all."** `CLAUDE.md` section 8 says upstream
  clients reject KMP *mesh traffic*; extending that to BLE link establishment was an invention, and the
  owner reports both apps connecting.
- **"`scripts/verify.sh embedded` then `deploy-pi.sh` ships a C change."** It does not (section 4).
