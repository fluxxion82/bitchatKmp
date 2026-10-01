# NIP-44 conformance of the Nostr DM envelope

- **Date:** 2026-10-01
- **Trigger:** the external crypto review of 2026-09-30 says our NIP-44 conversation-key derivation deviates from the spec on every platform.
- **Scope:** investigation and plan only. This review changes no derivation, wire format or existing test vector.
- **Sources checked:**
  - bitchatKmp `c199524`.
  - NIP-44 at nostr-protocol/nips `0046368`. `44.md` was last changed in `733a047` on 2026-06-28.
  - Official vectors: paulmillr/nip44 `671a1f0`, `nip44.vectors.json`, sha256 `269ed0f6…5040`. This is the checksum published in NIP-44.
  - Upstream iOS: permissionlesstech/bitchat `5e9287f` (Unlicense).
  - Upstream Android: permissionlesstech/bitchat-android `1b8a825` (GPLv3). It was read for protocol facts only. Nothing from it is quoted or copied here or in the tree.

## Headline

1. **What we ship is not NIP-44 v2, and the review understates the gap.** The review is right about the key derivation: our HKDF uses an empty salt, takes the 33-byte compressed point as input, and runs an expand step labelled `"nip44-v2"`. But the cipher, MAC, nonce, padding and encoding also all differ. Our scheme is "XChaCha20-Poly1305 under one static key per key pair, `v2:` + base64url". NIP-44 v2 is "ChaCha20 + HMAC-SHA256 under per-message keys, padded, version byte, standard base64". Our implementation passes **0** of the official valid vectors that apply to it.
2. **Upstream bitchat matches us, not the spec, and says so.** Upstream iOS documents its envelope as "**not** NIP-17, NIP-44, or NIP-59 compatible" (`bitchat/Nostr/NostrProtocol.swift:10-17`). Upstream Android uses the same construction. We decrypt gift wraps produced by both upstream clients. An independent implementation of the upstream scheme decrypts ours.
3. **Consequence:** a NIP-44/NIP-17 client cannot read our DMs, and we cannot read theirs. This holds before the application layer is even considered: our DM bodies are `bitchat1:` binary packets, and we drop anything else. Bitchat-to-bitchat DMs work today across all our clients and both upstream apps. Affected traffic is every Nostr DM: npub DMs, favourite notices, delivery and read acks, and **geohash DMs**. Geohash/location **channel** posts (kind 20000 and kind 1) are plaintext signed events and are not affected.
4. **Recommendation: do not "fix the salt".** On its own that produces a third scheme that is neither NIP-44 nor bitchat. Every DM to or from any unupgraded client, including upstream iOS and Android, would then fail to decrypt, and nothing would tell the user (decrypt errors are swallowed). Instead:
   - Keep the legacy envelope as the default wire format and rename it honestly.
   - Add NIP-44 v2 as an additional **receive** format first.
   - Then switch sending per peer, only for peers proven to read it.
   - Ideally coordinate a capability bit with upstream.
5. **Two adjacent security problems need no wire change and should come first:**
   - We never authenticate the seal, so DM senders can be spoofed (§6.1).
   - The device seed behind every geohash identity is generated with a non-cryptographic RNG (§6.2).

---

## 1. What our code does

`commonMain` only declares the API (`data/crypto/src/commonMain/kotlin/com/bitchat/crypto/Cryptography.kt:10-12`). The four actuals implement one identical scheme. `nativeMain` holds only an uncompiled `Cryptography.native.kt.bak`.

