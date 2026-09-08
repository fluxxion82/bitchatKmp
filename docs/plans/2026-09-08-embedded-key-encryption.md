# Embedded (linuxArm64) At-Rest Encryption for Secret Preferences

> **Revision 3, 2026-09-08.** Revision 1 and revision 2 were each reviewed adversarially and each
> returned **needs rework**, for a combined eight blockers. Every one of the eight was the same
> hazard wearing a different hat: *the application decides it is on a new device and mints a fresh
> Nostr identity while a recoverable identity is still on disk.* Revision 3 does not patch a ninth
> path. It states that hazard as an invariant, enforces the invariant in one place, ships that place
> first — before any cryptography exists — and then arranges the rest of the work so that being wrong
> about it cannot cost an identity.

---

## 0. What this actually defends against

A 32-byte key file on the same unencrypted SD card as the data it protects. Stated plainly, before
anything else in this document:

**It protects against casual copying.** An `scp -r ~/.bitchat` to a laptop, a support tarball of the
app directory, an SD image handed to someone to debug a radio problem, a prefs file pasted into an
issue, another local user on the box. Those are the leaks that actually happen to appliances, and
today every one of them carries `nostr_private_key` and `signing_private_key` in cleartext.

**It does not protect against anyone holding the card.** The key is on the card. It does not protect
against a full-home tarball (which after this work sweeps up `~/.config/bitchat` as well), against
code running as `sterling`, or against root. It is not equivalent to the desktop build's OS keyring,
and it is not full-disk encryption.

Nothing later in this document may claim more than that paragraph. Where the code and the docs
describe the guarantee, they say "resists copying and other local users, not someone holding the
card".

One more honesty point, new in revision 3 and load-bearing for the whole task order below: **this
plan does not, on its own, remove the plaintext from the card.** It writes the sealed store beside
the plaintext, makes the sealed store authoritative, and leaves the plaintext in place as recovery
material. Removing it is a separate, later, deliberate operator step (section 13). Until that step
runs, `grep -r nostr_private_key` still finds the key. That is a deliberate trade and section 7.3
argues it.

---

## 1. What the two review rounds found, and why this revision is shaped differently

Read this section before the tasks; the structure below is a consequence of it, not a preference.

**Round one, four blockers.** (1) A pre-write rotation that renamed the live sealed file aside before
writing its replacement, leaving a window in which the file did not exist — and an absent file was
read as a first run. (2) A master key created by `rename`, which silently replaces, stranding every
store sealed under the key it destroyed. (3) A migration whose "verification" decrypted and compared
the file it had just written, thereby authenticating input that was already corrupt. (4) Contradictory
rules about what to do on a downgrade, and a documented backup command that omitted the key.

**Round two, four more.** (5) A plaintext store truncated at a record boundary reads cleanly —
`FlatFileFormat` only reports damage when the file does not end in a newline — so a truncation that
drops the Nostr records while keeping the mesh records passes every check, and the store then looks
like "populated, but no Nostr key", which is exactly the state a genuine first run passes through.
(6) A retained `.plaintext.bak` present while the main file is absent was read as a first run, and a
migration would silently overwrite that backup. (7) A key-durability race: the process that loses the
`O_EXCL` create can read the winner's key and seal data under it before the winner's key file, or the
directory entry naming it, is durable — so a power cut leaves durable ciphertext and no key. (8) The
failure-cleanup promise ("the device is left byte-identical") was not true, because the master key
and earlier stores may already have been created.

**The single shared root.** Findings 1, 3, 5 and 6 are literally the same bug: *absence of evidence
was treated as evidence of a new device.* Findings 2 and 7 are the same bug pointed at the key
instead of the store: *a key that may not be durable, or may have been replaced, was treated as the
key.* Findings 4 and 8 are the documentation of those two.

**So the structure changed in four ways.**

- The invariant (section 3) is written down as one rule, implemented as one pure function, and
  covered by host tests **before any encryption code exists** (T1, T2). Those two tasks are worth
  shipping even if the rest of this plan is abandoned.
- Migration is **non-destructive**: nothing is renamed, moved or deleted (section 7.3). The class of
  bugs represented by findings 1, 3 and 6 has nowhere to live.
- Migration is **operator-run, not automatic at startup** (section 7.4), so the interruption windows
  that findings 1 and 7 depend on only exist while a human is watching a terminal.
- The key's durability is a **barrier taken before use**, not a property of whoever created it
  (section 7.5), which makes finding 7 unreachable without any cross-process coordination.

---

## 2. Goal

`EncryptionSettingsFactory.createEncrypted(name)` genuinely encrypts on `linuxArm64`, so the Orange
Pi stops holding `nostr_private_key`, `nostr_device_seed`, `signing_private_key` and
`static_private_key` as cleartext `key=value` text.

And, ranked above that goal because it is the one that cannot be undone: **no step of this work, and
no interruption of any step of this work, can cause the device to mint a new identity while a
recoverable one exists.**

---

## 3. The invariant

> **Minting identity material requires positive proof of a genuine first run. The absence of one file
> is never such proof.**

Positive proof means all of the following, established by looking, not by failing to find:

1. Every directory in the *identity domain* was listed successfully — it either does not exist
   (`ENOENT`) or was read to completion. A directory that exists and cannot be listed is not evidence
   of anything.
2. No artifact of any class was found in any of them: no sealed store, no plaintext store, no
   retained plaintext backup, no orphaned temp file from an interrupted write, no master key, no
   identity ledger, and no other file this application is known to create.
3. The store being read reported no damage and is empty.
4. The identity ledger holds no claim for the component about to be minted.

If any of 1–3 fails, or if 4 fails, the correct outcome is **refuse and report**, never mint.

The identity domain is exactly three directories:

| Directory | Holds |
|---|---|
| `$HOME/.bitchat/prefs` | `*.prefs`, `*.prefs.enc`, `*.prefs.plaintext.bak`, `*.prefs*.tmp*` |
| `${XDG_CONFIG_HOME:-$HOME/.config}/bitchat` | `master.key`, `identity-ledger` |
| `$HOME/.bitchat/settings` | the nine non-secret stores — no secrets, but their existence proves the app has run here before |

**Three properties this must have, and they are what the task ordering below is built to deliver:**

- **Enforced in exactly one place.** One function, `IdentityCustodian.loadOrMint`, is the only code in
  the tree that may create identity material. Every mint site calls it. The property is checkable by
  `grep`: no other caller of `NostrIdentity.generate()`, `Cryptography.generateEd25519KeyPair()` or
  `Random.nextBytes` into an identity slot.
- **Independently testable without any crypto.** The scan classification, the ledger format and the
  gate are pure functions over data classes. They are covered by JVM tests in a module that already
  has a JVM test source set. No libsodium, no cinterop, no device.
- **Holds regardless of which later step was interrupted.** The invariant is evaluated from what is on
  disk at the moment a mint is proposed. It does not care whether a migration finished, whether a
  sealed file is half-written, or whether the previous boot crashed — a half-written sealed file is an
  artifact, and an artifact defeats "virgin", full stop.

### 3.1 The case `e4f6a5c` currently misses, and how this closes it

`e4f6a5c` landed `PreferenceStoreState` and the rule that an `UNREADABLE` store refuses to mint. Its
`POPULATED` case deliberately still mints, and its reasoning is correct as far as it goes:
`bluetoothModule` writes a signing key into `bitchat_identity` during Koin graph construction, before
Nostr is ever asked for its key, so *every genuine first run passes through* "other keys present, no
Nostr key".

The gap: a store truncated at a record boundary presents identically. `FlatFileFormat.decode` reports
damage only when the file does not end in `\n`, so a truncation that lands exactly on a record
boundary and removes the trailing `nostr_private_key` and `nostr_device_seed` records — while keeping
the earlier `static_*` and `signing_*` records — reads clean, reports `POPULATED`, and mints. Correct
for a first run, catastrophic after truncation, and the two are indistinguishable *from the store's
contents alone*.

**The discriminator is a claim written outside the store: the identity ledger.**

`${XDG_CONFIG_HOME:-$HOME/.config}/bitchat/identity-ledger`, plain `key=value` text in the same
format `FlatFileFormat` already parses (so damage detection is free and already tested), holding only
**public, non-secret** values:

```
version=1
epoch=<32 hex, random, written once when the ledger is created>
claim.mesh_static=<x25519 public key, hex>
claim.mesh_signing=<ed25519 public key, hex>
claim.nostr=<x-only public key, hex — the npub's payload>
claim.nostr_seed=present
```

`claim.nostr_seed` is the literal string `present` because the device seed has no public form, and
writing a digest of it would create a secret-adjacent artifact for no gain. Every other claim value
is a public key that is already broadcast on the mesh or the relays.

The ledger is plaintext and stays plaintext after the stores are sealed. That is deliberate: its job
is to be readable when the sealed store is not, and to be greppable by an operator holding only a
recovered card.

**The rule the ledger buys:**

| Ledger | Claim for this component | Store state | Decision |
|---|---|---|---|
| absent, domain virgin | — | `FIRST_RUN` | **Mint.** Create the ledger, record the claim. |
| absent, domain virgin | — | `POPULATED` | **Mint.** A virgin domain cannot be populated; this row is unreachable and is asserted as such. |
| absent, domain **not** virgin | — | any | **Refuse.** A device that predates the ledger. One operator command (`--identity-adopt`, T3) writes the ledger from what is present, with the operator's recorded npub as the anchor. After that the ordinary rows apply. |
| present, damaged | — | any | **Refuse.** A damaged ledger is never read as "no claims". |
| present, clean | **present** | key missing from store | **Refuse.** *This is the truncation case.* The device has minted this component before; its private key must be recovered, not replaced. |
| present, clean | absent | `FIRST_RUN` or `POPULATED` | **Mint.** This component has genuinely never existed here, whatever else the store holds. |
| present, clean | absent | `UNREADABLE` | **Refuse.** The `e4f6a5c` rule, kept. |

Note what this does to the awkward `POPULATED` case: it stops carrying any weight. The question is no
longer "does the store hold *other* keys" — which cannot distinguish the two situations — but "has
*this component* ever existed here", which the ledger answers directly.

**Claim write ordering, and why it is this way round.** The claim is recorded **after** the key is
saved, never before. The two failure directions are not symmetric:

- Claim first, key-save fails → a claim with no key → permanent refusal to mint, on a device that has
  no identity. That is a brick.
- Key first, claim-write fails → key present, no claim → the next start finds the key, does not
  attempt to mint, and records the missing claim on the way past. Self-healing.

So claims are recorded both after a successful mint *and* after a successful load of a component that
has no claim yet. Recording is idempotent.

**The residual window, stated rather than hidden.** A key saved, a claim not yet written, a power cut,
*and* a truncation that removes that key: two failures inside one write window. The invariant does not
cover it. Sealing (T8) closes the truncation half of it permanently, because a truncated AEAD envelope
fails authentication instead of reading clean.

### 3.2 The fallback on platforms that are not the Pi

Android, Apple and JVM desktop have no directory domain to scan — their stores are the Keychain and
`EncryptedSharedPreferences`, which surface a read failure as an exception rather than as a
half-loaded map. They get a `DomainInspector` that reports `NotApplicable` and a `LedgerStore` that
reports `Unavailable`, and in that configuration `IdentityMintGate` reproduces
`NostrIdentityMintPolicy`'s current three-way decision **exactly**. A test asserts that equivalence
against the existing `NostrIdentityMintPolicyTest` cases, so this plan changes no behaviour on any
platform but `linuxArm64`.

---

## 4. What already landed, and is therefore not in this plan

Commit `e4f6a5c` ("Make the embedded preference store durable, private and honest") rewrote
`data/local/platform/src/linuxMain/kotlin/com/bitchat/local/prefs/Encryption.linux.kt` and moved the
pure parts to `commonMain`. **Read that commit before starting.** It already provides:

| Already done in `e4f6a5c` | Where |
|---|---|
| Whole-file reads, decoded once (the 4096-byte `fgets` truncation is gone) | `LinuxFileSettings.readWholeFile`, `Encryption.linux.kt:153-185` |
| Durable atomic write: `mkstemp` -> `fchmod 0600` -> full write -> `fsync(fd)` -> `close` -> `rename` -> `fsync(dir)` | `LinuxFileSettings.saveToFile`, `Encryption.linux.kt:187-255` |
| **The live file is never moved aside before a write** | same, and the KDoc at `:106-123` says why |
| A failed `fopen` is reported, not swallowed; `PreferenceStoreIOException` carries the path and `strerror` | `Encryption.linux.kt:43-57, 153-158` |
| Directories created and then explicitly `chmod 0700` | `ensureDirectory`, `Encryption.linux.kt:67-72`; `LocalModule.linux.kt:42-49` |
| Format and store-state logic in `commonMain`, host-tested | `FlatFileFormat.kt`, `PreferenceStoreState.kt`, 25 tests in `data/local/platform/src/jvmTest/` |
| An unreadable store refuses to mint a replacement Nostr identity | `NostrIdentityMintPolicy.kt`, `NostrClient.kt:434-444, 538-545` |

Revision 1's Task 1 (0600/0700 modes), its `writeDurably` helper and its "latent bugs in
`LinuxFileSettings`" section are **all superseded**. Do not reimplement them. The durable-write body
this plan needs already exists and is only *extracted*, not written.

This plan **extends** `e4f6a5c`'s mint policy rather than replacing it with a parallel mechanism:
`PreferenceStoreState` and `IdentityStoreState` keep their meaning and their tests, and become one of
the three inputs to `IdentityMintGate` (section 3.1). `NostrIdentityMintPolicy` is reduced to a
delegation so that its call sites do not change shape.

Two things `e4f6a5c` deliberately did not do, and this plan must: the process `umask` is still not
set, and the files are still plaintext.

---

## 5. Architecture

Three layers, and the first is complete on its own.

**Layer 1 — the invariant (no crypto, host-tested, T1–T3).**

- `:data:local:platform`, `commonMain`: `IdentityComponent`, `DomainArtifact` / `DomainScan` and its
  pure classification, `DomainVerdict`, `IdentityLedger` (parse/encode over `FlatFileFormat`),
  `LedgerClaims`, and `IdentityMintGate`. Plus `IdentityCustodian`, the single choke point, and the
  `DomainInspector` / `LedgerStore` interfaces it depends on.
- `:data:local:platform`, `linuxMain`: `LinuxDomainInspector` (`opendir`/`readdir`, classifying by
  name) and `LinuxLedgerStore` (read through `PosixFiles`, write through the durable-write path).
- `:data:local:platform`, other source sets: `NoDomainInspector` / `NoLedgerStore`, both reporting
  "not applicable", so behaviour on Android, Apple and desktop is bit-for-bit what it is today (3.2).
- `:data:remote:transport`, `commonMain`: `IdentityComponent` is referenced by `TransportIdentityProvider`,
  which gains `loadOrMint`. This module is a leaf with no project dependencies, and both `nostr` and
  `data:local:platform` already depend on it.
