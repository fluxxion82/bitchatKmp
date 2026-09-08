# Embedded Bluetooth hardening: advertising registration, gattlib NULL guards, discovery leaks, linker flag

Branch base: `main` @ `11ded25`. Target: `apps/embedded` on the Orange Pi Zero 3 (`sterling@192.168.4.58`).

## Goal

Four independent defects in the embedded Bluetooth stack, landing in **one binary and one announced
deploy**:

1. `RegisterAdvertisement` reports failure on every start even though BlueZ registers the
   advertisement. Remove the false failure and the fallback path it triggers.
2. gattlib dereferences D-Bus property getters that return `NULL` when a peer's BlueZ objects vanish
   mid-discovery. Best remaining candidate for the unexplained `SIGSEGV` on 2026-09-07 17:54:45.
3. gattlib's discovery out-params are `calloc`'d arrays that the Kotlin caller never frees.
4. `--allow-shlib-undefined` in `apps/embedded/build.gradle.kts` may no longer be needed and is
   hiding real link errors. Fifteen-minute experiment, pass or fail.

## Context: what was verified on 2026-09-07, and what it changes

Read-only inspection of the running device (uptime 25 min, `bitchat.service` active since
21:00:19, `NRestarts=0`) and of the repo. **The service was not restarted.**

| # | Claim | Verdict | Evidence |
|---|---|---|---|
| E1 | The registration reply times out | Confirmed | `21:00:21.226949` "sending D-Bus call (5s timeout)" → `21:00:26.294510` "D-Bus call returned". 5.068 s. `DBUS_ERROR_NO_REPLY`. |
| E2 | BlueZ registers it anyway | Confirmed | `busctl get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances` → `y 1`; `SupportedInstances` → `y 15`. |
| E3 | The app really is advertising the bitchat service UUID | Confirmed (indirectly, strongly) | E2, plus live peripheral-role traffic: peer `56:CC:FB:01:5E:0B` is `Connected=true` and repeatedly calls `WriteValue` on `/org/bitchat/gatt/service0/char0`, and we `Notify` back. A central found and connected to us. |
| E4 | Blocking D-Bus calls on this connection are broken in general | **Refuted** | The very next blocking call — `Set Discoverable` at `21:00:26.296073` → `21:00:26.307206` — returned in **11 ms**, on the same connection, with the same dispatch worker running. |
| E5 | The fallback expires after `DiscoverableTimeout` | **Not currently true on this device** | `DiscoverableTimeout` reads `u 0`. `/etc/bluetooth/main.conf` sets nothing (all sections empty), so this is persisted adapter state, not configuration, and can change. The hardening is still worth doing; the urgency is lower than assumed. |
| E6 | A leaked advertising instance per restart | **Partly refuted** | `ActiveInstances=1` with `NRestarts=0` — no cross-restart accumulation. BlueZ frees the instance when our bus connection drops at process exit. The real leak is *within* a process: see T2. |
| E7 | A gattlib rebuild is hours-expensive | **Refuted** | Measured: **23.4 s** for a cold rebuild (23 C objects) in the existing `bitchat-linux-arm64-cross` image. The result is byte-reproducible against the committed prebuilt: both `sha256 989e1e6903d60d3ca9ad40c6ec504aedca6a84862ab43595ee68cbb5addf6717`, 550506 bytes. Arti is the hours-expensive one (28 MB archive, 15–30 min per `scripts/build-native-linux-arm64.sh:96`). |
| E8 | `--allow-shlib-undefined` is about Skiko's X11/GLX symbols | **Refuted** | Skiko `0.9.47` ships **static** archives inside `skiko.klib` (`default/targets/linux_arm64/included/*.a`). `--allow-shlib-undefined` has no effect on static archives — it only relaxes symbol resolution for **shared** objects on the link line. See T7 for what it is actually covering. |
| E9 | The embedded app unregisters advertising on shutdown | **Refuted** | No `SIGTERM`/`atexit`/shutdown handler exists anywhere under `apps/embedded/src/`. `stopAdvertising()` is reachable only via `BluetoothMeshService.stopServices()` (`BluetoothMeshService.kt:470-479`). |