| Step | JVM (desktop) `Cryptography.jvm.kt` | Android `Cryptography.android.kt` | Apple (iOS app, macOS) `Cryptography.apple.kt` | Linux (Orange Pi) `Cryptography.linux.kt` |
|---|---|---|---|---|
| Lift the x-only peer key to a point with an explicit Y parity (0x02 or 0x03) | `recoverPublicKeyPointWithParity` 162-169 | 166-173 | inside `computeSharedPointCompressed` 558-564 | 582-588 |
| ECDH: raw scalar multiply, no hashing | `computeSharedPointWithParity` 171-177 (BouncyCastle) | 175-181 | `secp256k1_ec_pubkey_tweak_mul` 566-573 | 590-597 |
| **Shared-point form fed to the KDF** | **33-byte compressed** (`compressedPoint` 179-187) | **33-byte compressed** 183-191 | **33-byte compressed** (`serializeCompressed` 602-619) | **33-byte compressed** 626-643 |
| KDF | `deriveNIP44Key` 120-124 | 123-127 | 214-217 | 222-225 |
| HKDF-extract | **salt = empty**, IKM = compressed point (126-133) | same (129-136) | `hmacSha256(empty, ikm)` 535-536 | 559-560 |
| HKDF-expand | **info = `"nip44-v2"`**, L = 32, one block (135-145) | 138-148 | 538-545 | 562-569 |
| Cipher and MAC | **XChaCha20-Poly1305** AEAD (Tink), no AAD (`encryptNIP44` 236-252) | Tink 240-256 | libsodium `crypto_aead_xchacha20poly1305_ietf_*` 219-264 | libsodium 227-272 |
| Nonce | 24 random bytes, made inside Tink | Tink | `randombytes_buf` 232-235 | 240-243 |
| Padding | **none**; the ciphertext length equals the plaintext length plus 16 | none | none | none |
| Payload | `"v2:"` + **base64url, no padding** of `nonce24 ‖ ct ‖ tag16` (220-234, 247-248) | 224-238, 251-252 | 261-262, 704-719 | 269-270, 780-795 |
| Decrypt | needs the `v2:` prefix, then **tries both parities** (even, then odd) of the sender's x-only key, with AEAD tag check (254-282) | 258-286 | 266-300, 621-647 | 274-308, 645-671 |

Notes:

- **Key schedule in one line:**
  `key = HKDF-SHA256(salt = "", IKM = 0x02|0x03 ‖ X(a·lift(B)), info = "nip44-v2", L = 32)`
  The key is static for each pair of keys. It is not a conversation key followed by per-message keys.
- **Why decryption tries both parities.** The sender lifts the recipient's x-only key to even Y. If the recipient's real point has odd Y, the computed point is `−S`. The same applies on the receiving side. The prefix byte of the compressed point therefore depends on both parties' key parities, and the receiver tries both. NIP-44 feeds only X into the KDF, so this ambiguity does not exist there.
- **HMAC with an empty key** works on every platform. The 2026-09-30 commit `b0cc960` substitutes a one-byte zero key, which HMAC's zero padding makes equivalent. Linux pads keys up to 32 bytes and SHA-256-hashes longer keys (`Cryptography.linux.kt:365-390`). That is correct for every key length NIP-44 uses (0, 8 and 32 bytes) but is non-standard for 33–64-byte keys (§6.5).
- **The existing vector** `06df0a78…482c` (`CryptographyTest.kt:31-39`) is `HKDF(salt="", IKM=00..1f, info="nip44-v2")`. Python reproduces it independently. It pins the legacy KDF parameters on a 32-byte input, while the real encrypt path feeds 33 bytes. It cannot detect any of the differences above.

## 2. What NIP-44 v2 specifies, and how we do against the official vectors