- `apps/embedded`: `umask(0o077)` first in `main`; `--identity-report` (read-only) and
  `--identity-adopt` (writes the ledger and nothing else).

**Layer 2 — the envelope and its prerequisites (T4–T8).**

- `:data:local:platform`, `linuxMain`: a `reentrantLock` around the shared preference map and the
  factory cache (T4). It must land before any sealing exists — see 7.11.
- `:data:crypto`, `linuxMain`: `LinuxSecretBox` — XChaCha20-Poly1305-IETF with associated data, keyed
  BLAKE2b subkey derivation, `randombytes_buf` — over the libsodium cinterop that module already
  links. Plus a single shared `sodium_init` guard that `Cryptography` also uses.
- `:data:local:platform`, `commonMain`: a pure `SecretBox` interface, `SecretPrefsCodec` framing a
  `Map<String,String>` into `magic | version | kdfId | salt | nonce | ciphertext+tag`, the exception
  types, and `IdentityContentGate`. All host-tested on the JVM, because `linuxArm64` tests cannot run
  on this Mac.
- `:data:local:platform`, `linuxMain`: `PosixFiles` (open-once with `O_NOFOLLOW`, `fstat` the
  descriptor, read from that descriptor; plus the durable write extracted from `LinuxFileSettings`),
  `LinuxMasterKey` (32 bytes, `O_CREAT|O_EXCL`, durability barrier before use), `SodiumSecretBox`.
- `apps/embedded`: `--storage-selftest` (its own fresh scratch child directory; writes nothing live).

**Layer 3 — using it (T9–T10).**

- `EncryptedLinuxFileSettings`: reads whichever of the sealed or plaintext file is present, writes
  only the sealed one.
- `--migrate-storage`: an operator command. Seals each store beside its plaintext. **Renames nothing,
  deletes nothing.**
- The device: deploy, adopt, migrate, verify by executing a restore, document.

**Tech stack:** Kotlin/Native 2.2.10 linuxArm64, libsodium via the existing `:data:crypto` cinterop
(`headers = sodium.h`, so `crypto_generichash`, `crypto_scalarmult_base` and `crypto_sign_seed_keypair`
are already exposed), multiplatform-settings 1.3.0, kotlinx.serialization 1.9.0, kotlinx.atomicfu
0.29.0 (already a `commonMain` dependency of `data:local:platform`), POSIX. **No build-file change is
needed anywhere in this plan** — verify that claim at T1's compile gate and report if it is false.

---

## 6. Context the implementer needs

### 6.1 Repo and workflow

- Repo `/Users/fluxxion/Development/workspace/multiplatform/bitchat/bitchatKmp`, branch `main` at
  `8111edb`. Work on a branch, e.g. `embedded/identity-invariant`.
- Every Gradle call needs `--console=plain`. Embedded work needs `-Pembedded.enabled=true` and this
  Mac's `~/.m2` fork artifacts. **Never run `./gradlew clean`.** A relink of `:apps:embedded` takes
  minutes — use 10-minute timeouts.
- Do not touch `../forks`, `~/.m2`, submodules, or anything under `native/`.
- Commit style: short imperative summary, body explaining why, ending with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Background: `../docs/reviews/2026-09-07-embedded-plaintext-identity.md` (the finding this closes).

### 6.2 Licensing: a process instruction, not a footnote

`passman`, `passmanShared` and `passmanClient` are **AGPL-3.0**. bitchatKmp has no `LICENSE` file and
descends from a permissively licensed upstream. Copying AGPL source here would impose AGPL on the
whole repo.

**The implementer must not open, read, `grep`, or otherwise consult passman's source while writing any
part of this plan.** Section 12 describes the patterns in prose; that prose is the only permitted
input. If a detail seems missing, ask the owner — do not go and look. Each task report states, in one
line, that no passman file was opened. This is a process control: the difference between an
independently reimplemented pattern and a derivative work is whether you read the source.

### 6.3 The code as it stands (after `e4f6a5c`)

| File | What it does now |
|---|---|
| `data/local/platform/src/commonMain/.../prefs/Encryption.kt` | The whole interface: `fun createEncrypted(name: String): Settings` |
| `.../linuxMain/.../prefs/Encryption.linux.kt:85-104` | `LinuxEncryptionSettingsFactory`: `ensureDirectory(~/.bitchat)`, `ensureDirectory(~/.bitchat/prefs)`, an **unsynchronised** `settingsCache.getOrPut`, returns `LinuxFileSettings("$prefsDir/$name.prefs")` |
| `.../Encryption.linux.kt:125-331` | `LinuxFileSettings : Settings, HealthReportingSettings`. An **unsynchronised** `mutableMapOf<String,String>`; whole-file read at construction; every mutation calls `saveToFile()` |
| `.../Encryption.linux.kt:187-255` | `saveToFile`: the durable write. **This body is what T7 extracts, unchanged in behaviour.** |
| `.../Encryption.linux.kt:87-94`, `LocalModule.linux.kt:42-49` | `getenv("HOME")?.toKString() ?: "/tmp"` — the `/tmp` fallback that 7.10 addresses |
| `.../commonMain/.../prefs/FlatFileFormat.kt` | `encode`/`decode` for the legacy format, with `FlatFileContent.damage`. The ledger reuses it. |
| `.../commonMain/.../prefs/PreferenceStoreState.kt` | `FIRST_RUN` / `POPULATED` / `UNREADABLE` and the `HealthReportingSettings` interface |
| `.../commonMain/.../prefs/impl/LocalSecureIdentityPreferences.kt` | `bitchat_identity`; `static_private_key`, `static_public_key`, `signing_private_key`, `signing_public_key`; `storeState()` reads `HealthReportingSettings.storeDamage`. **Mint site (indirect).** |
| `.../commonMain/.../transport/SecureTransportIdentityProvider.kt` | Adapts `SecureIdentityPreferences` to `TransportIdentityProvider`; maps the three store states across |
| `.../commonMain/.../prefs/impl/LocalUserPreferences.kt:153-191` | `setUserState`: **four separate `settings[...] =` assignments**, i.e. four whole-file writes per screen transition |
| `data/remote/transport/src/commonMain/.../TransportIdentityProvider.kt` | `IdentityStoreState` and the provider interface. **Leaf module, no project dependencies.** |
| `data/remote/transport/nostr/.../NostrClient.kt:19-20` | `NOSTR_PRIVATE_KEY = "nostr_private_key"`, `DEVICE_SEED_KEY = "nostr_device_seed"` |
| `data/remote/transport/nostr/.../NostrClient.kt:434-446` | **Mint site 1:** consults `NostrIdentityMintPolicy`, then `NostrIdentity.generate()` |
| `data/remote/transport/nostr/.../NostrClient.kt:536-552` | **Mint site 2:** the device seed; consults the policy, then `Random.nextBytes(32)` |
| `data/remote/transport/bluetooth/.../di/bluetoothModule.kt:27-45` | **Mint site 3:** `Cryptography.generateEd25519KeyPair()` then `saveSigningKey`, with **no policy consultation at all**, during Koin graph construction. This is the first identity write on a new device and today it is unguarded. |
| `.../jvmMain/.../prefs/Encryption.jvm.kt:33-36`, `:118-121` | The two statements asserting the embedded build writes plaintext. They become false in T9. |
| `.../jvmMain/.../prefs/Encryption.jvm.kt:58-60` | `credentialStorage: Result<...> by lazy` — the memoisation pattern T9 copies |
| `data/crypto/src/linuxMain/.../Cryptography.linux.kt:73-76` | `sodiumReady`: the existing, and currently only, `sodium_init()` |
| `data/crypto/src/linuxMain/.../Cryptography.linux.kt:700-733` | `encryptXChaCha`: the `usePinned`/`memScoped`/`reinterpret<uint8_tVar>()` shape to mirror — but it passes `null, 0u` for `ad`/`adlen` |
| `data/crypto/src/nativeInterop/cinterop/libsodium.def` | `headers = sodium.h` — the whole API surface is available; no `.def` change needed |
| `apps/embedded/src/linuxArm64Main/.../Main.kt:116-122` | `main`: the `--version` early return, then `runApp()` |
| `apps/embedded/src/linuxArm64Main/.../Main.kt:142-149` | `App()` (Koin `startKoin`) at `:142`; `Drm.initialize()` at `:149`. **Storage is built before the display exists.** See 7.9. |

Module dependency facts that constrain where code may live, checked against the build files:

- `:data:remote:transport` has **no** project dependencies. `:data:remote:transport:nostr` and
  `:data:local:platform` both depend on it.
- `:data:local:platform` depends on `:data:remote:transport:nostr`, so nothing in `nostr` may depend
  on `:data:local:platform` — that would be a cycle.
- `:data:remote:transport:bluetooth` depends on `:data:local:platform` but **not** on
  `:data:remote:transport`. It therefore reaches the custodian through `SecureIdentityPreferences`,
  which is the reason T2 adds the mint entry point there rather than only on `TransportIdentityProvider`.

### 6.4 Hardware: verify, do not assume

The Orange Pi Zero 3 is an Allwinner H618 (quad Cortex-A53), per `docs/meshcore-orangepi-setup.md:10`.
Before T7, run this on the device and paste the output into the task report:

```bash
ssh sterling@<pi> 'ls -l /dev/tpm* /dev/tee* 2>&1; \
  ls -l /sys/bus/nvmem/devices/sunxi-sid0/nvmem 2>&1; \
  grep -m1 Features /proc/cpuinfo; grep -m1 Serial /proc/cpuinfo; \
  ls -l /etc/machine-id; id; umask; echo "HOME=$HOME XDG_CONFIG_HOME=${XDG_CONFIG_HOME:-<unset>}"'
ssh sterling@<pi> 'systemctl show -p Environment bitchat.service; \
  sudo -n tr "\0" "\n" < /proc/$(systemctl show -p MainPID --value bitchat.service)/environ | grep -E "^(HOME|XDG_CONFIG_HOME|USER)="'
```

Expectations, stated so a surprise is visible:

- **No TPM, no OP-TEE, no secure element.** `/dev/tpm0` and `/dev/tee0` should both be absent. If a
  TPM is present, stop and re-plan — it changes the right answer for 7.5.
- **The SID e-fuse is root-only** (`0400 root:root`). Binding to it would need a udev rule or root.
  Deferred; see 7.5.
- **`crypto_aead_aes256gcm_is_available()` returns 0 on this board.** libsodium's AES-256-GCM needs
  x86 SSSE3+AES-NI+PCLMUL; ARMv8 crypto extensions do not satisfy it. So `Cryptography.encryptAESGCM`
  on the Pi is *already* XChaCha20-Poly1305 through its private fallback, with the cipher chosen at
  runtime and recorded nowhere. That implicit branch is why this plan does not reuse `encryptAESGCM`
  for the file format.
- **`HOME` is set for the service; `XDG_CONFIG_HOME` is not.** systemd derives `HOME`, `USER`,
  `LOGNAME` and `SHELL` from the account database for a `User=` unit but sets no XDG variables, and
  `apps/embedded/systemd/bitchat.service` adds only `LANG`. So the real config path on the device is
  `/home/sterling/.config/bitchat`, via the `$HOME/.config` fallback — the fallback is the production
  path, not an edge case. **Never hardcode it anyway:** every command in T9 and T10 uses the path
  `--identity-report` prints (7.10).

---

## 7. Decisions and why

### 7.1 One enforcement point, and where it has to live

`IdentityCustodian` (`:data:local:platform`, `commonMain`) is the only code that may create identity
material:

```kotlin
class IdentityCustodian(
    private val store: SecureIdentityPreferences,   // the backing bitchat_identity store
    private val inspector: DomainInspector,          // linux: readdir; elsewhere: NotApplicable
    private val ledger: LedgerStore,                 // linux: the file; elsewhere: Unavailable
) {
    /**
     * Returns the existing value for [component], or mints one when — and only when — the
     * invariant of section 3 is satisfied. Throws IdentityRefusedException otherwise.
     */
    fun loadOrMint(component: IdentityComponent, mint: () -> MintedValue): String
    fun load(component: IdentityComponent): String?
    fun verdict(): DomainVerdict
}
```

The domain scan is taken **once**, lazily, at the first call, and cached for the process lifetime.
That matters: the application's own first write makes the domain non-virgin, so a scan taken per call
would refuse the second component on a genuine first run.

Call sites, all of which change to route through it:

- `NostrClient:434-446` and `:536-552` — through `TransportIdentityProvider.loadOrMint`, which
  `SecureTransportIdentityProvider` implements by delegating to the custodian.
- `bluetoothModule:27-45` — through a new `SecureIdentityPreferences.loadOrMintSigningKey`, which
  delegates to the custodian. This is the mint site with no guard at all today, and it is the *first*
  identity write on a new device.

`bluetooth` cannot see `:data:remote:transport` (6.3), which is why the entry point is added to
`SecureIdentityPreferences` as well as to `TransportIdentityProvider`. Both are thin delegations to
the same custodian instance; Koin registers exactly one.

**How the "exactly one place" claim is checked, and kept:** T2's report includes the output of

```bash
grep -rn "NostrIdentity.generate()\|generateEd25519KeyPair()\|generateKeyPair()" --include="*.kt" . | grep -v "/build/"
```

with a line for each hit saying either "inside a `loadOrMint` lambda" or "not identity material"
(the ephemeral wrap key at `NostrClient:319` is the latter).

**The gate is consulted at mint time, not at startup.** A device whose identity is intact never
reaches it, because nothing asks to mint. So enforcing the invariant does not make an un-adopted
device refuse to boot: it makes a device that has *already lost* a key refuse to paper over the loss.
That is what lets T2 deploy on its own, ahead of the ledger adoption in T3.

### 7.2 The truncation discriminator

Section 3.1 is the decision; this is the note on the two alternatives that were considered and
rejected.