E7 is the finding that reorders this plan: the gattlib work is cheap, not a project.

## Architecture: the D-Bus threading model this code assumes

`BlueZAdvertisingService` and `BlueZGattServerService` **both** call `dbus_bus_get(DBUS_BUS_SYSTEM, …)`,
which returns libdbus's *shared* connection — the same `DBusConnection*` object for the whole process.
Each then starts its own dispatch worker on it:

```
                       one shared DBusConnection (dbus_bus_get SYSTEM)
                                        |
   coroutine thread ------- dbus_connection_send_with_reply_and_block(5000)   <-- holds the I/O path
   Worker "DBusAdvDispatch" -- read_write(100) + dispatch()   BlueZAdvertisingService.kt:271-287
   Worker "DBusDispatch" ----- read_write(100) + dispatch()   BlueZGattServerService.kt:783-800
```

`dbus_connection_send_with_reply_and_block` blocks in `poll()` holding the connection's I/O path for
the duration of the timeout. While it does, no other thread on that connection can perform socket
**writes**.

### Root cause of the registration timeout

`RegisterAdvertisement` is the one call in this file where **bluetoothd must call back into our
process before it can reply**: it does `org.freedesktop.DBus.Properties.GetAll` on
`/org/bitchat/advertisement0` to read `Type`, `ServiceUUIDs`, `LocalName` and `Discoverable`.
`Set Discoverable` (E4, 11 ms) has no such callback. That is the only structural difference between
the call that stalls and the call that does not.

Our `GetAll` / `Get` / `Introspect` / `Release` handlers reply with a bare
`dbus_connection_send(dbusConnection, reply, null)` and **never flush** —
`BlueZAdvertisingService.kt:348`, `:383`, `:409`, `:417`. Every *other* send in the same file does
flush: `setAdapterAlias` (`:545`), `unregisterAdvertisement` (`:578`), `disableDiscoverable`
(`:627`). `dbus_connection_send` only queues; the bytes leave the process on the next socket write.

Leading hypothesis, consistent with E1–E4:

> The reply to bluetoothd's `GetAll` is queued by the dispatch worker but cannot be written, because
> the coroutine thread holds the I/O path inside its 5 s blocking call. bluetoothd waits for
> properties it will not receive until our own timeout expires. At `t=5 s` we give up and release the
> I/O path, the queued reply goes out, bluetoothd completes registration (→ E2 `ActiveInstances=1`)
> and sends a reply that lands on a pending call we have already abandoned.

A self-inflicted stall, not a slow daemon.

Of the four causes named at the outset:

- *"a reply routed to a filter that is not yet installed"* — **ruled out by code order**:
  `startDbusDispatchLoop()` (`:72`, which registers the object path at `:241` and the filter at
  `:255`) runs *before* `registerAdvertisement()` (`:74`).
- *"the dispatch loop not running while the call is in flight"* — **ruled out by E2**: if the loop
  were dead, `GetAll` would never be answered and bluetoothd would have failed the registration.
- *"a blocking call on the wrong thread"* — **this, in substance**, but the harm is the I/O path it
  holds, not the thread it is on.
- *"a genuinely slow bluetoothd reply"* — not excluded, and T1's instrumentation settles it in one
  deploy. Note the fix below is correct under **either** answer, whereas simply raising the timeout
  is correct only under this one and makes the other strictly worse (a 30 s stall instead of 5 s).

### Fix shape

Stop mixing a blocking call with a concurrent dispatch worker on a shared connection. Split
`startDbusDispatchLoop()` into "install the object path + filter" and "spawn the worker", and pump
the pending call **on the calling thread**:

```
installAdvertisementObject()        # register_object_path + add_filter, no thread
sendRegisterAdvertisement()         # dbus_connection_send_with_reply -> DBusPendingCall
  loop until completed or deadline:
      dbus_connection_read_write(conn, 50)   # one thread: reads, writes AND dispatches
      while (dispatch(conn) == DATA_REMAINS) {}
  dbus_pending_call_steal_reply()
startDbusDispatchLoop()             # only now spawn the long-lived worker (Release, re-reads)
```