| | NIP-44 v2 (`44.md`) | Ours |
|---|---|---|
| ECDH output | `shared_x`: unhashed 32-byte X coordinate | 33-byte compressed point (prefix ‖ X) |
| Conversation key | `HKDF-extract(salt="nip44-v2", IKM=shared_x)`; extract only | `HKDF(salt="", IKM=compressed, info="nip44-v2", L=32)` |
| Per-message keys | `HKDF-expand(conversation_key, info=nonce, L=76)` → chacha_key[0:32], chacha_nonce[32:44], hmac_key[44:76] | none: the same key encrypts every message |
| Nonce | 32 random bytes | 24 random bytes |
| Cipher | ChaCha20 (RFC 8439), counter 0 | XChaCha20 inside an AEAD |
| MAC | HMAC-SHA256(hmac_key, nonce ‖ ciphertext), compared in constant time | Poly1305 tag (AEAD), no AAD |
| Padding | `[u16 len][pt][zeros]` up to `calc_padded_len`; minimum 32. Since 2026-06-28 (`733a047`, nostr-protocol/nips#1907), a 6-byte extended prefix allows lengths ≥ 65536 | none, so the exact length leaks |
| Plaintext length | 1 … 2³²−1 (65535 before #1907) | anything, including empty (JVM) |
| Payload | `base64(0x02 ‖ nonce32 ‖ ct ‖ mac32)`, standard alphabet **with** padding; `#` is reserved; reject payloads under 132 chars or under 99 decoded bytes | `"v2:" + base64url-nopad(nonce24 ‖ ct ‖ tag16)` |

### Vector run

I ran our real JVM `Cryptography` against the complete official file. A throwaway harness compiled the **unmodified** `data/crypto` commonMain, jvmMain and commonTest sources into a JVM-only build (Appendix A). It reached private helpers by reflection so that each step could be isolated.

As a control, the same harness built NIP-44 from **our own** `hmacSha256`, our ECDH point and BouncyCastle `ChaCha7539Engine`. A separate pure-Python reference written from the spec also passed every vector, which confirms how this report reads the spec.

| Category (count) | Our implementation | Spec algorithm built from our primitives (control) |
|---|---|---|
| `valid.get_conversation_key` (35) | **0/35**. Feeding X instead of the compressed point still gives 0/35. Example: sec1=1, pub2=G gives `094ae949…8c68` against the expected `3b4610cb…b54e` | 35/35 |
| `valid.get_message_keys` (32) | no equivalent API. The private `hkdfExpand(L=76)` matches only the first 32 bytes; **bytes 32..76 come back zero** because it emits one block and zero-fills the rest | 32/32 |
| `valid.calc_padded_len` (24) | n/a (no padding) | 24/24 |
| `valid.encrypt_decrypt` (10) | **decrypt 0/10** ("Invalid NIP-44 version prefix"). Adding `v2:` in front still gives 0/10 ("invalid MAC"). A spec reader decrypts **0/10** of our encryptions of the same plaintexts | encrypt 10/10, decrypt 10/10 |
| `valid.encrypt_decrypt_long_msg` (3) | n/a: there is no way to pass a nonce or conversation key, so the output cannot match by construction | 3/3 |
| `invalid.encrypt_msg_lengths` [0, 65536, 100000, 10⁷] | **accepted 4/4**. The vectors require all four to be rejected, **but the vectors predate #1907**: the current spec text allows the last three. Empty plaintext is invalid either way. | — |
| `invalid.get_conversation_key` (8) | rejected 8/8, but in every case because pub2 is invalid. With a valid pub2, the JVM side **accepts sec ≥ n+1** and rejects 0 and n only through a NullPointerException (§6.4) | — |
| `invalid.decrypt` (12) | rejected 12/12, but every one only for the missing `v2:` prefix, before any of the checks these vectors exercise | 12/12 |

The same harness ran the 18 existing `CryptographyTest` cases on JVM; all passed.

## 3. What upstream bitchat does

**Upstream iOS (Unlicense), `bitchat/Nostr/NostrProtocol.swift` @ `5e9287f`:**

- 10-17: the type comment says the envelope is "deliberately BitChat-specific and is **not** NIP-17, NIP-44, or NIP-59 compatible". It reuses the kind numbers 1059/13/14 and the `v2:` prefix "for historical reasons".
- 686-746: `deriveSharedSecret` lifts with 0x02, falling back to 0x03, and calls `sharedSecretFromKeyAgreement(..., format: .compressed)`. That is the 33-byte compressed point.
- 904-919: `derivePrivateEnvelopeKey` uses `HKDF<SHA256>` with `salt: Data()` and `info: "nip44-v2"`, outputting 32 bytes. The doc comment states that this is not the NIP-44 key schedule.
- 589-627 and 629-684: XChaCha20-Poly1305 with a 24-byte random nonce, payload `"v2:" + base64url(nonce ‖ ct ‖ tag)`. Decryption tries both parities.
- Tests: `bitchatTests/NostrProtocolTests.swift`:
  - 165-204 decrypt a frozen gift wrap **produced by upstream Android `b7f0b33d`**.
  - 206-225 decrypt a frozen wrap from iOS release `733098bb`.
  - 252-274 assert that an official NIP-44 payload is **rejected**.

**Upstream Android (GPLv3), facts only, @ `1b8a825`:**

- `app/src/main/java/com/bitchat/android/nostr/NostrCrypto.kt`:
  - 199-207: compressed point used as the HKDF input.
  - 210-238: HKDF with an empty salt and info `"nip44-v2"`.
  - 244-262: Tink XChaCha20-Poly1305, `"v2:"` + base64url.
  - 269-295: decrypt accepts only `v2:` and tries both parities.

  The construction is the same as ours.

**Empirical check:**

- Our code opens both upstream fixtures end to end: gift wrap, then seal, then rumor. The new tests do this, and they pass on JVM.
- A Python implementation of the iOS-documented scheme, sharing no code with ours, decrypts **10/10** envelopes our Kotlin code produced and both upstream fixtures.
- A NIP-44 reference decrypts **0** of them. The as-sent payloads fail as "not base64" or "invalid payload size". Reframed as standard base64, they fail with "unknown version".

**Conclusion:** upstream matches us. Our current scheme is exactly what keeps us compatible with the bitchat apps we aim to interoperate with. Moving to the spec on our own would break that.

## 4. Consequences in plain terms

- **Can a NIP-44-compliant client read our DMs? No.** Its decoder rejects `v2:…` before any crypto runs. Even if it could decrypt, the rumor content would be `bitchat1:` plus a base64url binary packet (`NostrEmbeddedBitChat.kt:22-64`), not readable text.
- **Can we read theirs? No.**
  - `decryptNIP44` requires `v2:`, so official payloads are rejected 10/10.
  - Even with the crypto fixed, `ChatRepo.handleDirectMessageEvent` drops any rumor whose content does not start with `bitchat1:` (`ChatRepo.kt:943`).
  - Also, we don't publish a NIP-17 kind-10050 DM relay list (no reference anywhere in the tree), so most standard clients won't know where to send to us.
  - All of these failures are **silent**: `unwrapGiftWrap` and `openSeal` swallow the exception and return `null` (`NostrClient.kt:379-382`, `414-417`).
- **Bitchat-to-bitchat works.** That covers our iOS, Android, desktop and two Orange Pi builds (appleMain, androidMain, jvmMain, linuxMain) talking to each other, and talking to upstream iOS and Android.
- **What is affected:** everything that goes through `NostrClient.createPrivateMessage` and therefore `encryptNIP44`:
  - npub DMs, favourite and unfavourite notices, delivery acks and read receipts (`NostrTransport.kt:21-262`);
  - **geohash DMs**, which use a per-geohash derived identity (`NostrTransport.kt:263-383`, received via `ChatRepo.kt:463-482`).
- **What is not affected:**
  - geohash/location **channel** posts: kind 20000 (`ChatRepo.kt:413-416`) and kind-1 notes are plaintext signed events;
  - presence and PoW;
  - mesh (BLE/LoRa) private messages, which use Noise.

## 5. Migration plan

### 5.1 Options

| Option | Effect on bitchat interop | Effect on standard-Nostr interop | Verdict |
|---|---|---|---|
| A. Change the salt only (what the review suggests) | **Breaks** every DM between upgraded and non-upgraded clients, including upstream iOS and Android, silently | none: still not NIP-44 | **Reject** |
| B. Replace the envelope with NIP-44 v2 everywhere at once | **Breaks** all upstream interop and our own older builds, including the Orange Pis until they are reflashed | envelope layer only; app layer still incompatible (§4) | Reject |
| C. Keep legacy as the default and add NIP-44 v2 alongside it: receive both, send v2 only to peers that are proven capable | none | possible once the app-layer work (5.5) is done | **Recommended**, if standard interop is a goal at all |
| D. Keep legacy, rename it honestly, pin it with fixtures, take no NIP-44 | none | none (same as today) | Acceptable. This is upstream's own position |

The **product decision** belongs to the owner. The envelope only matters if we also want standard clients to see readable DMs (5.5). Absent that goal, D plus §6 is the cheap, correct outcome. Either way, do steps 0 and 1 below.

### 5.2 Phased rollout (option C)

**Step 0: no wire change, ship now.**

- Rename `encryptNIP44`, `decryptNIP44` and `deriveNIP44Key` to say what they are, for example `encryptLegacyEnvelope`, or `BitchatEnvelope.seal/open`. Leave the old names in place as deprecated forwarders for one release.
- Keep `deriveNIP44Key_matchesHKDFVector` exactly as it is: it now pins the legacy KDF.
- Fix §6.1 (seal authentication) and §6.2 (seed RNG for **new** seeds only).
- Add the 64 KiB ciphertext cap that upstream iOS uses (§6.3).

**Step 1: receive-side dual stack, on every one of our builds.**

- Dispatch on format:
  - `v2:` → legacy;
  - leading `#` → "unsupported version";
  - otherwise → NIP-44 v2 decode (standard base64, version byte 0x02).
- The two formats cannot be confused: `:` is not in the base64 alphabet, and a NIP-44 payload always starts with `A` because its version byte is 0x02.
- Nothing sends v2 yet, so nothing existing can break. Un-ignore `nip44Spec_decryptsOfficialPayloads` here, or retarget it at the new entry point.

**Step 2: learn capability per peer.**

- **Peers met on the mesh.** Both upstreams carry a capabilities bitfield in announce TLV `0x05`:
  - iOS: `Protocols/Packets.swift:33-40`; bits 0–10 are already assigned in `BitFoundation/PeerCapabilities.swift`.
  - Android: `model/IdentityAnnouncement.kt:22-26`.
  - Our decoder already skips unknown TLVs (`IdentityAnnouncement.kt:86-103`) but does not send `0x05`.

  Ask upstream to reserve a bit such as `nip44v2Envelope`. Don't take one unilaterally: the namespace is shared. Noise-authenticated capabilities (upstream iOS `AuthenticatedPeerStatePacket`, `Packets.swift:160-240`) are the stronger channel.
- **Nostr-only peers** (geohash DMs, npubs never met on the mesh) have no negotiation channel. The only safe in-band signal is **reply-in-kind**: once a correctly authenticated v2 envelope arrives from pubkey P, mark P as v2-capable.
  - Do **not** signal with event tags. Upstream iOS rejects any gift wrap whose tags are not exactly `[["p", recipient]]` (`NostrProtocol.swift:111-113`), and any inner tags beyond `[]` or that same `p` tag (176-181).
  - Persist the flag with a schema version, so that a rolled-back build ignores it.

**Step 3: switch sending per peer, behind a feature flag that defaults to off.**

- Send v2 only to peers marked capable, and legacy to everyone else.
- Never send both formats for one message: dual-stack receivers would get duplicates.
- Turn the flag on only after Step 1 has reached **all** our devices, including both Orange Pis, which update by hand.

**Step 4: retire legacy sending.**

- Only after upstream iOS and Android ship v2 receive.
- Keep legacy **receive** indefinitely; it is cheap. Relays store kind-1059 events with timestamps randomized up to 48 h back, and our receive window is 48 h 15 min (`NostrClient.kt:104-113`).

### 5.3 Work per platform

NIP-44 v2 should be written **once in commonMain**: padding, `calc_padded_len`, encode and decode, HMAC with AAD, constant-time compare, and multi-block HKDF-expand built on `hmacSha256`. **Do not reuse the private `hkdfExpand`:** it emits a single block, and the vector run shows that bytes 32..76 come back as zeros. Only two new `expect` primitives are needed.

| Primitive | JVM and Android | Apple | Linux (Orange Pi) |
|---|---|---|---|
| `ecdhSharedX(priv, xOnlyPub)`, validated | the existing BouncyCastle point, take `xCoord`. Add an explicit `[1, n−1]` range check (§6.4) | `secp256k1_ec_pubkey_tweak_mul` (already used), then serialize and drop the prefix byte | same as Apple |
| `chacha20(key, nonce12, data)`, RFC 8439, counter 0 | BouncyCastle `ChaCha7539Engine` (bcprov is already a dependency; it passed 10/10 in the harness) | libsodium `crypto_stream_chacha20_ietf_xor`. The prebuilt libs use `--enable-minimal` (`native/build-libsodium-*.sh`), so **confirm it links** before relying on it. A pure-Kotlin ChaCha20 in commonMain checked against RFC 8439 vectors avoids that question | same as Apple |
| HMAC-SHA256 | existing | existing (CCHmac) | existing; correct for 0-, 8- and 32-byte keys |

No changes are needed in `native/`, and no submodule bumps.

### 5.4 Test vectors to add

1. The **full official file**, generated into a commonTest Kotlin source with a sha256 check against `269ed0f6…`. Cover every category above; the run in §2 is the baseline.
2. The three **extended-prefix** vectors from the current `44.md` table (lengths 65535, 65536, 65537). Write down the policy for the conflict with `invalid.encrypt_msg_lengths`. Recommended: accept the extended prefix on receive, and cap sends at 65535 bytes so that readers older than #1907 can open them. This costs nothing, since upstream already caps envelopes at 64 KiB.
3. **Legacy freeze:**
   - keep the two upstream fixtures added in this review;
   - add one frozen legacy gift wrap **produced by each of our platforms**: JVM, Android, Apple, Linux. Each should carry the recipient key and record the commit it was produced at, so that legacy cannot drift on any platform.
4. **Format dispatch:** `v2:` → legacy, base64 → v2, `#` → unsupported, garbage → error. A spec reader must reject legacy envelopes, and legacy must reject spec payloads.
5. **Cross-client:** at Step 3, a v2 envelope from each of our platforms must decrypt with the Python or reference implementation.
6. Run everything on `jvmTest`, `macosArm64Test` and `iosSimulatorArm64Test`, and on **linuxArm64**. There is currently no Linux test gate, and the Orange Pi path is a separate actual.

### 5.5 What standard-client interop actually requires beyond the envelope

- Plain-text kind-14 rumor content for human messages, while keeping `bitchat1:` for acks.
- Accepting non-`bitchat1:` content on receive.
- Publishing a kind-10050 relay list.
- NIP-17's self-copy.
- Possibly kind-15 messages.

Without these, step C still leaves standard clients with an envelope they can open but contents they can't render, and vice versa. Scope this before committing to option C.

### 5.6 Risk of silently breaking conversations

- **Highest risk:** any edit to the legacy path, such as option A, a "harmless" refactor of `compressedPoint`, or changing which parity is tried first. Failure is invisible: the message just never appears, its acks never arrive, and the sender sees it stuck at "sent". The legacy fixtures added here are the guardrail. If they go red, stop.
- **Orange Pi lag:** these boards update last. Per-peer reply-in-kind protects them automatically, because they never send v2, so nobody marks them capable.
- **Geohash identities** derive from the device seed (§6.2). The envelope migration does not touch them. Fixing the RNG must not regenerate existing seeds, or every geohash DM thread is orphaned.

### 5.7 Rollback

| Step | Rollback | Why it is safe |
|---|---|---|
| 0 | revert the build | the wire format is unchanged; renames come with forwarders |
| 1 | revert the build | nobody sends v2 yet |
| 3 | turn the flag off; sends return to legacy | receivers stay dual-stack. Peers that already marked us capable keep sending v2, which we can still read, **provided the rollback floor is a Step-1 build**. Never ship a v2-sending build to a device whose previous build lacked v2 receive |
| 4 | turn legacy sending back on | legacy receive was never removed |

## 6. Related findings (outside the derivation question; none changed here)

1. **High: DM sender spoofing.** `NostrClient.decryptPrivateMessage` (`NostrClient.kt:84-138`) checks only the **outer** wrap's signature. It never verifies the seal's signature and never checks `seal.pubkey == rumor.pubkey`. It then returns `rumor.pubkey` as the sender, and `ChatRepo` uses that for attribution, blocking and favourite handling (`ChatRepo.kt:941-1031`).
   - Anyone who knows a user's npub can make a DM appear to come from any pubkey, including a favourite. To do it, they seal with their own key a rumor that claims the victim's pubkey.
   - Both upstreams check this: iOS at `NostrProtocol.swift:131-166`, Android at `NostrProtocol.kt:77, 89`.
   - Fix: verify the seal signature and kind 13, require `seal.pubkey == rumor.pubkey`, and return `seal.pubkey`. Every deployed sender already signs its seals, so this is receive-only and needs no wire change.
2. **High: non-CSPRNG geohash identity seed.** `NostrClient.getOrCreateDeviceSeed` mints the 32-byte seed with `kotlin.random.Random.nextBytes`, and the `SecureRandom` line is commented out (`NostrClient.kt:533-535`). `kotlin.random` is not a cryptographic generator, and **every geohash private key** is `HMAC(seed, geohash ‖ i)` (`NostrClient.kt:470-505`). Upstream iOS uses CryptoKit (`NostrIdentityBridge.swift:79-81`). Fix this for new seeds. Rotating existing seeds is a separate decision with its own trade-off.
3. **Medium: no size bound before base64 decode or JSON parse** on inbound wraps, seals and rumors. Upstream caps them at 64 KiB (`NostrProtocol.swift:39-43, 107-110, 636-639`).
4. **Low: JVM/Android private-key range.** ECDH accepts scalars ≥ n+1 and rejects 0 and n only through a NullPointerException. Apple and Linux check with `isValidPrivateKey`.
5. **Low: Linux `hmacSha256`** hashes keys of 33–64 bytes, where RFC 2104 hashes only keys longer than 64. This doesn't affect any current caller: the seed is 32 bytes and the NIP-44 keys are 0, 8 or 32.
6. **Low: platform divergence on empty plaintext.** The JVM encrypts `""`. Apple and Linux pin it with plain `usePinned` (`Cryptography.apple.kt:241`, `Cryptography.linux.kt:249`), unlike the rest of `b0cc960`. That throws `addressOf(0)` on an empty array, so on those platforms an empty plaintext is rejected for the wrong reason. This is not reachable today, because seals and rumors are never empty.
7. **Privacy:** there is no padding, so relays see each DM's exact length through both layers. NIP-44's padding exists to blunt this.
8. **Provenance, for the owner:** the eight NIP-44 functions in our JVM and Android actuals are 95% identical (88 of 93 non-comment lines, same order and same identifiers) to upstream Android's `NostrCrypto.kt` lines 159-295, which is GPLv3. This report makes no licensing assessment. It is flagged because the brief treats that tree as read-only. Any new NIP-44 code should be written from `44.md` and the Unlicense iOS sources.

## 7. What this review adds, and gate status

- `data/crypto/src/commonTest/kotlin/com/bitchat/crypto/Nip44ConformanceTest.kt`:
  - `legacyEnvelope_*` pins today's wire behaviour against the two upstream fixtures, the `v2:` layout and the legacy key schedule;
  - `nip44Deviation_*` characterises the gap against the official vectors and passes today;
  - `nip44Spec_decryptsOfficialPayloads` is `@Ignore`'d because it is **expected to fail** until Step 1. Un-ignored, it fails with `Invalid NIP-44 version prefix`; this was checked.

  It uses only the existing public API. No existing test or vector was changed.
- **Gates.** The requested gates **could not run in this container**:
  - `:data:crypto:jvmTest` and `scripts/verify.sh quick` failed while configuring, because the session's network policy blocks `dl.google.com`, so the Android Gradle Plugin cannot resolve.
  - `:data:crypto:macosArm64Test` needs a macOS host with Homebrew libsodium and secp256k1.

  As a substitute, the throwaway harness (Appendix A) compiled the unmodified `data/crypto` commonMain, jvmMain and commonTest sources. Result: 18 existing tests passed, 6 new tests passed, 1 new test was skipped (the `@Ignore`'d one).

  **Before merging, run** on a Mac:
  ```
  ./gradlew --console=plain -Pembedded.enabled=false -Ptui.enabled=false :data:crypto:jvmTest :data:crypto:macosArm64Test
  scripts/verify.sh quick
  ```
  Native has not been exercised. The new tests use only kotlin.test, kotlinx-serialization-json (already a commonMain dependency) and `kotlin.io.encoding.Base64`.

## Appendix A: reproducing the vector run

The harness is a JVM-only Kotlin Multiplatform build. Its `commonMain`, `jvmMain` and `commonTest` source directories point at `data/crypto/src/...`, with bcprov 1.85, Tink 1.23.0 and kotlinx-serialization-json 1.9.0.

The runner loads `nip44.vectors.json` and calls `Cryptography` directly. It reaches the private `computeSharedPointWithParity`, `compressedPoint` and `hkdfExpand` through reflection, so that each step can be isolated.

Raw output:

```
valid.get_conversation_key (35): ours(compressed point, salt='', info='nip44-v2')=0  ours-deriveNIP44Key(x-only)=0  spec-from-our-primitives=35
  example sec1=1 pub2=79be667ef9dcbbac..: expected=3b4610cb7189beb9cc29eb3716ecc6102f1247e8f3101a03a1787d8908aeb54e  ours=094ae949687633b63ca2c51dc2c0b2ec2ece8920b7e2e51d29fee568cca28c68  ourIKM=0279be667ef9dcbbac..(33 bytes)
valid.get_message_keys (32): no public equivalent. ours-private-hkdfExpand(L=76)=0 (first 32 bytes match: 32; bytes 32..76 all zero: true)  spec-from-our-primitives=32
valid.calc_padded_len (24): ours=N/A (no padding in our scheme)  spec-from-our-primitives=24
valid.encrypt_decrypt (10): ours conv-key match=0  ours.decryptNIP44(payload)=0  ours.decryptNIP44('v2:'+payload)=0  spec-reader-on-ours.encryptNIP44=0  | spec-from-our-primitives encrypt=10 decrypt=10
  our decrypt error: plain: Invalid NIP-44 version prefix
  our decrypt error: v2:-prefixed: java.security.GeneralSecurityException: invalid MAC
valid.encrypt_decrypt_long_msg (3): ours=N/A (no nonce/conversation-key API; payload checksums cannot match by construction)  spec-from-our-primitives=3
invalid.encrypt_msg_lengths [0, 65536, 100000, 10000000]: ours rejects=0/4
invalid.get_conversation_key (8): rejected 8/8 (all via pub2 decoding)
  with a valid pub2: sec=0 rejected (NPE), sec=n rejected (NPE), sec=n+1 ACCEPTED, sec=2^256-1 ACCEPTED
invalid.decrypt (12): ours rejects=12 (all for 'Invalid NIP-44 version prefix')  spec-from-our-primitives rejects=12
```

Independent Python check (secp256k1 in pure Python plus `cryptography`):

```
reference NIP-44 sanity: get_conversation_key 35/35, encrypt_decrypt(decrypt) 10/10
our Kotlin envelopes: independent legacy (iOS-scheme) decrypt 10/10; NIP-44 reference decrypt 0/10
upstream fixture android b7f0b33d: seal kind=13 pubkey=79be667e…; rumor kind=14 tags=['p'] content='legacy fixture from Android b7f0b33d'
upstream fixture ios 733098bb:     seal kind=13 pubkey=2e3d79df…; rumor kind=14 tags=[]    content='legacy fixture from 733098bb'
NIP-44 reader on both fixtures: as sent -> "Only base64 data is allowed"; reframed to std base64 -> "unknown version"
```
