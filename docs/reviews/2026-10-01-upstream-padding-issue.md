# Draft issue for upstream (post on permissionlesstech/bitchat and bitchat-android)

Post as-is or trim. It states only our own measurements, quotes none of their code, and asks rather
than prescribes. Suggested title:

> Message padding is skipped for most payload sizes, so exact lengths are observable

---

## Body

Hello — we maintain a Kotlin Multiplatform port of bitchat and interoperate with both of these
clients, so we inherited this mechanism rather than inventing it. We think we have found a gap in it
worth your attention, and we would rather raise it than ship a one-sided change that silently breaks
DMs between our builds and yours.

### What we observe

`MessagePadding` pads a packet up to the next block in a fixed ladder (256, 512, 1024, 2048), and the
padding scheme writes the padding length into every padding byte. That length therefore has to fit in
one byte, so padding larger than 255 bytes cannot be represented and the padding is skipped — the
packet goes out at its exact size.

Because the block is chosen from `payload + 16` while the padding is capped at 255, the two rules
disagree over a large part of the range. Enumerating every size from 1 to 2032 bytes against that
logic, these sizes are emitted **unpadded**:

| Payload size (bytes) | Block the ladder selects | Padding required | Emitted |
|---|---|---|---|
| 1–240 | 256 | 16–255 | padded |
| **241–256** | 512 | 256–271 | **unpadded** |
| 257–496 | 512 | 16–255 | padded |
| **497–768** | 1024 | 256–527 | **unpadded** |
| 769–1008 | 1024 | 16–255 | padded |
| **1009–1792** | 2048 | 256–1039 | **unpadded** |
| 1793–2032 | 2048 | 16–255 | padded |
| **2033 and larger** | (no block) | — | **unpadded** |

That is **1072 of the 2032 sizes below the top block, about 53%**, plus everything above it.

### Why it matters beyond the mesh

On the Nostr path the padded packet is what ends up inside the encrypted envelope, so the bucket is
what a relay can infer. For the sizes above, there is no bucket: the relay sees the exact length
through both the seal and the gift wrap. Since neither envelope layer adds padding of its own, a
relay operator can distinguish message sizes for a majority of real messages, and can do so for every
message longer than the top block.

We do not think this is exploitable for content recovery on its own. We do think it undercuts what the
padding is there for, and it is invisible: nothing reports that a packet went out unpadded.

### What we are **not** proposing

We are not going to change this unilaterally. Any change to the padded byte count changes what your
clients receive, and the failure mode is silent in both directions — a message that fails to decrypt
or parse simply never appears, and the sender waits indefinitely for an acknowledgement. Our two
hand-updated embedded boards make a flag day worse still. So we would rather agree on a direction than
present a patch.

### Directions we can see

1. **Choose the block so the padding always fits.** If the target were derived from the payload such
   that the required padding is always 1–255 bytes, the ladder would never select an unreachable
   block. This changes emitted packet sizes, so it needs to land on both sides.
2. **Widen the padding-length encoding.** This removes the 255-byte ceiling and allows the top block
   to apply above 2032 bytes, but it is a packet-format change and the most disruptive option.
3. **Pad inside the encrypted envelope instead, for the Nostr path only.** The rumor and seal are JSON,
   and JSON parsers are required to ignore trailing whitespace, so appending ASCII spaces before
   sealing is invisible to a receiver that knows nothing about it — no packet-format change and no new
   field. We believe current clients would accept it unchanged, but we have not proven that against
   your release builds, which is exactly why we are asking rather than shipping.

Option 3 is the one we would be able to adopt without touching the mesh packet format or LoRa byte
counts, and it is the only one that also hides the length of messages above the top block.

### Questions

- Is the 255-byte interaction known and accepted, or unintended?
- Would you accept trailing whitespace inside the sealed rumor and seal JSON as a compatible way to
  bucket envelope lengths, and would you consider matching it?
- If you would rather fix the block selection in the packet format, how would you want to sequence it
  across the three clients?

Happy to supply the enumeration script, and to test any candidate against our builds on Android, iOS,
desktop and two Linux ARM64 boards.

---

## Notes for us, not for the issue

- Measured against our port's copy of the mechanism at `380b559`
  (`data/remote/transport/bluetooth/src/commonMain/.../MessagePadding.kt`, plus a second copy under
  `data/remote/rest/dto/.../protocol/MessagePadding.kt` — worth reconciling those two separately).
- `BinaryProtocol.decode` tries `decodeCore` on the raw bytes first and only falls back to `unpad`, so a
  payload that merely *looks* padded cannot be corrupted. I checked this before writing the issue
  specifically so we would not report a data-loss risk that does not exist.
- Upstream iOS is Unlicense, upstream Android is GPLv3. The issue above contains none of their code.
- Our own position is already decided: no padding change ships until upstream has received one
  (`docs/plans/2026-10-01-dm-length-padding.md`).