One thread owns the socket for the whole handshake, so our `GetAll` reply is written the moment it
is queued.

---

## Tasks

Ordered by value per effort. T1–T4 are Kotlin-only and need no native rebuild. T5–T6 are the gattlib
change. T7 is a standalone experiment. **T7 could be dropped** with no loss (see its note); T6 could
be deferred if a new fork repo is unwelcome today.

### T1 — Make advertisement registration succeed on the first try

**Files:** `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZAdvertisingService.kt`

**Approach**

1. Add `dbus_connection_flush(dbusConnection)` after each `dbus_connection_send(...)` in
   `handleAdvertisementMethod` — `:348` (GetAll), `:383` (Get), `:409` (Introspect), `:417`
   (Release). Correct regardless of which hypothesis holds, and matches the rest of the file.
2. Add a one-line timestamped log at the top of `handleAdvertisementMethod` recording `iface`,
   `member` and whether a reply was sent. This is the discriminator: if `GetAll` is dispatched early
   in the 5 s window, the stall is ours; if it arrives only at `t≈5 s`, bluetoothd is genuinely slow
   and only the timeout in step 4 matters. Keep the log — it is cheap and this path is silent today.
3. Split `startDbusDispatchLoop()` (`:232-291`) into:
   - `installAdvertisementObject(): Boolean` — the `ensureAdvVTableInitialized()` /
     `dbus_connection_register_object_path` (`:241`) / `dbus_connection_add_filter` (`:255`) block,
     including the existing rollback on failure and the `objectPathConnection` /
     `filterConnection` bookkeeping.
   - `startDbusDispatchLoop()` — only the `Worker.start` block (`:271-290`).
   `stopDbusDispatchLoop()` (`:293-312`) already tears both down independently and needs no change.
4. Rewrite `registerAdvertisement()` (`:139-227`): build the message as today, then replace
   `dbus_connection_send_with_reply_and_block` (`:191`) with
   `dbus_connection_send_with_reply(connection, message, pendingVar.ptr, 30_000)` followed by an
   inline pump loop on the calling thread (`dbus_connection_read_write(conn, 50)` +
   drain `dbus_connection_dispatch`) until `dbus_pending_call_get_completed(pending) != 0u` or a
   30 s monotonic deadline. Then `dbus_pending_call_steal_reply(pending)`,
   `dbus_set_error_from_message`, `dbus_pending_call_unref`, `dbus_message_unref(reply)`.
   Keep the existing `AlreadyExists` branch (`:204-209`). On a real error, keep the fallback.
5. Reorder `startAdvertising()` (`:55-82`): `initDbusConnection()` →
   `installAdvertisementObject()` → `registerAdvertisement()` → `startDbusDispatchLoop()`. On
   registration failure, tear the object path and filter back down before falling back.