- **A length or record-count footer in the flat file.** Would catch a boundary truncation directly,
  but cannot be retrofitted onto the files already on the device, which is the case that matters —
  and the format is deliberately frozen (`FlatFileFormat`'s KDoc).
- **Cross-checking `static_public_key` against `static_private_key`.** Proves internal consistency of
  the records that survived; says nothing about records that did not. It is worth doing anyway, and it
  is in the T6 content gate — but as a check on migration input, not as a first-run discriminator.

The ledger is the only mechanism that answers "has this component ever existed here" without
depending on the file that might have lost it.

### 7.3 Migration is non-destructive: nothing is renamed, deleted or moved

**Recommendation: never delete or rename the plaintext file in this plan.** Write the sealed store
alongside it, treat the sealed store as authoritative once it verifies, and leave the plaintext
untouched as recovery material until a later, separate, deliberate cleanup step the operator runs when
satisfied.

*For:*

- It deletes an entire class of bug rather than fixing instances of it. Findings 1, 3 and 6 were all
  "the file moved, or was about to, and then something went wrong". With no rename there is no window
  in which the identity exists under a name nothing is looking for.
- **The cross-check stays available forever.** Because the plaintext is still there, the content gate
  of section 7.6 can be re-run at any time, by `--storage-verify`, months later, against the original
  input — not only during the one migration run. Revision 2's gate was a one-shot: it ran once, during
  the riskiest minute of the device's life, and if it was wrong nothing could tell you afterwards.
- **The plaintext stays a valid identity recovery source indefinitely**, despite going stale. Identity
  keys are write-once: once `nostr_private_key` exists it never changes. What goes stale in a frozen
  plaintext copy is the nickname, the favourites map and the joined channels — annoying to lose, not
  irreplaceable. So the recovery material does not decay in the dimension that matters.
- The threat model (section 0) already concedes no protection against physical possession of the card,
  so the marginal exposure of leaving the file for a few more weeks is small on that axis.

*Against, honestly:*

- **The `grep -r nostr_private_key` one-liner still works** until the retirement step runs. That was
  the headline benefit of the whole change, and this defers it. This is a real cost, not a rounding
  error, and it is why section 0 says so in its own paragraph and why the retirement step is written
  down as a named follow-up with its own procedure (section 13) rather than left implicit.
- Two copies of the same secrets means two things to get the permissions right on. Mitigated: the
  plaintext already exists with whatever mode it has, T3's `umask` and the durable write make every
  file this process writes 0600, and the retention is bounded by the operator's own cleanup.
- A rollback to a pre-encryption binary silently reads the frozen plaintext and diverges. Covered by
  the risks table: this is the same "no merge" hazard revision 2 had, except that with no rename the
  operator is not additionally required to reconstruct a file before rolling back.

**Net:** the safety gain is structural and the cost is a deferral of a benefit, on a device whose
threat model already tolerates the exposure. Take the non-destructive path.

Consequences that fall out of it, all of them simplifications:

- The `E present, P present` state is **normal**, not an error. Revision 2's "refuse to start when
  both exist" rule is deleted, along with the black screen it caused on a state the device would
  reach in the ordinary course of events.
- No task in this plan ever creates a `.plaintext.bak`. The plaintext keeps its own name. The
  domain scan still recognises that name as an artifact (section 3), because a revision-2 attempt or
  a hand-restore could have left one and its presence must count as evidence.
- Migration has no step 6. It ends when the sealed file verifies.

### 7.4 Migration is an operator command, not an automatic startup step

**Recommendation: do not migrate automatically at startup.** `--migrate-storage` is an explicit
command, run over ssh, with the service stopped.

*For:*

- Automatic migration on a headless box that boots unattended is precisely what makes an interruption
  window matter. Run by hand, the window exists once, for a second, while a human watches the output.
- The npub anchor (7.6) is an operator-supplied cross-check. As an argument to a command it is
  natural; revision 2 had to smuggle it in through `systemctl set-environment` for exactly one boot
  and then remember to unset it, which is a procedure nobody would get right twice.
- The box is trivially ssh-able and this happens once in its life.
- A failure becomes a non-zero exit and a paragraph on a terminal the operator is already reading,
  instead of a black screen and a journal hunt (7.9).
- It composes with 7.3: with no automatic migration and no rename, a boot of the new binary on an
  unmigrated device does exactly what today's binary does — reads the plaintext — so deploying the
  binary and migrating the data become two independently revertible events.

*Against:* a future second device would need the operator to run one command. That is acceptable, and
if it ever stops being acceptable, automatic migration can be added later *on top of* a mechanism that
has been proven by hand — which is the right order.

### 7.5 The master key: custody, and durability as a barrier before use

The device boots unattended into a UI, has no D-Bus session, no keyring, and no operator at boot.

| Option | Defends against | Does **not** defend against | Verdict |
|---|---|---|---|
| **Random 32-byte key file, 0600, outside the prefs dir, strict checks** | another local user; `scp -r ~/.bitchat`; an app-data backup; a prefs dir pasted into an issue | anyone with the card; a full-home tarball; code execution as `sterling` | **Chosen.** |
| Derive from SoC SID (or MAC) + stored salt | the above, **plus** the card removed from the board and cloned | a full-home copy made on the running board; card + board taken together | **Deferred to v2** (`kdfId = 0x02`). |
| Passphrase on the touchscreen, cached unlock | a stolen card *and* board | unattended reboot — the node stays off the mesh until a human touches it | **Rejected as primary.** |
| TPM / secure element | card and board theft, offline attack | — | **Not available** (6.4). |

Location: `${XDG_CONFIG_HOME:-$HOME/.config}/bitchat/master.key`, deliberately **outside**
`~/.bitchat`, so the single most common accidental leak — copying the app's own data directory — does
not carry the key with it. The cost is that there are now two things to back up, and section 10's
backup command carries both.

**Creation is exclusive, and refuses when any sealed store already exists.**

1. `open(path, O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC, 0600)`. `O_EXCL` means two processes
   racing cannot both win. There is no `rename` anywhere in creation, because a rename silently
   replaces — and replacing this file strands every store sealed under the key it destroyed.
2. **Before creating anything**, consult the domain scan. If any `*.prefs.enc` exists, do not create a
   key: throw `SecureStoreUnavailableException` naming the sealed files and the key path. This is the
   difference between an incomplete restore being a permanent brick and an actionable message
   ("restore `master.key` from the same backup").

**Durability is a barrier taken before use, not a property of the creator.** This is the fix for
review finding 7, and its shape is what makes it correct without any cross-process coordination:

> Before the master key is used to seal **anything**, and regardless of whether this process created
> it or found it, the process fsyncs, in this order: the key file descriptor; the directory
> `${XDG_CONFIG_HOME:-$HOME/.config}/bitchat`; and that directory's parent.

- It removes the race entirely. The `O_EXCL` loser does not need to know anything about the winner or
  wait for it — it performs the barrier itself, and after the barrier the key is durable no matter who
  wrote it or when.
- **Creating the config parent needs its own synchronisation, and this is it.** `mkdir` of
  `.../bitchat` racing another process gives one `EEXIST`, which is success; but the *directory entry*
  for `bitchat` inside `.config` must also be durable before a key inside it counts as durable, which
  is why the parent is the third fsync and not an afterthought.
- It is idempotent and costs three fsyncs once per process start, on a boot path that already does
  far more I/O than that.
- **Implementation note:** `fsync` on an `O_RDONLY` descriptor is permitted on Linux but POSIX allows
  `EBADF`. Attempt it on the read-only descriptor; on `EBADF` or `EINVAL`, reopen the key `O_WRONLY`
  (no truncation, no `O_CREAT`), fsync, close. Directory fsync is always done on an `O_RDONLY`
  directory descriptor, which is the standard and portable case.
- A power cut between the `O_EXCL` create and the barrier leaves a short or zero-length key. On load
  that is a hard error, not a re-create. The message says: if no `*.prefs.enc` exists, `rm` the
  truncated key and rerun; otherwise restore it from backup. That remedy is safe precisely because
  step 2 established that no sealed store predates the key.

**Strict checks on load**, all fail-closed, all against the *descriptor*, never a path (7.10):
regular file (`S_ISREG` on the `fstat` result), exactly 32 bytes, `st_uid == geteuid()`,
`mode & 0o077 == 0`, and the containing directory `mode & 0o022 == 0`. Each failure names the path and
prints the exact `chmod`/`chown` to run. A world-readable key file is not a key.

**v2, explicitly not in this plan.** Reserve `kdfId = 0x02` for
`subkey = BLAKE2b(key = masterKeyFile, message = "bitchat-prefs-v1" || boardId || salt)`. Do not
enable it until an identity export/import path exists, because board-binding turns a dead board into a
dead account and the Nostr identity cannot be re-minted.

### 7.6 Cipher, format, and the content gate

**Cipher: XChaCha20-Poly1305-IETF**, from the libsodium already linked into `:data:crypto` for
linuxArm64. No new dependency. Chosen over AES-256-GCM because libsodium's AES-GCM is unavailable on
this SoC, and over `Cryptography.encryptAESGCM` because that function picks its cipher at runtime and
records the choice nowhere. The 192-bit nonce makes a fresh random nonce per write safe with no
counter and no state.

**Granularity: whole file.** Per-value encryption leaks the key set, the entry count and every value
length. The store already rewrites the whole file on every `put`, so whole-file sealing costs nothing
extra in I/O.

**Payload:** UTF-8 JSON of `Map<String,String>` via kotlinx.serialization — already a module
dependency, and it escapes everything `key=value` could not.

**On-disk layout** (binary; base64 would only inflate it):

```
off  len  field
  0    7  magic      "BCPREF" + 0x00
  7    1  version    0x01
  8    1  kdfId      0x01 = master key file only   (0x02 reserved for key file + board id)
  9   16  salt       random per write, input to subkey derivation
 25   24  nonce      random per write (crypto_aead_xchacha20poly1305_ietf_NPUBBYTES)
 49    N  ciphertext || 16-byte Poly1305 tag
```

`HEADER_BYTES` is computed from the field constants, not written as `49`, and a test asserts it
equals 49.

**Subkey:** `fileKey = crypto_generichash(out = 32, in = "bitchat-prefs-v1" || salt, key = masterKey)`
— keyed BLAKE2b. The master key is already full-entropy random, so no password KDF is warranted;
Argon2 here would burn 64 MiB and seconds of a 512 MB board's boot for nothing.

**AAD = the 49-byte header || the prefs name in UTF-8. Here is what that is and is not worth.**

Revision 1 claimed the AAD stops an attacker swapping one sealed file for another. **That claim was
wrong and is withdrawn.** Anyone who can write into `~/.bitchat/prefs` runs as `sterling`, and
`sterling` can also read the master key — so that attacker forges a valid envelope under any store
name they like. What the AAD actually buys:

- **Operator error.** A hand-restore that `cp`s `userPreferences.prefs.enc` over
  `bitchat_identity.prefs.enc` fails the tag loudly instead of silently presenting the wrong store as
  the identity. On a device whose recovery procedure is "restore two files from a tarball", this is
  the realistic failure and it is worth catching.
- **Header integrity.** Because version, kdfId, salt and nonce are inside the AAD, bit rot or a
  partial write in the header fails authentication rather than being trusted and misparsed.

What it does **not** buy: **the envelope binds no version counter, generation number or timestamp.**
Restoring an older sealed file of the same store name opens cleanly and looks current. Rollback of a
store's *contents* is undetected. Fixing that needs a monotonic counter with somewhere trustworthy to
keep it, which this device does not have; it is out of scope.

**A property worth naming, because it is the point of the whole exercise:** an AEAD envelope **cannot
be truncated at a record boundary and still read clean.** Any truncation, of any length, fails the
Poly1305 tag. Sealing therefore permanently removes the class of damage that produced review finding
5. The ledger (3.1) covers the transition; the envelope covers the steady state.

**The content gate** is what migration runs before it declares a store sealed. It is a check on
migration *input*, not a first-run discriminator (7.2). For `bitchat_identity`, all of:

- Every key of `{static_private_key, static_public_key, signing_private_key, signing_public_key}` is
  present. A missing one means the file lost records, whatever `FlatFileFormat` thought.
- Every value that was in the plaintext is in the sealed map, byte-identical, including
  `nostr_private_key` and `nostr_device_seed` when they were present.
- Each of the four key values base64-decodes to exactly 32 bytes.
- **`crypto_scalarmult_base(static_private_key) == static_public_key`.**
- **`crypto_sign_seed_keypair(signing_private_key).pk == signing_public_key`** — the stored 32-byte
  "private" is the seed, which is what `crypto_sign_seed_keypair` takes.
- If `nostr_private_key` is present: it is a valid secp256k1 scalar, and the public key derived from
  it **equals the anchor the operator recorded before touching anything** (T10 step 1), *and* equals
  `claim.nostr` in the identity ledger. Two independent anchors, one human and one machine, and the
  ledger one is available on every later run because the plaintext is never removed (7.3).

For `userPreferences` and `block_list_prefs` the gate is the round-trip equality only. Their key sets
are open-ended, so there is no "expected keys" list, and there is no key material to derive anything
from. Say so rather than pretending to check.

### 7.7 Reading a store: every case, one table

Two files per store: `P` = `<name>.prefs` (plaintext), `E` = `<name>.prefs.enc` (sealed). Nothing is
ever renamed, so there is no third name.

| E | P | Action |
|---|---|---|
| absent | absent | Ask the **invariant** (section 3), not the storage layer. Virgin domain -> empty store, normal first run. Otherwise -> refuse and report. |
| absent | present | **Read P**, plaintext, exactly as today. Log once per start that this store is unsealed and name `--migrate-storage`. No automatic migration (7.4). |
| present | absent | **Open E.** Any failure throws. |
| present | present | **Open E; P is inert.** The normal steady state after migration. P is never read while E exists and never written again. |

Failures when opening `E`, and there is no permissive case among them:

| Condition | Behaviour |
|---|---|
| Size < `HEADER_BYTES`, or magic mismatch | **Throw** `SecureStoreCorruptException`. Not "maybe it is plaintext" — `E` exists, and `E` is by definition a sealed file. |
| `version` or `kdfId` unknown | **Throw.** The message names the byte. A newer binary wrote it; do not guess. |
| AEAD tag fails (wrong key, tampering, truncation, bit rot) | **Throw.** The message names the file, the resolved master key path, and the plaintext beside it if there is one. Delete nothing. |
| Opens, but the JSON does not parse | **Throw.** This cannot happen without the key, so be loud. |

**There is no fall back from a broken `E` to `P`.** Revision 1 had one, on a magic mismatch, and it
let anyone who could corrupt one byte of `E` force the app back onto the plaintext. Worse under 7.3,
where `P` is still present on every migrated device: a silent fallback would resurrect a frozen
plaintext and then diverge from it. Refusing is the only safe answer, and the operator's remedy —
`mv` the `.enc` aside and restart, accepting the loss of everything written since migration — is one
command that the error message prints.

`EncryptedLinuxFileSettings` implements `HealthReportingSettings` with `storeDamage` always empty:
a sealed store either opens completely or throws, so it can never hand a caller a half-loaded map.
That makes `PreferenceStoreState.UNREADABLE` unreachable for this backend — a strengthening, not a
regression: the refusal moves from "do not mint" to "do not start".

### 7.8 What a failed migration guarantees, per store

Revision 2 claimed the device would be left byte-identical. That was not true, and this is the precise
replacement. After **any** failure of `--migrate-storage`, including a crash or a power cut, for the
store `S` it was working on:

- **`<S>.prefs` is byte-identical to what it was when the command started.** It is opened read-only.
  No task in this plan writes, renames or unlinks a `.prefs` file. This is unconditional.
- **`<S>.prefs.enc` is either absent, or exactly the file that was there when the command started.**
  The command creates a sealed file only for a store whose scan found none. If it created one and then
  could not verify it — re-read mismatch, content gate failure, anything after publication — it
  unlinks it, having recorded at the instant of its own successful `rename` that this file is its own
  to remove.
- **A pre-existing sealed file is never unlinked, and never overwritten.** If the scan finds `E`
  already present for a store that has not been recorded as migrated — an interrupted earlier attempt,
  a partial restore, a file of unknown origin — the command **refuses that store before writing
  anything**, names the file, and moves on to the summary. It cannot prove it wrote that file, so it
  does not touch it. The operator inspects it with `--storage-report` and decides.
- **Any `<S>.prefs.enc.tmp*` this command created is unlinked.** A temp file from an earlier,
  interrupted run is reported, not removed — same reasoning.

And, explicitly, what is **not** guaranteed, because pretending otherwise is what made revision 2's
claim false:

- `master.key` may have been created by this command, and is **deliberately kept**. Deleting it would
  strand a store that migrated successfully earlier in the same run.
- The identity ledger may have been created or gained claims. Kept, for the same reason and because a
  claim is never wrong once written.
- Stores migrated earlier in the same run keep their sealed files and are not rolled back.

The command's final summary prints one line per store — `migrated`, `already sealed`, `refused
(<reason>)`, `unchanged` — plus one line each for the key and the ledger saying whether this run
created them. That summary *is* the guarantee, made visible.

### 7.9 Fail-closed on a headless device

`Main.kt:142` constructs `App()`, which calls `startKoin`; the three preference classes build their
`Settings` in property initialisers, so a storage failure throws there, and `Drm.initialize()` is at
`:149`. **Storage is constructed before a single pixel exists.** A refusal at startup is therefore a
black screen, with the reason visible only over ssh in `journalctl -u bitchat.service`.

**Decision: accept the black screen. Do not restructure `Main.kt`.** Reasons:

- Moving Koin construction after DRM/GBM/EGL/Skia init is a large change to the startup ordering of an
  app whose render loop is already the most fragile thing in the tree
  (`apps/embedded/EMBEDDED_NOTES.md`). The risk exceeds the value of the error it would render.
- The remedy for every one of these failures is a shell command. The device has a 3.5" touchscreen and
  a CardKB; there is no affordance for restoring a file or running `chmod`.
- `scripts/deploy-pi.sh` polls the new invocation's journal, requires the identity line, and dumps the
  journal and dies if it does not appear. A storage refusal fails the deploy loudly.

**And 7.4 shrinks this surface substantially, which is a large part of why it was chosen.** Migration
is where the interesting failures are, and migration now happens in a command with an exit code and a
terminal, not at startup. What remains reachable at startup is: the key file has the wrong mode or
owner; `HOME` is unset; a sealed file will not open; the invariant refuses a mint. The first two are
repairable from the printed command; the last two need the backup either way.

Two cheap mitigations that are in scope:

- Every fail-closed message is one self-contained block naming the resolved path and the exact command,
  written to stderr, readable when Koin has wrapped it several `InstanceCreationException`s deep.
- `--identity-report` and `--storage-report` are read-only and runnable with the service stopped, so
  the operator can see exactly what is on disk without starting anything.

### 7.10 TOCTOU, symlinks, `HOME`, and never hardcoding a path

- **Open once.** `stat(path)` followed by `open(path)` permits substitution between the two, and
  `stat` follows symlinks. Every read in `PosixFiles` does
  `open(path, O_RDONLY|O_NOFOLLOW|O_CLOEXEC)` -> `fstat(fd)` -> read from that same `fd`. All mode,
  uid, size and `S_ISREG` checks are on the `fstat` result.
- `ELOOP` from `O_NOFOLLOW` is reported as "is a symlink; refusing to follow", not as a generic error.
- **`O_NOFOLLOW` covers the final component only.** A symlinked *parent* is not detected. Accepted and
  stated: the parents are created by this process at 0700, and an attacker who can replace them is
  already `sterling`.
- **`HOME` unset is fatal for the master key.** `${XDG_CONFIG_HOME:-$HOME/.config}` with both unset
  must **not** inherit the existing `?: "/tmp"` fallback: a master key in `/tmp` is in a
  world-traversable directory, is wiped by a tmpfiles clean, and — combined with "never create a key
  when a sealed store exists" (7.5) — would turn one `/tmp` clean into a permanent refusal to start.
  Throw `SecureStoreUnavailableException` immediately, naming the variable. `XDG_CONFIG_HOME` is
  honoured only if it is an absolute path, per the XDG spec; a relative value is ignored and the
  `$HOME/.config` fallback applies.
- **Every path in every procedure is the *resolved* one.** `--identity-report` prints, as its first
  three lines, the resolved prefs directory, the resolved config directory and the resolved master key
  path. T3 and T10 use those values. No command in this plan hardcodes `~/.config/bitchat/master.key`,
  because the whole point of an `XDG_CONFIG_HOME`-aware resolver is that a document cannot know where
  it landed. This is also what makes the restore drill (T10 step 6) work by environment override
  alone.
- The `~/.bitchat` `/tmp` fallback in `Encryption.linux.kt:88` and `LocalModule.linux.kt:43` is left
  alone (section 13): with the key check throwing first, no run reaches a state where it matters.

### 7.11 Concurrency: the shared map, and why it must be fixed before sealing

`LinuxFileSettings.data` is a plain `mutableMapOf`. Kotlin/Native 2.2 has no freezing, so it is
genuinely shared mutable state, reached from at least two families of thread: the Compose main
dispatcher (viewmodels -> `SaveUserStateAction` -> `LocalUserPreferences`) and `Dispatchers.Default`
workers (`BluetoothMeshService.kt:82`, `PeerManager.kt:35`, `SecurityManager.kt:50`,
`PacketProcessor.kt:19`, `NostrTransport.kt:19`, `NostrRelay.kt:68`).

Today a race garbles a line or loses an update. After sealing, a concurrent `put` during `encode`
gives a torn map, and the envelope authenticates the torn content perfectly — a seal over garbage is
indistinguishable from a seal over truth. `LinkedHashMap` iteration concurrent with insertion in
Kotlin/Native is not guaranteed to throw; it can simply produce nonsense.

**Fix (T4, before any sealing exists):** one `kotlinx.atomicfu.locks.reentrantLock()` per
`LinuxFileSettings` instance, around every accessor body and around the whole encode-then-write
sequence; the same for `LinuxEncryptionSettingsFactory.settingsCache` and `LinuxSettingsFactory`,
because `getOrPut` on a shared map from several threads can return two different `LinuxFileSettings`
for one file — two in-memory maps racing to overwrite each other's whole file.

The `fsync` happens under the lock, so writers serialise for its duration. That is deliberate and is
what T8's measurement bounds.

`kotlinx.atomicfu` is already a `commonMain` dependency, and `reentrantLock()`/`withLock` are ordinary
library API needing no compiler plugin. **If they do not resolve on `linuxArm64`, stop and report** —
the correct alternative is a `pthread_mutex_t` allocated once in a `nativeHeap` arena, and that is a
decision to take deliberately. Do not substitute a spin lock: it would spin across an `fsync`.

### 7.12 Cost

Two numbers must not be conflated:

- **`e4f6a5c` already made every preference write cost two `fsync`s** (the data file and the
  directory). That is landed, shipped behaviour and is not caused by this plan.
- **This plan adds, per write:** 40 bytes from `randombytes_buf`, one keyed-BLAKE2b over 32 bytes, and
  one XChaCha20-Poly1305 pass over a few kilobytes. On a Cortex-A53 that is tens of microseconds.
  Against an SD-card `fsync` it is not measurable.

So: **sealing is free; the fsyncs are the cost, and they predate this work.** Measure anyway, because
`LocalUserPreferences.setUserState` issues **four** whole-file writes for one screen transition
(`LocalUserPreferences.kt:165-170`), so the per-transition cost is `4 x (write + 2 fsync)`, and if
that is bad it will be blamed on encryption.

**T8 measures it** on the real board, in a scratch directory, over 50 writes of a payload the size of
the real `userPreferences` store. **Decision rule, written down in advance so the number decides:**

- **p50 <= 25 ms and max <= 150 ms:** change nothing.
- **Above that:** split the paths. `bitchat_identity` keeps the synchronous durable write, always —
  it is written a handful of times in a device's life and losing one write costs an account.
  `userPreferences` and `block_list_prefs` get a 50 ms coalescing debounce.
- The debounce is **not** in this plan, because it needs a flush on shutdown and `apps/embedded` has
  no `SIGTERM`, `atexit` or shutdown handler anywhere under `apps/embedded/src/`. If the measurement
  demands it, it is a follow-up with its own plan, and the number goes in that plan's first line.

### 7.13 The API question

**Keep `createEncrypted` as the only method; do not add a `createPlaintext` sibling.** Every current
caller holds data that should be encrypted, so an explicit plaintext variant would be a footgun with
no user. The plaintext escape hatch already exists as a *different* interface: `Settings.Factory`
(`LinuxSettingsFactory` -> `~/.bitchat/settings/`), used by the nine non-secret preference classes.

| Caller | Store | Contents | Secret? |
|---|---|---|---|
| `LocalSecureIdentityPreferences` | `bitchat_identity` | `static_*`, `signing_*`, plus `nostr_private_key` and `nostr_device_seed` written through `storeSecureValue` from `NostrClient.kt:19-20` | **Yes — key material.** Whoever reads it can impersonate the device and decrypt its DMs. |
| `LocalUserPreferences` | `userPreferences` | nickname, `FavoriteRelationship` map, joined geohash channels, last channel, peer display names | **Yes — social graph and location history.** |
| `LocalBlockListPreferences` | `block_list_prefs` | mesh fingerprints and Nostr pubkeys of blocked users | **Yes — social graph.** |

### 7.14 Consistency with the desktop fail-closed rule

The embedded target adopts the same rule, not an exception. After this work it has a key source that
always exists, so there is no "no backend" case. Be honest in the documentation about *how much*
weaker the custodian is: the desktop's master key sits in an OS keyring that a locked session
protects; the embedded master key sits on the same unencrypted SD card as the data. Both are
"encrypted at rest"; only one resists an attacker holding the storage. The KDoc must say so, because
the next person to read `createEncrypted` will otherwise assume parity.

---

## 8. Task ordering

Ten tasks. Every one is a single commit and independently revertible. Three ordering principles, in
priority order:

1. **The invariant ships first, and ships alone.** T1 and T2 contain no cryptography, no file format
   change and no new file on disk except the ledger. They are worth having even if T4 onward is never
   written, and after them the identity-loss hazard is closed regardless of whether anything later in
   this plan is correct.
2. **Nothing in this plan modifies, moves or deletes a `.prefs` file.** Ever. The live plaintext
   identity is read-only to every task here (7.3).
3. **The device is touched as late as possible, and each time by the smallest possible act:**
   T3 writes one new file (the ledger), T8 writes only under a scratch directory it created, T10
   writes the sealed files. Nothing else.

```
T1   invariant types, scan, ledger, gate    repo only, 25 JVM tests, wires nothing
T2   the single choke point + rewiring      repo only, all mint sites route through it
T3   umask, --identity-report, --adopt      device: read-only report, then one new file
T4   lock the shared preference map         repo only, no format change
T5   LinuxSecretBox + one sodium_init       repo only, compile gate
T6   envelope codec + content gate          repo only, 20 JVM tests
T7   PosixFiles + LinuxMasterKey            repo only, compile gate
T8   storage self-test on the board; MEASURE  bare binary in /tmp; nothing live touched
T9   encrypted settings + --migrate-storage repo only; NOT deployed
T10  the live device: migrate and verify    the only deploy that changes stored data
```

**A note that saves an afternoon:** the gate is consulted **at mint time, not at startup**. A device
whose identity is intact never reaches it, because nothing asks to mint. So T2 does not make an
un-adopted device refuse to boot; adoption (T3) protects against *future* loss, and a pre-ledger
device with an intact store keeps working either way. The only pre-ledger device that refuses is one
that has already lost a key — which is the entire point.

T8 deliberately does **not** use `scripts/deploy-pi.sh`. That script swaps
`/opt/bitchat/releases/current` even with `--no-restart`, so from that moment any restart — including
an automatic `Restart=on-failure` or a reboot — would run the new binary. Instead T8 `scp`s the bare
`.kexe` to `/tmp` and runs it by hand. This works because every `--*` command in this plan returns
from `main` before `runApp()`, exactly as `--version` already does, so none of them needs
`compose-resources/` or DRM.

---

## 9. Tasks

### T1 — The invariant: types, classification, ledger and gate. Pure, host-tested, wired to nothing.

**Why first:** it is the whole point (section 3), it is the only part of this plan whose absence can
cost an identity, and it needs no crypto, no cinterop and no device. It can be reviewed in full on the
Mac.

**Files**
- Create `data/local/platform/src/commonMain/kotlin/com/bitchat/local/identity/IdentityComponent.kt`
- Create `.../identity/DomainScan.kt`
- Create `.../identity/DomainVerdict.kt`
- Create `.../identity/IdentityLedger.kt`
- Create `.../identity/IdentityMintGate.kt`
- Create `data/local/platform/src/jvmTest/kotlin/com/bitchat/local/identity/DomainScanTest.kt`
- Create `.../jvmTest/.../identity/IdentityLedgerTest.kt`
- Create `.../jvmTest/.../identity/IdentityMintGateTest.kt`

**Approach**

```kotlin
enum class IdentityComponent(val storeKey: String, val claimName: String) {
    MESH_STATIC("static_private_key", "claim.mesh_static"),
    MESH_SIGNING("signing_private_key", "claim.mesh_signing"),
    NOSTR_PRIVATE("nostr_private_key", "claim.nostr"),
    NOSTR_DEVICE_SEED("nostr_device_seed", "claim.nostr_seed");
    companion object { fun forStoreKey(key: String): IdentityComponent? }
}

enum class ArtifactClass { SEALED_STORE, PLAINTEXT_STORE, RETAINED_PLAINTEXT, TEMP_WRITE, MASTER_KEY, LEDGER, OTHER }

data class DomainArtifact(val directory: String, val name: String, val kind: ArtifactClass)

/** One directory's listing. [names] is null exactly when the directory could not be listed. */
data class DirectoryListing(val path: String, val existed: Boolean, val names: List<String>?, val failure: String?)

sealed interface DomainVerdict {
    /** Positive proof of a first run: every directory read, every one empty. */
    data object Virgin : DomainVerdict
    data class Inhabited(val artifacts: List<DomainArtifact>) : DomainVerdict
    /** A directory exists and could not be listed. Never treated as evidence of absence. */
    data class Indeterminate(val reasons: List<String>) : DomainVerdict
    /** Platforms with no directory domain (Android, Apple, JVM desktop). */
    data object NotApplicable : DomainVerdict
}

object DomainScan {
    /** Pure. Classifies by directory and file name only; never reads a byte of any file. */
    fun verdict(listings: List<DirectoryListing>): DomainVerdict
}
```

Classification rules, and the bias is deliberate: **anything not recognised is `OTHER`, and `OTHER` is
still an artifact.** A name this code does not know about is evidence that something has been here.
`Virgin` is returned only when every listing has `names != null` and every `names` is empty.
`Indeterminate` wins over `Inhabited` only in the message; both refuse.

```kotlin
sealed interface LedgerClaims {
    data object Absent : LedgerClaims
    data class Damaged(val reasons: List<String>) : LedgerClaims
    data class Present(val epoch: String, val claims: Map<String, String>) : LedgerClaims
    data object Unavailable : LedgerClaims          // platforms with no ledger
}

object IdentityLedger {
    const val FILE_NAME = "identity-ledger"
    const val VERSION = "1"
    /** Parses through FlatFileFormat, so damage detection is the code that is already tested. */
    fun parse(text: String?): LedgerClaims
    fun encode(claims: LedgerClaims.Present): String
    /** Idempotent. Re-recording the same value is a no-op; a different value is an error. */
    fun withClaim(claims: LedgerClaims.Present, component: IdentityComponent, value: String): LedgerClaims.Present
}
```

`parse` returns `Damaged` when `FlatFileContent.damage` is non-empty, when `version` is missing or not
`1`, or when `epoch` is missing. It **never** returns `Present` with an empty claim map because it
failed to read something. An unknown `claim.*` name is carried through untouched, so a future
component does not make an older binary declare the ledger damaged.

```kotlin
sealed interface MintVerdict {
    data object AllowedFirstRun : MintVerdict
    data class AllowedNoteworthy(val reason: String) : MintVerdict
    data class Refuse(val reason: String, val remedy: String) : MintVerdict
}

object IdentityMintGate {
    fun decide(
        component: IdentityComponent,
        domain: DomainVerdict,
        store: PreferenceStoreState,
        ledger: LedgerClaims,
    ): MintVerdict
}
```

Implement section 3.1's table verbatim, in that order, and make the `Refuse` messages carry a
`remedy` string that is a command the operator can run. When `domain == NotApplicable` **and**
`ledger == Unavailable`, reproduce `NostrIdentityMintPolicy.decide` exactly (3.2).

**Verify**
```bash
./gradlew :data:local:platform:jvmTest --console=plain -Pembedded.enabled=false
```
`BUILD SUCCESSFUL` with 25 new tests, and the 25 tests `e4f6a5c` added still green. The new tests:

*Scan (12).* 1 all three directories present and empty -> `Virgin`. 2 all three absent -> `Virgin`.
3 one directory unlistable -> `Indeterminate` naming it, never `Virgin`. 4 a `bitchat_identity.prefs`
-> `Inhabited`, classed `PLAINTEXT_STORE`. 5 a `bitchat_identity.prefs.enc` -> `SEALED_STORE`.
6 **a `bitchat_identity.prefs.plaintext.bak` and nothing else -> `Inhabited`** (round-two finding 6).
7 a `bitchat_identity.prefs.tmpAb12Cd` and nothing else -> `Inhabited`, `TEMP_WRITE`. 8 `master.key`
alone -> `Inhabited`. 9 `identity-ledger` alone -> `Inhabited`. 10 a file in the settings directory
alone -> `Inhabited`. 11 an unrecognised name -> `Inhabited`, classed `OTHER`. 12 the artifacts in the
verdict carry directory and name only — the test asserts `DomainArtifact` has no field that could hold
file content.

*Ledger (5).* 13 round-trips claims. 14 text that `FlatFileFormat` reports damage for -> `Damaged`,
**not** `Present` with no claims. 15 a missing or unknown `version` -> `Damaged`. 16 an unknown
`claim.*` survives a parse/encode round trip and does not cause `Damaged`. 17 `withClaim` is a no-op
for the same value and throws for a different one.

*Gate (8).* 18 `Virgin` + `Absent` + `FIRST_RUN` -> `AllowedFirstRun`. 19 `Inhabited` + `Absent` ->
`Refuse`, remedy names `--identity-adopt`. 20 `Damaged` -> `Refuse` for every store state.
21 **claim present, key missing from the store -> `Refuse`**, reason names the component and says
recover rather than replace (this is the round-two truncation finding, and it is the single most
important assertion in the file). 22 claim absent + `POPULATED` + clean ledger -> allowed.
23 `UNREADABLE` -> `Refuse` even with a clean ledger and no claim. 24 `Indeterminate` -> `Refuse` even
when the store says `FIRST_RUN`. 25 `NotApplicable` + `Unavailable` reproduces
`NostrIdentityMintPolicy.decide` for all three `IdentityStoreState` values, table-driven.

**Commit:** `Add the identity first-run invariant as a pure, tested decision`

---

### T2 — Route every mint site through one custodian

**Why second:** T1 is a decision nobody consults. This is the task that makes the invariant true.

**Files**
- Create `data/local/platform/src/commonMain/kotlin/com/bitchat/local/identity/IdentityCustodian.kt`
- Create `.../commonMain/.../identity/DomainInspector.kt` (interface + `NoDomainInspector`)
- Create `.../commonMain/.../identity/LedgerStore.kt` (interface + `NoLedgerStore`)
- Create `data/local/platform/src/linuxMain/kotlin/com/bitchat/local/identity/LinuxDomainInspector.kt`
- Create `.../linuxMain/.../identity/LinuxLedgerStore.kt`
- Modify `.../commonMain/.../prefs/SecureIdentityPreferences.kt` (add `loadOrMintSigningKey`)
- Modify `.../commonMain/.../prefs/impl/LocalSecureIdentityPreferences.kt`
- Modify `.../commonMain/.../transport/SecureTransportIdentityProvider.kt`
- Modify `data/remote/transport/src/commonMain/.../TransportIdentityProvider.kt` (add `loadOrMint`)
- Modify `data/remote/transport/nostr/.../NostrClient.kt` (both mint sites) and `NostrIdentityMintPolicy.kt`
- Modify `data/remote/transport/bluetooth/.../di/bluetoothModule.kt`
- Modify every `localModule` actual to register the inspector and ledger store

**Approach**

`IdentityCustodian` is section 7.1's class. Two details that are easy to get wrong:

- **The domain scan is taken once, lazily, and cached for the process lifetime.** The application's own
  first write makes the domain non-virgin, so a scan taken per call would refuse the second component
  of a genuine first run. Take it at the first `loadOrMint`, before any store has been written.
- **A claim is recorded after the key is saved, never before** (3.1), and also after a successful
  *load* of a component that has no claim yet. Recording is idempotent. A failure to record a claim is
  logged and does not fail the load — the next start records it.

`TransportIdentityProvider` gains a method whose signature mentions **no new types**, so no module
gains a dependency:

```kotlin
/** Returns the stored value, or the result of [mint] when the invariant permits creating one. */
fun loadOrMint(key: String, mint: () -> String): String
```

`SecureIdentityPreferences` gains the bluetooth-facing equivalent, also with no new types in its
signature (`:data:remote:transport:bluetooth` cannot see `:data:remote:transport`, see 6.3):

```kotlin
fun loadOrMintSigningKey(mint: () -> Pair<ByteArray, ByteArray>): Pair<ByteArray, ByteArray>
```

Rewire the three mint sites:

- `NostrClient:434-446` — replace the policy call plus `NostrIdentity.generate()` with
  `identityProvider.loadOrMint(NOSTR_PRIVATE_KEY) { NostrIdentity.generate().privateKeyHex }`.
- `NostrClient:536-552` — the same shape for `DEVICE_SEED_KEY`.
- `bluetoothModule:27-45` — the `?: run { generateEd25519KeyPair(); saveSigningKey(...) }` branch
  becomes `securePrefs.loadOrMintSigningKey { Cryptography.generateEd25519KeyPair() ... }`. The
  existing "stored public key does not match the derived one, rewrite it" branch is left exactly as it
  is: it is a repair of a key that is present, not a mint.

`NostrIdentityMintPolicy` keeps its name and its call shape and becomes a delegation to
`IdentityMintGate` so its existing test file stays meaningful. Do not delete it in this commit.

`LinuxDomainInspector` lists the three directories with `opendir`/`readdir`, distinguishing "does not
exist" (`ENOENT`) from "could not be listed" (anything else) — that distinction is the whole of
`Indeterminate`, so get it right. It reads no file contents.

`LinuxLedgerStore` reads the ledger with the existing whole-file read and writes it with the durable
write path. Until T7 extracts `PosixFiles.writeDurably`, call
`LinuxFileSettings`'s existing mechanism or duplicate the smallest necessary part and mark it with a
`// T7: replace with PosixFiles.writeDurably` comment — do not invent a second, less careful writer.

Every other platform registers `NoDomainInspector` and `NoLedgerStore` (3.2).

**Verify**
```bash
./gradlew :data:local:platform:jvmTest --console=plain -Pembedded.enabled=false
scripts/verify.sh full
```
All five gates PASS. This task changes `commonMain` that every platform compiles, so `full` is the
gate, not a shortcut.

Report, in the task notes, the output of

```bash
grep -rn "NostrIdentity.generate()\|generateEd25519KeyPair()\|generateKeyPair()" --include="*.kt" . | grep -v "/build/"
```

with a verdict line per hit: "inside a `loadOrMint` lambda" or "not identity material". Also list every
implementation of `TransportIdentityProvider` found in the tree, including test fakes, and confirm each
compiles against the new method.

**Commit:** `Make one custodian the only place identity material can be created`

---

### T3 — `umask`, `--identity-report`, `--identity-adopt`, and the ledger on the real device

**Why here:** it is the first task that touches the Pi, and the only thing it can write is one new
file that nothing else reads.

**Files**
- Create `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/IdentityCommands.kt`
- Modify `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/Main.kt:116-122`

**Approach**

1. **`umask` first.** As the very first statement of `main`, before the `--version` branch:
   ```kotlin
   platform.posix.umask(0b111_111u)  // 0o077: nothing for group or other
   ```
   One line, and it makes every file and directory this process creates owner-only — including Arti's
   state under `~/.bitchat/tor` and the Nostr data dir under `~/.bitchat/data`, neither of which this
   plan otherwise touches. Note in the commit body that the app talks to `meshcored`/`meshtasticd` over
   TCP and serial, never through a shared file, so no other process needs read access to anything it
   writes.

2. **`--identity-report`** — strictly read-only, returns before `runApp()`. Prints, in this order:
   ```
   resolved prefs dir    /home/sterling/.bitchat/prefs
   resolved config dir   /home/sterling/.config/bitchat
   resolved key path     /home/sterling/.config/bitchat/master.key   (absent)
   domain verdict        Inhabited (7 artifacts)
     ~/.bitchat/prefs        bitchat_identity.prefs        PLAINTEXT_STORE
     ...
   ledger                Absent
   bitchat_identity      plaintext, 6 keys, no damage
     static_private_key  present   signing_private_key present
     nostr_private_key   present   nostr_device_seed   present
   userPreferences       plaintext, 41 keys, no damage
   block_list_prefs      plaintext, 3 keys, no damage
   ```
   **Key names and presence only. Never a value, never a key byte.** The first three lines are what
   every later command uses (7.10) — nothing in this plan hardcodes a path.

3. **`--identity-adopt --npub-hex <64 hex>`** — the one-time conversion of a device that predates the
   ledger. It:
   - refuses, changing nothing, if a ledger already exists (adoption is not an update path);
   - refuses if the identity store reports any damage;
   - derives the Nostr public key from the stored `nostr_private_key` with the existing
     `Cryptography` API — this adds no cryptography, it calls what is already linked — and **refuses,
     changing nothing, if it does not equal `--npub-hex`**;
   - writes the ledger with a fresh random `epoch` and one claim per component actually present, the
     mesh claims taken verbatim from the stored public keys;
   - prints exactly what it wrote and exits non-zero on any refusal.

   `--npub-hex` is mandatory whenever `nostr_private_key` is present. There is no "adopt whatever is
   there" mode: an unanchored adoption would bless a truncated store as the truth, which is the
   failure this whole plan exists to prevent.

**Verify**
```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
scp apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe sterling@<pi>:/tmp/bitchat-id.kexe
ssh sterling@<pi> 'chmod 755 /tmp/bitchat-id.kexe && /tmp/bitchat-id.kexe --identity-report'
```
Run the report **while the service is still running** — it is read-only — and paste its output into
the task report. Note the npub the app is showing and the nickname; that pair is the anchor for the
rest of this plan and for T10.

Then, with the service stopped so no cached scan is stale:
```bash
ssh sterling@<pi> 'sudo -n systemctl stop bitchat.service'
ssh sterling@<pi> '/tmp/bitchat-id.kexe --identity-adopt --npub-hex <64 hex>; echo "exit=$?"'
ssh sterling@<pi> '/tmp/bitchat-id.kexe --identity-report'
ssh sterling@<pi> 'sudo -n systemctl start bitchat.service && sleep 8 && systemctl is-active bitchat.service'
ssh sterling@<pi> 'rm -f /tmp/bitchat-id.kexe'
```
Expected: `exit=0`; the second report shows `ledger Present` with four claims; the service comes back
active and the app shows the same npub and nickname. **Nothing was deployed** — `current` was not
swapped and the running service is the old binary throughout.

**Commit:** `Report and adopt the embedded identity domain, and set the process umask`

---

### T4 — Serialise the shared preference map

**Why here:** it is correct on its own, it changes no file format, and it is the one bug whose
consequence gets *worse* once sealing exists (7.11). It must land before T9.

**Files**
- Modify `data/local/platform/src/linuxMain/kotlin/com/bitchat/local/prefs/Encryption.linux.kt`
  (`LinuxFileSettings`, `LinuxEncryptionSettingsFactory`)
- Modify `data/local/platform/src/linuxMain/kotlin/com/bitchat/local/di/LocalModule.linux.kt`
  (`LinuxSettingsFactory`)

**Approach**
1. `private val lock = kotlinx.atomicfu.locks.reentrantLock()` as a field of `LinuxFileSettings`.
2. Wrap the body of every `Settings` override (`keys`, `size`, `clear`, `remove`, `hasKey`, and all
   fourteen `put*`/`get*`), plus `loadFromFile`, `saveToFile` and `storeState`, in `lock.withLock { }`.
   The lock is reentrant, so `putString` calling `saveToFile` under the same lock is fine — and it is
   what makes "mutate then write" one atomic step rather than two.
3. Give `LinuxEncryptionSettingsFactory` its own lock and wrap `settingsCache.getOrPut`. Do the same
   for `LinuxSettingsFactory.create` (it has no cache today; add none — note in a comment that two
   calls with the same name produce two independent maps over one file, a pre-existing hazard for the
   non-secret stores and out of scope here).
4. Change no behaviour and no message. The diff should be imports, a field, and `lock.withLock {` / `}`.

**Verify**
```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
```
`BUILD SUCCESSFUL`. **If `kotlinx.atomicfu.locks.reentrantLock` does not resolve, stop and report** —
see 7.11 for why not to improvise.

**Commit:** `Serialise the embedded preference map across threads`

---

### T5 — `LinuxSecretBox` in `:data:crypto`, and one `sodium_init`

**Files**
- Create `data/crypto/src/linuxMain/kotlin/com/bitchat/crypto/LinuxSecretBox.kt`
- Modify `data/crypto/src/linuxMain/kotlin/com/bitchat/crypto/Cryptography.linux.kt:73-76`

**Approach**

First, the single initialiser. `Cryptography.sodiumReady` is currently the only `sodium_init()` in the
tree; a second one in `LinuxSecretBox` would be a second independent guard racing the first. Extract
it into one internal object in the same source set and have both use it:

```kotlin
internal object Sodium {
    val ready: Boolean = run {
        if (sodium_init() < 0) error("sodium_init failed")
        true
    }
}
```

`Cryptography.sodiumReady` becomes `Sodium.ready`. Kotlin/Native initialises an object once, under a
lock, so one object is genuinely one init. Do not add a member to the `expect object Cryptography` —
that would force four unrelated actuals.

Then the box. A linux-only public `object`; `data:local:platform` already depends on `:data:crypto`,
so its `linuxMain` can call it with no build-file change.

```kotlin
@OptIn(ExperimentalForeignApi::class)
object LinuxSecretBox {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 24   // crypto_aead_xchacha20poly1305_ietf_NPUBBYTES
    const val TAG_BYTES = 16     // crypto_aead_xchacha20poly1305_ietf_ABYTES

    fun randomBytes(size: Int): ByteArray
    /** BLAKE2b keyed hash: crypto_generichash(out=32, in=context||salt, key=key). */
    fun deriveSubkey(key: ByteArray, context: String, salt: ByteArray): ByteArray
    /** Returns ciphertext||tag. Throws IllegalStateException if libsodium fails. */
    fun seal(plaintext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray
    /** Returns null on authentication failure - the only expected failure. Throws on argument errors. */
    fun open(sealed: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?
    fun wipe(buffer: ByteArray)

    // Used only by the migration content gate (7.6); both are in sodium.h and already exposed.
    fun x25519PublicKey(privateKey: ByteArray): ByteArray
    fun ed25519PublicKeyFromSeed(seed: ByteArray): ByteArray
}
```

Mirror the `usePinned` / `memScoped` / `reinterpret<uint8_tVar>()` shape of `encryptXChaCha`
(`Cryptography.linux.kt:700-733`), but pass real `ad`/`adlen` instead of the `null, 0u` that function
passes, and read `Sodium.ready` at the top of every entry point.

`open` must distinguish "the tag failed" (return `null`) from "you passed a 17-byte nonce" (throw).
**Do not wrap the body in `catch (_: Throwable) { null }`** the way `decryptXChaCha` does at
`Cryptography.linux.kt:745-751`; that pattern is exactly what makes the current code unable to tell
corruption from a bad key, and 7.7 depends on telling them apart.

**Verify**
```bash
./gradlew -Pembedded.enabled=true :data:crypto:compileKotlinLinuxArm64 --console=plain
./gradlew --console=plain -Pembedded.enabled=false :domain:jvmTest :apps:desktop:compileKotlin
```
Both `BUILD SUCCESSFUL`. The second confirms the new file is invisible to non-embedded builds and that
the `sodiumReady` refactor disturbed nothing. There is no way to *execute* this on the Mac; T8's
self-test is where it first runs for real.

**Commit:** `Add an XChaCha20-Poly1305 sealed box for linuxArm64`

---

### T6 — Envelope codec and content gate in `commonMain`, tested on the JVM

**Why here:** `linuxArm64` tests cannot run on this Mac, so everything that *can* be host-tested must
live where the JVM reaches it. The framing, the AAD construction, the corruption rules and the content
gate are all pure functions.

**Files**
- Create `data/local/platform/src/commonMain/kotlin/com/bitchat/local/prefs/secure/SecretBox.kt`
- Create `.../secure/SecretPrefsCodec.kt`
- Create `.../secure/SecureStoreExceptions.kt`
- Create `.../secure/IdentityContentGate.kt`
- Create `data/local/platform/src/jvmTest/kotlin/com/bitchat/local/prefs/secure/SecretPrefsCodecTest.kt`
- Create `.../jvmTest/.../secure/IdentityContentGateTest.kt`

**Approach**

```kotlin
interface SecretBox {
    val nonceBytes: Int
    fun randomBytes(size: Int): ByteArray
    fun deriveSubkey(context: String, salt: ByteArray): ByteArray
    fun seal(plaintext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray
    fun open(sealed: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray?
}

interface IdentityKeyDeriver {
    fun x25519PublicKey(privateKey: ByteArray): ByteArray
    fun ed25519PublicKeyFromSeed(seed: ByteArray): ByteArray
    /** null when [privateKeyHex] is not a valid secp256k1 scalar. */
    fun nostrPublicKeyHex(privateKeyHex: String): String?
}

class SecureStoreCorruptException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
class SecureStoreUnavailableException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

object SecretPrefsCodec {
    val MAGIC = byteArrayOf(0x42, 0x43, 0x50, 0x52, 0x45, 0x46, 0x00)  // "BCPREF\0"
    const val VERSION: Byte = 0x01
    const val KDF_KEYFILE: Byte = 0x01
    const val SALT_BYTES = 16
    const val NONCE_BYTES = 24
    val HEADER_BYTES = MAGIC.size + 1 + 1 + SALT_BYTES + NONCE_BYTES   // computed, not 49

    fun encode(entries: Map<String, String>, storeName: String, box: SecretBox): ByteArray
    fun isEnvelope(bytes: ByteArray): Boolean
    /** Throws SecureStoreCorruptException on every failure in 7.7's table. */
    fun decode(bytes: ByteArray, storeName: String, box: SecretBox): Map<String, String>
}
```

`encode`: fresh `salt` and `nonce` per call from `box.randomBytes`; require
`box.nonceBytes == NONCE_BYTES` and throw if not, so a mismatched box cannot write a header the
decoder will misparse; `header = MAGIC || VERSION || KDF_KEYFILE || salt || nonce`;
`aad = header || storeName.encodeToByteArray()`; payload =
`Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), entries).encodeToByteArray()`;
result = `header || box.seal(payload, aad, nonce, box.deriveSubkey("bitchat-prefs-v1", salt))`.

`decode`: length >= `HEADER_BYTES`; magic matches; version and kdfId known; rebuild the identical
`aad`; `box.open` returning `null` -> `SecureStoreCorruptException`; then `Json.decodeFromString`
inside a `try` that rethrows as `SecureStoreCorruptException`. Every message names `storeName`.

`IdentityContentGate` — the pure part of 7.6:

```kotlin
object IdentityContentGate {
    /** Empty means the sealed map is a complete, self-consistent identity. */
    fun check(
        storeName: String,
        before: Map<String, String>,
        after: Map<String, String>,
        deriver: IdentityKeyDeriver,
        expectedNostrPublicKeyHex: String?,   // the operator anchor, and the ledger claim
    ): List<String>
}
```
For a `storeName` other than `bitchat_identity` it checks only `before == after`. For
`bitchat_identity` it runs every bullet in 7.6 and returns one string per failure. It never returns a
key value in a failure string — only key names, sizes and the fact of a mismatch.

The JVM test double is a **real** AEAD, not a stub: `AES/GCM/NoPadding` with `updateAAD`, and
`deriveSubkey` as `Mac("HmacSHA256")`. **It declares `nonceBytes = 24`, matching production**, and
derives its 12-byte GCM IV as `SHA-256(nonce).copyOf(12)` — deterministic, consuming the whole 24-byte
nonce, so the tamper tests stay meaningful and a production/test nonce-size divergence cannot hide.
The `IdentityKeyDeriver` double uses JCE X25519 and Ed25519, and a fixed table for the Nostr mapping.

Tests, each asserting one thing. *Codec (13):* 1 empty map round-trips; 2 a 64 KB value round-trips;
3 values containing `\n`, `=` and non-ASCII round-trip; 4 two `encode` calls on identical input differ
(fresh nonce and salt); 5 flipping a ciphertext byte throws; 6 flipping a header byte throws (the
header is AAD); 7 the wrong `storeName` throws; 8 truncation to `HEADER_BYTES + 1` throws;
9 truncation below `HEADER_BYTES` throws; 10 an unknown `version` throws and the message names the
byte; 11 `isEnvelope` is false for `"a=b\n"` and true for `encode` output; 12 `HEADER_BYTES == 49`,
`NONCE_BYTES == 24`, and the box's `nonceBytes == SecretPrefsCodec.NONCE_BYTES`; 13 `encode` throws
for a box whose `nonceBytes` is 12.

*Gate (7):* 14 a complete consistent identity passes; 15 a map missing `signing_private_key` fails and
the message names it; 16 `static_public_key != x25519PublicKey(static_private_key)` fails;
17 `signing_public_key` not matching the seed's derived key fails; 18 `before` has
`nostr_private_key` and `after` does not -> fails; 19 a derived Nostr public key differing from
`expectedNostrPublicKeyHex` fails; 20 a non-identity store with `before == after` passes even when the
identity keys are absent.

**Verify**
```bash
./gradlew :data:local:platform:jvmTest --console=plain -Pembedded.enabled=false
```
`BUILD SUCCESSFUL`, 20 new tests, and T1's 25 plus `e4f6a5c`'s 25 all still green. Confirm the counts
in `data/local/platform/build/reports/tests/jvmTest/index.html`.

**Commit:** `Add an authenticated envelope format for secret preferences`

---

### T7 — POSIX file primitives and the master key

**Files**
- Create `data/local/platform/src/linuxMain/kotlin/com/bitchat/local/prefs/secure/PosixFiles.kt`
- Create `.../linuxMain/.../secure/LinuxMasterKey.kt`
- Create `.../linuxMain/.../secure/SodiumSecretBox.kt`
- Modify `.../linuxMain/.../prefs/Encryption.linux.kt` (extract `saveToFile`'s body; no behaviour change)
- Modify `.../linuxMain/.../identity/LinuxLedgerStore.kt` (use `PosixFiles.writeDurably`; remove T2's
  `// T7:` comment)

Run the hardware check in 6.4 before starting and paste its output into the task report.

**Approach**

`PosixFiles` — every function fails loudly, with `strerror(errno)` and the path:

- `readAll(path): ByteArray?` — `null` **only** on `ENOENT`.
  `open(path, O_RDONLY or O_NOFOLLOW or O_CLOEXEC)`; `ELOOP` -> throw "is a symlink; refusing to
  follow"; `fstat(fd)`; require `S_ISREG`; read from that same `fd` in a loop until EOF. **No
  `stat(path)` anywhere** (7.10).
- `statOf(fd): FileFacts` — `(mode, uid, size, isRegular)` from `fstat`, so callers check the
  descriptor they will read, never a path they might not.
- `writeDurably(path, bytes)` — **the body currently inside `LinuxFileSettings.saveToFile`
  (`Encryption.linux.kt:187-255`), moved here verbatim in behaviour**: `mkstemp("$path.tmpXXXXXX")`,
  `fchmod` 0600, write handling short writes and `EINTR`, `fsync(fd)`, `close(fd)`,
  `rename(tmp, path)`, then `open` the directory `O_RDONLY` and `fsync` it. On any failure `unlink` the
  temp and throw. `LinuxFileSettings.saveToFile` becomes a call to this plus its `storeDamage` line.
  **This is an extraction, not a rewrite.**
- `createExclusively(path, bytes): Boolean` — `open(path, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW
  or O_CLOEXEC, 0600)`; on `EEXIST` return `false`; write, `close`, return `true`. **No `rename` and no
  temp file** (7.5). It does not fsync — the barrier below does, for creator and finder alike.
- `syncDurable(path)` / `syncDirectory(path)` — the barrier primitives. `syncDurable` opens
  `O_RDONLY|O_NOFOLLOW`, calls `fsync`, and on `EBADF`/`EINVAL` reopens `O_WRONLY` (no `O_CREAT`, no
  `O_TRUNC`) and fsyncs that (7.5).

`LinuxMasterKey.loadOrCreate(domain: DomainVerdict): ByteArray`:

1. `configDir`: `getenv("XDG_CONFIG_HOME")` if set **and absolute**, else `"${getenv("HOME")}/.config"`.
   If `HOME` is also unset, throw `SecureStoreUnavailableException` naming both variables (7.10). Then
   `"$configDir/bitchat"`, `ensureDirectory` (the existing helper does `mkdir` + explicit `chmod 0700`;
   `EEXIST` is success).
2. Try to read `master.key`. Present -> go to 4.
3. Absent. **Before creating it, consult the domain verdict for any `*.prefs.enc`.** If any exists,
   throw `SecureStoreUnavailableException` naming those files and the resolved key path, with the
   "restore the key from the same backup" message (7.5). Otherwise `createExclusively` with
   `LinuxSecretBox.randomBytes(32)`; on `false` fall through to 4. Print one line naming the path — the
   operator's cue that this file now needs backing up.
4. **The durability barrier, unconditionally, whether this process created the key or found it**
   (7.5): `syncDurable(keyPath)`, `syncDirectory(configDir/bitchat)`, `syncDirectory(configDir)`. Only
   after all three returns is the key allowed to be used for anything.
5. Load and validate through `readAll`/`statOf`, throwing `SecureStoreUnavailableException` with the
   exact remediation in the message: regular file; exactly 32 bytes (a short file gets the
   truncated-key message with its two branches); `st_uid == geteuid()`; `mode and 0o077u == 0u`
   ("run: chmod 600 <path>"); containing directory `mode and 0o022u == 0u` ("run: chmod 700 <dir>").

`SodiumSecretBox(masterKey) : SecretBox, IdentityKeyDeriver` — a thin adapter over `LinuxSecretBox`,
with `nonceBytes = LinuxSecretBox.NONCE_BYTES` and
`deriveSubkey(context, salt) = LinuxSecretBox.deriveSubkey(masterKey, context, salt)`. Give it
`override fun toString() = "SodiumSecretBox"` so the key cannot reach a log through interpolation. Do
not chase full key zeroing: this process holds the key for its whole lifetime by design, and
pretending otherwise is theatre.

**Verify**
```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
./gradlew :data:local:platform:jvmTest --console=plain -Pembedded.enabled=false
```
Both `BUILD SUCCESSFUL`; the second proves the `saveToFile` extraction did not disturb the common
tests. Behavioural verification is T8.

**Commit:** `Hold the embedded preference key in an exclusively created 0600 key file`

---

### T8 — Storage self-test on the board, and the write-cost measurement

**Why before the encrypted settings:** this is the first task that executes T5 and T7 on real
hardware, and it does so **without touching a single live file**.

**Files**
- Create `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/StorageSelfTest.kt`
- Modify `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/Main.kt` (argv dispatch)

**Approach**

**`--storage-selftest [dir]`** returns from `main` before `runApp()`, so it needs no
`compose-resources/` and never opens DRM.

**Safety rails, because a self-test that destroyed a retained plaintext backup would be worse than no
self-test:**

- **It always creates its own fresh child directory and works only inside it.** Even when `dir` is
  supplied, the test `mkdtemp`s `"<dir>/bitchat-selftest.XXXXXX"` and uses *that*. It never writes to,
  reads from, or cleans the supplied directory itself — a supplied directory could hold a retained
  plaintext copy, and cleanup would destroy it. `dir` defaults to `${TMPDIR:-/tmp}`.
- It **refuses to run**, with a non-zero exit, if the resolved `dir` is inside `$HOME/.bitchat` or the
  resolved config directory, or if `dir` contains any `*.prefs`, `*.prefs.enc` or `master.key`.
- It creates and uses its **own throwaway master key inside the scratch child**, with `HOME` and
  `XDG_CONFIG_HOME` pointed there. It never reads the real key.
- On exit it removes **only the child directory `mkdtemp` returned**, by that exact path, never by a
  glob, and only when every check passed.

Checks, each one line of output, `OK` or `FAIL`:
```
bitchat storage self-test  (scratch: /tmp/bitchat-selftest.Ab12Cd)
  master key       created O_EXCL, 32 bytes, mode 0600, dir 0700          OK
  master key       second create returns EEXIST and loads the same bytes  OK
  master key       durability barrier runs on the found-not-created path  OK
  master key       refuses to create beside an existing .prefs.enc        OK
  seal/open        32768-byte payload round-tripped                       OK
  tamper (body)    rejected                                               OK
  tamper (header)  rejected                                               OK
  wrong store name rejected                                               OK
  truncated 1 byte rejected                                               OK
  unknown version  rejected                                               OK
  symlink          O_NOFOLLOW refused a symlinked store                   OK
  durable write    temp 0600, renamed, dir fsynced; file is 0600          OK
  domain scan      unlistable directory reports Indeterminate, not Virgin OK
  ledger           damaged text parses to Damaged, not to "no claims"     OK
  write cost       50 writes of 4096 B: p50 7 ms, p95 12 ms, max 31 ms
PASS
```
Exit 0 on PASS, 1 on any FAIL. **Never print a key, a value, or a plaintext preference** — key names,
sizes and counts only.

The measurement is the number 7.12's decision rule consumes. Record p50, p95 and max in the task
report and state which branch of the rule they select.

**Verify**
```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
scp apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe sterling@<pi>:/tmp/bitchat-selftest.kexe
ssh sterling@<pi> 'chmod 755 /tmp/bitchat-selftest.kexe && /tmp/bitchat-selftest.kexe --storage-selftest; echo "exit=$?"'
ssh sterling@<pi> 'rm -f /tmp/bitchat-selftest.kexe'
```
Expect `PASS` and `exit=0`. **The running service is untouched**: nothing was deployed, `current` was
not swapped, and the self-test wrote only under `/tmp`. Confirm with
```bash
ssh sterling@<pi> 'ls -l ~/.bitchat/prefs ~/.config/bitchat; systemctl is-active bitchat.service'
```
— the prefs mtimes unchanged, `master.key` still absent, the service still active.

**Commit:** `Add an embedded storage self-test and measure the write cost`

---

### T9 — Encrypted settings, the migration command, and the two false statements

**Files**
- Modify `data/local/platform/src/linuxMain/kotlin/com/bitchat/local/prefs/Encryption.linux.kt`
- Create `.../linuxMain/.../secure/EncryptedLinuxFileSettings.kt`
- Create `.../linuxMain/.../secure/SecureStoreMigration.kt`
- Create `apps/embedded/src/linuxArm64Main/kotlin/com/bitchat/embedded/StorageCommands.kt`
- Modify `apps/embedded/.../Main.kt` (argv dispatch)
- Modify `data/local/platform/src/jvmMain/.../prefs/Encryption.jvm.kt:33-36` and `:118-121`

**Approach**

`EncryptedLinuxFileSettings(prefsDir, storeName, box)` keeps the same in-memory map and the same
eighteen accessor overrides as `LinuxFileSettings`, under T4's `reentrantLock` discipline. What
differs:

- `loadFromFile` implements 7.7's table. **It never migrates.** The `E absent, P present` row reads
  the plaintext and logs one line per process start naming `--migrate-storage`.
- `saveToFile` writes only the sealed file:
  `PosixFiles.writeDurably(encPath, SecretPrefsCodec.encode(data, storeName, box))`. **No rotation of
  any kind before the write** (the round-one finding). No `.enc.bak`.
- `storeDamage` is always `emptyList()`; a sealed store opens completely or throws (7.7).

`SecureStoreMigration.migrate(prefsDir, storeName, box, deriver, expectedNostrPublicKeyHex)` is called
**only** from the command below, never from a constructor:

1. Refuse, writing nothing, if `E` already exists for this store (7.8) — name it and return.
2. Read `P` through `FlatFileFormat.decode`. If `FlatFileContent.damage` is non-empty, refuse, touch
   nothing, name the damage.
3. Seal to `E` through `PosixFiles.writeDurably`. **Record, at the instant its `rename` returns, that
   this file is this run's to remove** — that flag is the whole of the cleanup guarantee in 7.8.
4. Re-open `E` from disk, decrypt, require the decoded map to equal the map from step 2.
5. Run `IdentityContentGate.check`.
6. On any failure at 4 or 5: `unlink` the `E` this run created, and throw. **Nothing else is touched:
   `P` was opened read-only and is not a target of any operation in this plan.**
7. On success: return. **There is no rename step.** `P` stays exactly where it is (7.3).

New commands in `apps/embedded`:

- **`--migrate-storage [--npub-hex <64 hex>]`** — the operator command of 7.4. Runs the migration for
  each of the three stores in turn, and prints the per-store summary that *is* the guarantee of 7.8:
  one line per store (`migrated`, `already sealed`, `refused (<reason>)`, `unchanged`) plus one line
  each for the master key and the ledger saying whether this run created them. Non-zero exit if any
  store was refused. `--npub-hex` is required when `bitchat_identity` holds a `nostr_private_key`; when
  the ledger holds `claim.nostr`, the two must agree with each other and with the derived value, and
  any disagreement refuses everything.
- **`--storage-verify`** — read-only. For every store that has both `E` and `P`, decrypts `E`, decodes
  `P`, and re-runs the content gate on the pair. This is what 7.3 buys by not deleting the plaintext:
  the cross-check is available at any time, not only during the migration minute. Prints one line per
  store; non-zero exit on any failure.
- **`--storage-report`** — read-only, safe to run against a live service. For each store it prints
  which files exist, their modes, and either `plaintext, N keys` or `sealed, version 1, kdf 1, N keys`.
  It decodes through `SecretPrefsCodec` directly: no `Settings` object, no migration path, no write of
  any kind. Structural key names only (`static_public_key present: yes`), never a value.

All three resolve every path from the environment and print the resolved values, so the restore drill
in T10 works by `HOME`/`XDG_CONFIG_HOME` override alone (7.10).

`LinuxEncryptionSettingsFactory` gains:
```kotlin
private val box: Result<SodiumSecretBox> by lazy {
    runCatching { SodiumSecretBox(LinuxMasterKey.loadOrCreate(domainVerdict)) }
}
```
and `createEncrypted` does `box.getOrThrow()`. Memoise exactly as
`DesktopEncryptionSettingsFactory.credentialStorage` does at `Encryption.jvm.kt:58-60`: the three
preference classes build their settings in property initialisers, so without memoisation one broken
key file produces three identical stack traces nested in three Koin `InstanceCreationException`s.

Correct the two now-false statements in `Encryption.jvm.kt` — the KDoc at `:33-36` and the comment at
`:118-121`, both of which say the embedded build writes plaintext. The replacement text says: every
platform encrypts; the embedded custodian is a 0600 key file on the same unencrypted SD card as the
data, which resists copying and other local users but not someone holding the card; and, until the
operator runs the retirement step, a frozen plaintext copy is still beside it. Put the same sentences
in `Encryption.linux.kt`'s class KDoc, replacing the "the contents are NOT encrypted" note at `:74-83`.

**Verify**
```bash
./gradlew -Pembedded.enabled=true :apps:embedded:linkDebugExecutableLinuxArm64 --console=plain
./gradlew :data:local:platform:jvmTest --console=plain -Pembedded.enabled=false
scripts/verify.sh full
```
`BUILD SUCCESSFUL` from each and all five gates PASS. The desktop gate matters: it proves the
`Encryption.jvm.kt` edits did not break `DesktopEncryptionSettingsFactoryTest`, whose assertions read
`secureStorageUnavailableMessage`.

Then re-run T8's self-test from `/tmp` on the device with the new binary — add two checks to it, both
in the scratch child: a plaintext store migrates, verifies and **still has its plaintext beside it**;
and a truncated plaintext is refused with nothing written.
```bash
scp apps/embedded/build/bin/linuxArm64/debugExecutable/bitchat-embedded.kexe sterling@<pi>:/tmp/bitchat-selftest.kexe
ssh sterling@<pi> 'chmod 755 /tmp/bitchat-selftest.kexe && /tmp/bitchat-selftest.kexe --storage-selftest; echo "exit=$?"; rm -f /tmp/bitchat-selftest.kexe'
```
**Nothing is deployed in this task.** `current` is not swapped and the service is not restarted.

**Commit:** `Encrypt embedded secret preferences at rest`

---

### T10 — The live device: anchor, deploy, migrate, restore-drill, document

**Files**
- Modify `apps/embedded/README.md` (a "Secrets on the device" section)
- Modify `CLAUDE.md` section 8 (remove the plaintext gap; add the SD-card limitation, the two-file
  backup rule, and the pending retirement step)
- Modify `../docs/reviews/2026-09-07-embedded-plaintext-identity.md` (a "Partially resolved" note at
  the top, naming the commits and saying plainly that the plaintext is still on the card until the
  retirement step runs)

**Step 1 — record the anchor while the app is still running.** Before stopping anything:
```bash
ssh sterling@<pi> 'systemctl is-active bitchat.service'
ssh sterling@<pi> '~/bitchat-embedded.kexe --identity-report'      # read-only; safe against a live service
```
Write down the npub and nickname the app is showing, and the three resolved paths the report printed.
Every path used below is one of those, never a hardcoded one (7.10). Convert the npub to its 64-hex
form; that is `<ANCHOR>`.

**Step 2 — stop the service.**
```bash
ssh sterling@<pi> 'sudo -n systemctl stop bitchat.service'
```

**Step 3 — the pre-migration archive (the rollback).**
```bash
ssh sterling@<pi> 'cd ~ && tar czf ~/bitchat-pre-encryption.tgz .bitchat/prefs .bitchat/settings $( [ -d .config/bitchat ] && echo .config/bitchat )'
scp sterling@<pi>:~/bitchat-pre-encryption.tgz /Users/fluxxion/Development/workspace/multiplatform/bitchat/   # keep it OUT of the repo
mkdir -p /tmp/bitchat-pre-check && tar xzf /Users/fluxxion/Development/workspace/multiplatform/bitchat/bitchat-pre-encryption.tgz -C /tmp/bitchat-pre-check
cut -d= -f1 /tmp/bitchat-pre-check/.bitchat/prefs/bitchat_identity.prefs
```
The last command must list `static_private_key`, `static_public_key`, `signing_private_key`,
`signing_public_key` and, if Nostr has run, `nostr_private_key` and `nostr_device_seed`. If it does
not, **stop**: the archive is not a backup.

**This archive cannot contain the master key, because the key does not exist yet.** It is the rollback
to the *pre-encryption* state and nothing else. The archive that can actually restore an encrypted
device is taken in step 7, and it is the one that gets drilled.

**Step 4 — deploy without restarting.**
```bash
PI_HOST=sterling@<pi> scripts/deploy-pi.sh --debug --no-restart
```
The service is already stopped, so the `current` swap is safe. Note the rollback command the script
prints.

**Step 5 — adopt, if T3 has not already.**
```bash
ssh sterling@<pi> '~/bitchat-embedded.kexe --identity-report'
ssh sterling@<pi> '~/bitchat-embedded.kexe --identity-adopt --npub-hex <ANCHOR>; echo "exit=$?"'
```
Skip the adopt if the report already says `ledger Present` with four claims.

**Step 6 — migrate, with the service stopped.**
```bash
ssh sterling@<pi> '~/bitchat-embedded.kexe --migrate-storage --npub-hex <ANCHOR>; echo "exit=$?"'
ssh sterling@<pi> '~/bitchat-embedded.kexe --storage-verify; echo "exit=$?"'
ssh sterling@<pi> '~/bitchat-embedded.kexe --storage-report'
ssh sterling@<pi> 'ls -l ~/.bitchat/prefs ~/.config/bitchat'
ssh sterling@<pi> 'head -c 10 ~/.bitchat/prefs/bitchat_identity.prefs.enc | xxd'
ssh sterling@<pi> 'grep -c nostr_private_key ~/.bitchat/prefs/bitchat_identity.prefs.enc || echo "not in the sealed file (good)"'
```
Expected: `exit=0` from both commands; three `.enc` files at `-rw-------` **beside three `.prefs`
files that are still there and still have their original mtimes**; `master.key` at `-rw-------` in a
`drwx------` directory; the magic reading `4243 5052 4546 00` then `01 01`; no cleartext key name in a
sealed file. Read the per-store summary line by line — it is the guarantee of 7.8 made visible.

**Step 7 — the restore drill: execute it, do not describe it.** This is the first archive that can
actually restore this device, because it is the first one that contains the key.
```bash
ssh sterling@<pi> 'cd ~ && tar czf ~/bitchat-post-migration.tgz .config/bitchat .bitchat/prefs .bitchat/settings'
scp sterling@<pi>:~/bitchat-post-migration.tgz /Users/fluxxion/Development/workspace/multiplatform/bitchat/

ssh sterling@<pi> 'rm -rf /tmp/restore-drill && mkdir -p /tmp/restore-drill && tar xzf ~/bitchat-post-migration.tgz -C /tmp/restore-drill'
ssh sterling@<pi> 'HOME=/tmp/restore-drill XDG_CONFIG_HOME=/tmp/restore-drill/.config ~/bitchat-embedded.kexe --identity-report'
ssh sterling@<pi> 'HOME=/tmp/restore-drill XDG_CONFIG_HOME=/tmp/restore-drill/.config ~/bitchat-embedded.kexe --storage-report'
ssh sterling@<pi> 'HOME=/tmp/restore-drill XDG_CONFIG_HOME=/tmp/restore-drill/.config ~/bitchat-embedded.kexe --storage-verify; echo "exit=$?"'
ssh sterling@<pi> 'rm -rf /tmp/restore-drill'
```
The drill must show: the resolved paths pointing inside `/tmp/restore-drill`; `bitchat_identity` as
`sealed, version 1, kdf 1`, i.e. **the archive's key decrypted the archive's data**; and
`--identity-report` printing the ledger with `claim.nostr` equal to `<ANCHOR>`. If any of that fails,
**stop** — the archive is not a restore, and the device must not be left in a state whose only backup
is unproven.

**Step 8 — start the service and check the identity end to end.**
```bash
ssh sterling@<pi> 'sudo -n systemctl start bitchat.service && sleep 8 && systemctl is-active bitchat.service'
ssh sterling@<pi> 'journalctl -u bitchat.service -b -n 80 --no-pager | grep -iE "master key|sealed|ledger|SecureStore|unsealed"'
```
**The decisive check: the app on the display shows the same npub and the same nickname as step 1.** If
it does not, stop, restore per section 11, and re-plan.

**Step 9 — leave every `.prefs` file exactly where it is**, and say so in the task report with the
`ls -l` output proving their mtimes are unchanged. Retiring them is section 13's named follow-up, run
by the operator after the device has run a week without incident. This plan does not do it and does not
schedule it.

**Step 10 — document**, in `apps/embedded/README.md`:

- **Back up two things together or neither:** the resolved config directory (`master.key` and
  `identity-ledger`) and `~/.bitchat/prefs`. Either alone is useless.
  ```bash
  ssh sterling@<pi> 'cd ~ && tar czf ~/bitchat-secrets.tgz .config/bitchat .bitchat/prefs .bitchat/settings'
  ```
  Revision 1's command omitted the config directory, which would have produced a restored device that
  could not open its own files.
- **How to prove a backup is a backup:** the drill in step 7, verbatim. An untested archive is a
  guess.
- **That tarball contains the key and the data**, so it is exactly as sensitive as the plaintext file
  used to be. Encrypting at rest moves the exposure from the card to the backup; treat the backup
  accordingly.
- **What is still in cleartext on the card, and how to remove it** — section 13's retirement
  procedure, written out, so the operator can run it when ready.
- **What this protects against** — section 0, verbatim in substance.
- **What a refusal looks like:** a black screen; the reason is in `journalctl -u bitchat.service`; the
  message names the resolved file and the exact command; `--identity-report`, `--storage-report` and
  `--storage-verify` are read-only and show what is on disk.

**Verify:** the command blocks above, plus `scripts/verify.sh full` green on the branch before merging.

**Commit:** `Migrate the Pi to encrypted preferences and document the limits`

---

## 10. Review checkpoints

- **After T1, before T2.** Read the 25 tests before building anything on them. A wrong gate is worse
  than no gate, because it converts "we did not check" into "we checked and it was fine". Test 21 —
  claim present, key missing, refuse — is the one the whole plan turns on.
- **After T2, before anything else.** The identity-loss hazard should now be closed. Confirm it with
  the `grep` in T2's verify block: every mint of identity material is inside a `loadOrMint` lambda. If
  the rest of this plan were abandoned here, the outcome would still be a net improvement.
- **After T3.** The device has a ledger and the operator has an anchor written down. Both are
  prerequisites for T10 and neither can be reconstructed later from a device that has lost a key.
- **After T8. Stop and read the measured write cost** against 7.12's rule, and record which branch it
  selects in the task report. Do not carry on to T9 without that number written down.
- **After T9, before T10.** `scripts/verify.sh full` green, and the self-test PASS on the device from
  `/tmp` with the live service untouched.
- **Inside T10, after step 7.** Do not start the service until the restore drill has passed. That is
  the only point in this plan where a proven backup exists and the live data has already changed.

---

## 11. Risks and rollback

| Risk | Likelihood | Impact | Mitigation | Rollback |
|---|---|---|---|---|
| **A new identity is minted while a recoverable one exists** | low | **severe and irreversible** — the account cannot be re-minted | the invariant (section 3), enforced in one place (7.1), shipped first (T1–T2), tested without crypto (T1's 25 tests), and holding whatever later step was interrupted; the ledger closes the clean-read-but-truncated case (3.1); the AEAD envelope removes that damage class entirely (7.6) | none needed: the failure mode is a refusal with a printed remedy, not a silent mint |
| **Migration loses the Nostr identity** | very low | severe | nothing is renamed, moved or deleted (7.3); `.prefs` files are opened read-only by every task here; migration is operator-run with the service stopped (7.4); the content gate checks derived public keys and two independent anchors (7.6); a proven restore exists before the service restarts (T10 step 7) | the plaintext is still on the device, untouched: move the `.enc` files aside and redeploy the previous release |
| Rollback then roll-forward diverges | low | moderate | **Stated, not mitigated.** A previous binary writes `<name>.prefs` and ignores `<name>.prefs.enc`; roll forward and the sealed file wins (7.7), so everything written while rolled back is lost. There is no merge. Deliberate rollbacks should move the `.enc` files aside at the same time. | operator's choice, made explicit by `--storage-report` showing both files and their mtimes |
| Master key lost or clobbered (SD corruption, a careless `rm -rf ~/.config`) | low | severe — every sealed file becomes unreadable | the key is created `O_EXCL` and never renamed over (7.5); creation refuses while any `.enc` exists; the durability barrier runs before first use (7.5); the plaintext is still on the device | restore the config directory from the step-7 archive, or fall back to the plaintext with the previous binary |
| Key created but not durable when data sealed under it is | **closed** | would be severe | the barrier of 7.5: fsync of the key descriptor, its directory and that directory's parent, taken by every process before first use, creator or not. No cross-process coordination and no waiting. | — |
| A partial sealed file from an interrupted earlier attempt is destroyed by a later run | **closed** | would be severe | 7.8: a sealed file this run did not create is never unlinked; its presence refuses the store before anything is written | operator inspects it with `--storage-report` |
| The self-test destroys retained plaintext | **closed** | would be severe | T8 always `mkdtemp`s its own child directory and cleans only that exact path; it refuses a directory containing `*.prefs`, `*.prefs.enc` or `master.key` | — |
| A fail-closed start is a black screen | low (7.4 removed most of it) | the node is off the mesh with nothing on the display | accepted deliberately (7.9); migration moved out of startup, so what remains is permissions, `HOME`, and a sealed file that will not open; every message names the resolved path and the exact command; `deploy-pi.sh` dumps the journal and fails the deploy | `journalctl -u bitchat.service`, run the printed command, restart |
| Concurrent writes corrupt a seal | medium before T4, negligible after | severe — a seal over a torn map is indistinguishable from a valid one | T4's `reentrantLock` around every accessor and around the whole encode-and-write; T4 ships before any sealing exists | revert T4 only if it deadlocks; the lock is reentrant, so the mutate-then-write path is the case to watch |
| **The plaintext keys stay on the card for weeks** | **certain, by design** | the headline benefit of this work is deferred | argued in 7.3 and stated in section 0; the retirement procedure is written out in section 13 so it is a scheduled act, not an intention | run the retirement step |
| Write cost makes the UI feel slow | medium | annoying, not dangerous | measured in T8 against a rule written before the number was known (7.12); the two `fsync`s predate this plan | if the number is bad: identity keeps the synchronous durable write, ordinary preferences get a debounce — a separate plan, because there is no `SIGTERM` handler to flush on |
| Cross-compile blindness — `linuxArm64` cannot be tested on this Mac | **high** | a bug reaches the device | the invariant, the codec and the gate are all `commonMain` with 45 JVM tests; T8's self-test exercises the real libsodium, the real POSIX path, the real scan and a real migration on the board, in a scratch directory, before anything live is touched | the self-test exits 1; do not migrate |
| `crypto_generichash`, `crypto_scalarmult_base` or the AEAD `ad` parameter binds differently than expected | low | T5 does not compile | caught at T5's compile gate, before anything is built on it | fall back to `crypto_auth_hmacsha256` for subkey derivation — already used at `Cryptography.linux.kt:370` |
| `umask(0o077)` breaks something that reads the app's files | low | a transport stops working | the LoRa daemons use TCP and serial, not shared files; proven by the service starting and a message sending | one-line revert of the `umask` call in `Main.kt` |
| Someone reads "encrypted at rest" as "safe if the card is stolen" | **high** | false confidence | section 0 is the first thing in this document; the same sentences go in `Encryption.linux.kt`, `Encryption.jvm.kt`, `apps/embedded/README.md` and `CLAUDE.md` | — |
| An older sealed backup is restored and looks current | low | stale state, silently | **stated, not mitigated** (7.6): the envelope binds no version or counter | — |

**Rollback of the whole change:** the commits are independent and ordered. T1 and T2 are safe to keep
in every case and are worth keeping on their own. Reverting T4–T10 and redeploying the previous
release restores plaintext reading immediately, because **the plaintext was never moved** — move the
`.enc` files aside first, per the third row of this table.

---

## 12. What was taken from the reference projects, and what was rejected

**Read 6.2 first: the implementer must not open passman's source.** This section is prose about
patterns, and it is the only permitted input.

**`passman` / `passwordManager` (same codebase; `passman` is the current one).** Argon2id via
BouncyCastle, HKDF-SHA256 subkeys, AES-256-GCM, three binary envelopes, `SecureFiles`/`DurableFiles`,
a staged vault migration. JVM/Android/iOS only — **no Kotlin/Native Linux target and no `.def` files
anywhere** — so there is no code there that could be lifted onto `linuxArm64` even if the licence
allowed it.

*Taken, as patterns, reimplemented from these descriptions:*

- **The whole header as AAD**, so a tampered or bit-rotted header fails the tag rather than being
  trusted. 7.6 corrects revision 1's overstatement of what this buys.
- **Magic + version + fixed-offset binary layout**, no base64 on disk.
- **temp -> `fsync` -> atomic rename -> directory `fsync`.** bitchatKmp already has this, landed in
  `e4f6a5c`; T7 extracts it rather than writing it.
- **Create the temp already owner-only**, so the atomic replace cannot widen the mode to the umask.
  Also already landed in `e4f6a5c`.
- **Migrate, read back, verify, roll back on mismatch, and retain a pre-migration copy.** T9 is this,
  with two departures. It adds a *content* gate, because a codec that faithfully seals truncated input
  is not a success. And it does not perform the pattern's final retirement of the original at all
  (7.3) — the "retain a copy" half is kept and the "replace the original" half is deferred to a
  separate operator act.
- A **redacted `toString`** on the key holder, so a key cannot reach a log through interpolation.

*Rejected:*

- **Argon2id and PBKDF2:** there is no passphrase here and the master key is already full-entropy
  random. Argon2's 64 MiB and seconds of CPU would buy nothing on a 512 MB board's boot path.
- **AES-256-GCM via JCE:** JVM-only, and libsodium's AES-GCM is unavailable on this SoC.
- **Two-generation staged keyring rewrap, PKCS#12 identity stores, biometrics, a cross-process
  `FileLock`:** all solve problems a single-process appliance does not have. (The *in-process* lock is
  a real problem and is T4.)

**Licence.** `passman`, `passmanShared` and `passmanClient` are all **AGPL-3.0**. bitchatKmp has no
`LICENSE` file and descends from a permissively licensed upstream. Copying AGPL source here would
impose AGPL on this repo, so **nothing is copied** — the patterns above are reimplemented from these
descriptions, which is not a derivative work, *provided the implementer did not read the source*
(6.2). If literal code is ever wanted from `passman`, that is a licensing decision for the owner.

**`miix`.** No prior art. No Linux native target, no KDF, no AEAD file format, no cinterop'd crypto, no
device-bound key derivation, no permission or atomic-write discipline. Its only at-rest encryption is
a dead Android `EncryptedSharedPreferences` wrapper commented out of its Koin module; everything live
is plaintext platform preferences, auth tokens included. **Nothing taken.**

---

## 13. Explicitly NOT doing

- **Not deleting, renaming or moving any `.prefs` file.** 7.3. This is the largest single difference
  from revisions 1 and 2 and it is what makes most of their blockers unreachable.

  **The retirement procedure, written down here so it is a scheduled act rather than an intention.**
  Run it as a separate change, after the device has run a week on sealed stores without incident. It
  is the step that actually removes the cleartext from the card, and until it runs section 0's first
  benefit is not realised.

  ```bash
  ssh sterling@<pi> '~/bitchat-embedded.kexe --storage-verify; echo "exit=$?"'   # must be 0
  ssh sterling@<pi> 'sudo -n systemctl stop bitchat.service'
  # take and DRILL a fresh archive first - T10 step 7, verbatim
  ssh sterling@<pi> 'cd ~/.bitchat/prefs && for f in *.prefs; do shred -u "$f" 2>/dev/null || rm -f "$f"; done'
  ssh sterling@<pi> '~/bitchat-embedded.kexe --storage-report'   # three sealed stores, no plaintext
  ssh sterling@<pi> 'sudo -n systemctl start bitchat.service'
  ```
  `shred` on flash with wear levelling does not reliably overwrite anything; it is used because it
  costs nothing, not because it works. The honest guarantee is "the file name is gone and the blocks
  are free", which is what any deletion gives on this hardware.

- **Not migrating automatically at startup.** 7.4. `--migrate-storage` is an operator command.
- **Not rotating a backup generation before a write.** If one is ever wanted it is a **copy taken
  after a successful rename**, never a move of the only live copy, and it belongs on `bitchat_identity`
  alone, at most once per process start.
- **Not debouncing ordinary preference writes.** Gated on T8's measurement against 7.12's rule. It
  needs a `SIGTERM` flush and `apps/embedded` has no shutdown handler at all, so it is a follow-up with
  its own plan.
- **Not coalescing `LocalUserPreferences.setUserState`'s four writes into one.** The better fix for the
  same cost, but it is a `commonMain` change affecting Android, iOS and desktop and should not ride
  along with a linuxArm64 encryption change.
- **Not restructuring `Main.kt` so a storage failure can be rendered on the display.** 7.9 — a
  deliberate decision, with the black screen accepted and recorded in the risks table.
- **Not extending the ledger or the domain scan to Android, Apple or desktop.** 3.2 — they get
  `NotApplicable`/`Unavailable` and behave exactly as they do today, with a test asserting it. The
  ledger is useful everywhere and is worth a later plan; it is not worth widening this one's blast
  radius.
- **Not adding `createPlaintext` or splitting `EncryptionSettingsFactory`.** All three callers hold
  secrets (7.13) and the plaintext path already exists as the separate `Settings.Factory`.
- **Not board-binding the key to the SoC SID or MAC.** Reserved as `kdfId = 0x02`; blocked on an
  identity export/import path, because it turns a dead board into a dead account.
- **Not adding a passphrase or touchscreen unlock.** It contradicts unattended boot.
- **Not binding a version counter or generation number into the envelope**, so restoring an older
  sealed file of the same store name is undetected (7.6). It needs trustworthy monotonic state, which
  this board does not have.
- **Not encrypting the nine non-secret stores** under `~/.bitchat/settings/`. They keep
  `LinuxFileSettings`, and they get T4's lock and T3's `umask`. Their *presence* is evidence for the
  domain scan (section 3), which is the only role they play here. If any of them later gains a secret
  it should move to `createEncrypted`, not have encryption bolted onto the plaintext factory.
- **Not changing the `~/.bitchat` `/tmp` fallback** at `Encryption.linux.kt:88` and
  `LocalModule.linux.kt:43`. `HOME` unset is already fatal for the master key (7.10), so no run reaches
  a state where `/tmp/.bitchat` matters. A follow-up for tidiness, not correctness.
- **Not touching Arti's state directory** (`~/.bitchat/tor`), which holds its own key material. Arti
  enforces its own permissions and T3's `umask` helps; a proper review of it is separate work.
- **Not encrypting the SD card.** Full-disk encryption is the only thing that defends against physical
  possession, and it is a provisioning decision that contradicts unattended boot in its simple form.
  Worth a separate conversation; out of scope.
- **Not changing Android, Apple or JVM desktop behaviour**, beyond correcting two false statements in
  `Encryption.jvm.kt` and adding a no-op inspector and ledger store.