6. Downgrade the two misleading `logError` lines at `:211-212` ("requires the advertisement object
   to be exported on D-Bus first" — it already is; "bluetoothctl-based approach" — there is none) to
   one accurate `logWarn`.

**Verification** — one deploy (see "Deploying"), then:

```bash
ssh -n -o BatchMode=yes -o ConnectTimeout=90 sterling@192.168.4.58 \
  'journalctl -u bitchat.service -b --no-pager -o short-precise | grep BLUEZ_ADV'
```

Expected: `Registering BLE advertisement...` and `Advertisement registered` **within ~200 ms** of
each other; a `GetAll` handler line between them; **no** `RegisterAdvertisement failed`, **no**
`Falling back`, **no** `Legacy advertising enabled`.

```bash
ssh -n -o BatchMode=yes -o ConnectTimeout=90 sterling@192.168.4.58 \
  'busctl get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances'
```

Expected: `y 1` (unchanged — it was already 1; the point is that we now *know* it, and reached it in
milliseconds without the fallback).

**Commit**

```
Register the BLE advertisement without racing our own dispatch loop

RegisterAdvertisement is the one BlueZ call that calls back into us before
it replies: bluetoothd reads LEAdvertisement1 properties over GetAll. We
answered that GetAll with an unflushed dbus_connection_send while the
calling thread sat in send_with_reply_and_block holding the connection's
I/O path, so the reply could not be written until our own 5 s timeout
expired. BlueZ registered the advertisement anyway once the reply finally
went out, so every start logged a failure, fell back to plain
discoverability, and left the real instance registered behind our back.

Own the socket from one thread for the duration of the handshake: install
the object path and filter, pump the pending call inline, and only then
start the long-lived dispatch worker. Flush every reply we send.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

### T2 — Unregister the advertisement, and stop leaking `Discoverable`

**Files:** `BlueZAdvertisingService.kt`; `apps/embedded/src/linuxArm64Main/kotlin/...` (the `main`
entry named by `apps/embedded/build.gradle.kts:57`, `com.bitchat.embedded.main`)

**Approach**

1. `tryLegacyAdvertising()` (`:431`) returns `true` but never sets `isRegistered`, so
   `unregisterAdvertisement()` (`:552`) hits `if (!isRegistered) return` at `:553` and neither the
   `UnregisterAdvertisement` call nor `disableDiscoverable()` (`:583`) ever runs. Replace the single
   `isRegistered` flag with two: `registeredWithManager` (set at `:206` and `:223`) and
   `usingLegacyDiscoverable` (set in `tryLegacyAdvertising`). `unregisterAdvertisement()` sends
   `UnregisterAdvertisement` only for the first and calls `disableDiscoverable()` only for the
   second. This is the actual in-process leak (E6): a stop/start cycle today leaves the instance
   registered and the adapter discoverable.
2. In `tryLegacyAdvertising()`, set `DiscoverableTimeout = 0u` (a `UINT32` variant on
   `org.bluez.Adapter1`) **before** setting `Discoverable = true`, so the fallback cannot silently
   expire on a device whose persisted timeout is not 0 (E5 — this device happens to read 0 today).
   Reuse the `Properties.Set` shape already in `setAdapterAlias` (`:508-550`).
3. Install a `SIGTERM`/`SIGINT` handler in the embedded `main` that calls
   `BluetoothMeshService.stopServices()` (`BluetoothMeshService.kt:470`) and waits briefly for it.
   There is none today (E9), so `systemctl stop` kills the process mid-flight. BlueZ does free the
   instance when our bus connection drops, so this is about a clean stop and about not leaving
   `Discoverable=true` on the adapter, not about unbounded growth.

**Verification**

```bash
# after the deploy, with the service running
ssh -n ... 'busctl get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances'   # y 1
ssh -n ... 'busctl get-property org.bluez /org/bluez/hci0 org.bluez.Adapter1 DiscoverableTimeout'            # u 0
```

Then, **only with the owner's agreement** since it interrupts them, one stop/start cycle:

```bash
ssh -n ... 'sudo systemctl stop bitchat.service'
ssh -n ... 'busctl get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances'   # y 0
ssh -n ... 'sudo systemctl start bitchat.service'
ssh -n ... 'busctl get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances'   # y 1, not 2
```

**Commit**

```
Give back the advertising instance we took

The legacy fallback returned success without setting isRegistered, so
unregisterAdvertisement() returned early and neither UnregisterAdvertisement
nor the Discoverable reset ever ran: a stop/start cycle left the instance
registered and the adapter discoverable. Track the two registration paths
separately and undo each one. Pin DiscoverableTimeout to 0 whenever the
fallback runs so it cannot expire, and stop the mesh service on SIGTERM
so systemctl stop is a clean shutdown rather than a kill.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

### T3 — Free gattlib's discovery arrays

**Files:** `data/remote/transport/bluetooth/src/linuxMain/kotlin/com/bitchat/bluetooth/service/BlueZGattClientService.kt`

Pure Kotlin — this does **not** need the gattlib rebuild, despite being bundled with T5 in the
brief.

`gattlib_discover_primary` (`gattlib.c:559`) and `gattlib_discover_char_range` (`gattlib.c:987`)
both `calloc` an array and hand ownership to the caller. There are exactly two call sites and
neither frees:

- `BlueZGattClientService.kt:415` — `servicesPtr.value`
- `BlueZGattClientService.kt:473` — `charsPtr.value`

**Approach**

Wrap each `memScoped` body in `try { … } finally { platform.posix.free(ptr.value) }`. `memScoped` is
inline, so the `return`s on the `abandon(...)` paths (`:417`, `:423`, `:428`, `:439`, `:445`, `:449`,
`:481`, `:486`, `:490`, `:508`, `:512`) still run the `finally`.

**Two ordering hazards that must not be got wrong:**

- `bitchatService` (`:432`) is a `CStructVar` pointing **into** the services array, and
  `discoverCharacteristics(entry, bitchatService, count, epoch)` (`:455`) is called with it. Copy
  `attr_handle_start` and `attr_handle_end` into Kotlin `Int`s **before** the free, and change
  `discoverCharacteristics` to take those two values instead of the struct — otherwise the free
  becomes a use-after-free.
- `bitchatCharacteristic` (`:497`) is a `uuid_t` pointing into the characteristics array and is
  passed to `enableNotifications` (`:515`). Keeping the free in the outer `finally` (rather than
  freeing eagerly after the scan loop) covers this correctly.

**Verification**

```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
```

Expected: `BUILD SUCCESSFUL`. Then after the deploy, over a session with several peer connects:

```bash
ssh -n ... 'systemctl show bitchat.service -p MainPID' 
ssh -n ... 'grep VmRSS /proc/<pid>/status'   # sample twice, ~30 min apart
```

Expected: `VmRSS` flat across the samples rather than creeping with connect count. The per-connect
figure is small (tens of bytes per discovered service/characteristic), so this is a
"does-not-grow" check, not a dramatic one.

**Commit**

```
Free the arrays gattlib hands us

gattlib_discover_primary and gattlib_discover_char_range calloc their
out-params and give the caller ownership. Both call sites dropped them.
Address rotation makes peers reconnect often, so this accumulated.

The service struct and the characteristic uuid both point into those
arrays, so copy the handle range out before freeing and keep the
characteristic array alive until notifications are subscribed.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

### T4 — Checkpoint: build and review T1–T3 before touching the submodule

```bash
scripts/verify.sh embedded
```

Expected: `PASS`. Nothing is deployed yet — T1–T3 and T5–T6 ship together in a single deploy (T8).

### T5 — NULL-guard gattlib's D-Bus property getters

**File:** `data/remote/transport/bluetooth/native/gattlib/dbus/gattlib.c`

The compiled branch is the `BLUEZ_VERSION >= 5.38` one — device runs BlueZ 5.84 — i.e. `:506-:670`
for `gattlib_discover_primary` and `:842-:1065` for `gattlib_discover_char_range`. The other two
copies (`:414-:505`, `:674-:841`) are dead and must **not** be edited.

When a peer's BlueZ objects vanish mid-discovery, gattlib still constructs a proxy per object path,
but the property cache is empty and the generated getters return `NULL`. Three unchecked
dereferences, in the compiled branch:

| Line | Expression | Crash |
|---|---|---|
| `:646` | `gattlib_string_to_uuid(org_bluez_gatt_service1_get_uuid(service_proxy), …)` | `bt_string_to_uuid(&bt_uuid, NULL)` → `strlen(NULL)` |
| `:913` | `const gchar *const *flags = org_bluez_gatt_characteristic1_get_flags(characteristic); for (; *flags != NULL; flags++)` | `*NULL` |
| `:931` | `gattlib_string_to_uuid(org_bluez_gatt_characteristic1_get_uuid(characteristic), …)` | as `:646` |

`common/gattlib_common.c` confirms the mechanism: `gattlib_string_to_uuid` passes `str` straight to
`bt_string_to_uuid` with no NULL check.

**Approach**

Guard each: log at `GATTLIB_ERROR` with the object path, `g_object_unref` the proxy, and `continue`
— the same shape as the existing `get_service` guard at `:877-:886`. While there, that existing
guard `continue`s **without** `g_object_unref(characteristic)` (`:885`), leaking a GObject on the
exact path we now expect to hit more often; fix it in the same change.

Keep the diff minimal and upstreamable: no refactoring, no reformatting, no touching the
`< 5.38` branches, no fixing the transposed `calloc(n * size, 1)` arguments at `:559`/`:987`
(harmless, and churn works against upstreaming).

**Verification**

```bash
cd /path/to/gattlib-checkout && git diff --stat     # ~4 hunks, one file
docker run --platform linux/amd64 --rm \
  -v "$PWD/data/remote/transport/bluetooth/native:/build" \
  bitchat-linux-arm64-cross bash /build/build-gattlib-linux-arm64.sh
```

Expected: completes in **~25 s** (measured 23.4 s cold), no new warnings, and
`data/remote/transport/bluetooth/native/gattlib/build/linux-arm64/install/lib/libgattlib.a` is
rewritten. Its sha256 **must differ** from
`989e1e6903d60d3ca9ad40c6ec504aedca6a84862ab43595ee68cbb5addf6717` (the current prebuilt) — if it
matches, the edit was made in a dead branch.

Note `build-gattlib-linux-arm64.sh:43` does `rm -rf "${BUILD_DIR}"`. Copy the existing
`libgattlib.a` aside first so a failed build cannot leave the tree without a prebuilt.

**Commit** (in the gattlib fork)

```
Check the D-Bus property getters before dereferencing them

When a peer disappears mid-discovery its BlueZ objects are gone but a
proxy is still created, with an empty property cache. get_uuid() and
get_flags() then return NULL and we walk them anyway:
gattlib_string_to_uuid() passes the pointer to strlen(), and the flags
loop dereferences the array head. Skip the characteristic or service
instead, as the get_service() check above already does — and unref the
proxy on that path, which it forgot to do.
```

### T6 — Carry the gattlib patch the way this repo carries forks

**Files:** `.gitmodules`, the submodule pointer at
`data/remote/transport/bluetooth/native/gattlib`, `docs/FORKED_LIBRARIES.md`

**Which mechanism, and why.** Three options were considered:

| Option | Fit |
|---|---|
| Tracked patch file applied by `build-gattlib-linux-arm64.sh` | **No precedent in this repo.** It also breaks the invariant that the submodule SHA identifies the source: `scripts/baseline-manifest.sh:23` records `git submodule status`, so a patch applied at build time would be invisible to the baseline. |
| **Fork with a pinned commit** | **Matches convention.** `docs/FORKED_LIBRARIES.md` already documents five forks, all as `fluxxion82/<project>` on a named branch. gattlib is *already* a submodule pinned to an exact SHA (`1580056`, `labapart/gattlib`), so repointing it is the smallest possible structural change and `baseline-manifest.sh` picks it up for free. |
| Upstream to `labapart/gattlib` | Right thing to do, wrong thing to block on. Open the PR; ship the fork meanwhile. |

**Approach**

1. Fork `labapart/gattlib` → `fluxxion82/gattlib`, branch `bitchat-null-guards` off `1580056`.
2. Commit T5 there, push, and repoint the submodule: update the URL in `.gitmodules` and stage the
   new SHA. Do **not** change anything else about the submodule.
3. Add row 6 to the `docs/FORKED_LIBRARIES.md` quick-reference table — Fork Repo
   `fluxxion82/gattlib`, Branch `bitchat-null-guards`, Version N/A (native static lib), Publish
   "built by `scripts/build-native-linux-arm64.sh` step 5", Consumed Via
   `native/gattlib/build/linux-arm64/install/lib/libgattlib.a`. Add a short section noting the
   rebuild is **~25 s** and byte-reproducible, so unlike Arti it is safe to rebuild casually. That
   correction is worth writing down: `CLAUDE.md` §4's blanket "the prebuilt native archives take
   hours to rebuild" is true of Arti and libsodium, not of gattlib.
4. Open the upstream PR against `labapart/gattlib`; link it from the new section.

**Verification**

```bash
git submodule status data/remote/transport/bluetooth/native/gattlib   # new SHA, not 1580056
scripts/baseline-manifest.sh                                          # records the new SHA
scripts/verify.sh embedded                                            # PASS
```

**Commit**

```
Point gattlib at a fork with the NULL-guard fix

The property-getter guards have to live in gattlib itself, because it
holds its own mutex for the length of a discovery call and Kotlin cannot
wrap them from outside. Carry them the way this repo already carries
Compose, Koin and the LoRa firmware: a fork on a named branch, pinned by
submodule SHA so baseline-manifest.sh records it. Upstream PR linked
from FORKED_LIBRARIES.md.

Note in that doc that a gattlib rebuild is ~25 s and byte-reproducible;
the "hours to rebuild" warning in CLAUDE.md is about Arti and libsodium.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

### T7 — Test dropping `--allow-shlib-undefined` (timeboxed, 15 min)

**File:** `apps/embedded/build.gradle.kts:74`

**Prediction, and why the experiment is still worth running.** The in-code comment at `:73` ("No
GLX/X11: upstream skiko-linuxarm64 >= 0.9.47 bundles an EGL-only Skia") describes the wrong thing.
`--allow-shlib-undefined` only relaxes symbol resolution for **shared objects** on the link line.
Skiko `0.9.47` ships static archives inside `skiko.klib`; gattlib, glib, dbus, bluetooth
(`data/remote/transport/bluetooth/native/sysroot/lib/aarch64-linux-gnu/*.a`) and Arti
(`libarti_linux.a`) are **all static**. None of them can be the reason.

The shared objects that *are* on the line come from `apps/embedded/sysroot/usr/lib/aarch64-linux-gnu`,
and that sysroot is incomplete. Measured `DT_NEEDED` entries with no match in the sysroot:

- `libgbm.so.1` (linked directly via `-lgbm`) → `libwayland-server.so.0` **missing**,
  `libexpat.so.1` **missing** (`libexpat.so` is a dangling absolute symlink to a host path).
- `libEGL_mesa.so.0` → `libwayland-client.so.0`, `libX11-xcb.so.1`, `libxshmfence.so.1`,
  `libxcb-dri2/dri3/present/randr/sync/xfixes` — all missing.
- `libfontconfig.so.1` → `libexpat.so.1` missing.

So the expected result is **fail, on `libgbm`'s missing wayland/expat**, not on Skiko. Run it anyway:
it is fifteen minutes, and either outcome is worth having.

**Approach**

1. Comment out `"--allow-shlib-undefined"` at `:74`.
2. `./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain`
3. Record the exact undefined symbols.

**Outcomes**

- **Links clean** → delete the flag and its comment. The flag stops hiding real errors. Commit.
- **Fails on sysroot Mesa/Wayland/expat DSOs** (expected) → restore the flag and **replace the
  misleading comment** with what it is really covering:
  `// The extracted sysroot has no libwayland-*, libexpat.so.1 or libxcb-* DSOs, so libgbm/libEGL_mesa`
  `// have unresolvable DT_NEEDED entries. Nothing to do with Skiko, which ships static archives.`
  Record the symbol list in the commit body. **This is the likely outcome and is a real deliverable:
  the next person does not repeat the experiment.**
- **Fails on something else** → that is a genuine latent error the flag was hiding. Stop, do not
  paper over it, report it and open a follow-up.

**Commit** (expected variant)

```
Say what --allow-shlib-undefined is actually covering

Dropping the flag fails the link, but not for the reason the comment
claimed. Skiko 0.9.47 ships static archives inside the klib, as do
gattlib and Arti, and --allow-shlib-undefined does not apply to static
archives at all. What it covers is the extracted sysroot: libgbm.so.1
and libEGL_mesa.so.0 have DT_NEEDED entries for libwayland-*,
libexpat.so.1 and libxcb-* that the sysroot does not contain.

Keep the flag, and record the symbols so nobody runs this experiment
a third time.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
```

**This is the task to drop** if time is short. It changes no behaviour in the expected case.

### T8 — The single deploy

All of T1–T3 and T5–T6 land in one binary.

```bash
scripts/verify.sh embedded          # PASS before anything is shipped
scripts/deploy-pi.sh                # builds, ships, verifies, restarts
```

**The owner uses this device daily.** Announce before running, deploy once, and do not re-deploy to
iterate — collect all the evidence from the one restart. If a fix turns out to be wrong, roll back
to the previous release directory (T-Risks) rather than shipping a second build the same day.

Post-deploy evidence to collect in one pass:

```bash
ssh -n -o BatchMode=yes -o ConnectTimeout=90 sterling@192.168.4.58 \
  'journalctl -u bitchat.service -b --no-pager -o short-precise | grep -E "BLUEZ_ADV|SIGSEGV|Segmentation";
   busctl get-property org.bluez /org/bluez/hci0 org.bluez.LEAdvertisingManager1 ActiveInstances;
   busctl get-property org.bluez /org/bluez/hci0 org.bluez.Adapter1 DiscoverableTimeout;
   systemctl show bitchat.service -p NRestarts'
```

Expected: registration logged as succeeding in ~100 ms, `y 1`, `u 0`, `NRestarts=0`.

---

## Review checkpoints

- After **T4**: T1–T3 compile and `scripts/verify.sh embedded` passes. Review the
  `registerAdvertisement` rewrite carefully — the pump loop is the one piece of genuinely new
  concurrency logic.
- After **T6**: submodule repointed, `verify.sh embedded` still passes, fork pushed and PR opened.
- After **T8**: read the `BLUEZ_ADV` log block before declaring T1 fixed. If the new `GetAll` log
  line shows the callback arriving at `t≈5 s` rather than early, the root cause was the slow-daemon
  variant instead; the fix still works, but say so rather than claiming the diagnosis was confirmed.

## Risks and rollback

| Risk | Mitigation |
|---|---|
| The registration pump loop spins without a deadline and hangs startup | Hard 30 s monotonic deadline plus the existing fallback. `dbus_connection_read_write(conn, 50)` blocks in `poll`, so the loop does not busy-spin. |
| Draining `dispatch()` on the calling thread runs GATT-server or scanning handlers re-entrantly on a coroutine thread | The shared connection already has handlers on two worker threads; this adds a third caller for a bounded window. Review that `handleAdvertisementMethod` and the GATT server's handler are re-entrancy-safe before merging. If uncertain, narrow the pump loop to `read_write` plus a single `dispatch` per iteration. |
| Freeing the discovery arrays introduces a use-after-free | The two hazards are named explicitly in T3. This is the highest-risk small change in the plan; review it against `:432` and `:497` specifically. |
| The gattlib rebuild overwrites the prebuilt with a broken archive | `build-gattlib-linux-arm64.sh:43` deletes the build dir first — copy `libgattlib.a` aside before running. The build is 23 s and byte-reproducible, so recovery is trivial. |
| The submodule repoint breaks a clean clone | `git submodule update --init --recursive` against the fork; verify on a fresh clone before merging. |
| The deploy regresses the owner's daily use | `scripts/deploy-pi.sh` keeps every release under `/opt/bitchat/releases/<sha12>-<build>-<digest8>/` with `current` as an atomically swapped symlink. Rollback is repointing `current` at the previous release directory and restarting — no rebuild. Note the previous release identity **before** deploying. |

## What this plan is NOT doing

- Not restarting `bitchat.service` during planning. All device inspection was read-only.
- Not touching the upstream protocol drift (SHA-256 peer IDs, Ed25519 signatures, header flags) from
  `../docs/plans/2026-09-06-reentry-plan.md` §2. Unrelated.
- Not rewriting `BlueZAdvertisingService` onto GLib's main loop, and not consolidating the two
  `dbus_bus_get` dispatch workers onto one owner. Both are the right long-term shape and both are
  much larger than this. Recorded as a follow-up.
- Not fixing the dead `setOnPacketReceivedCallback` wiring in the Android/iOS `BleModule`s
  (`CLAUDE.md` §8) — different platforms, unrelated.
- Not addressing the ~70 stale `dev_*` objects accumulating in BlueZ's object tree from address
  rotation. Real, separate, and needs its own investigation.
- Not addressing the `le-connection-abort-by-local` connect errors seen in the log — those are the
  central-role policy work already landed in `11ded25` and need observation, not a change.
- Not upgrading gattlib past `1580056`, not upgrading Arti, not touching `../forks`, `~/.m2`, other
  submodules, or any other `native/` prebuilt.
- Not running `./gradlew clean`.
- Not fixing the transposed `calloc` arguments in gattlib (`:559`, `:987`) — harmless, and churn
  hurts the upstream PR.
