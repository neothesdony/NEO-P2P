# NEO-P2P — Verified Review Findings (2026-11-03)

Branch: `v0.3.0` · Base commit: `2ab602c` · Scope: read-only source review, no `android/` source
modified.

This document is the **spec** for
`docs/superpowers/plans/2026-11-03-review-findings-fix-all.md`. It re-verifies
every finding from the first-pass review, records what changed after
double-checking, and marks the ones that were overstated or withdrawn.

## Verification performed

| Check | Command / method | Result |
|---|---|---|
| Unit tests | `./gradlew :core:test :app:testDebugUnitTest` | 1319 tests, 0 failures |
| Debug build | `./gradlew :app:assembleDebug` | passes |
| Lint | `./gradlew :app:lintDebug` | 241 warnings, 1 hint; 3 errors + 22 warnings baseline-filtered; 173 baseline entries stale |
| Runtime msgpack | `./gradlew :app:dependencies --configuration debugRuntimeClasspath` | `org.msgpack:msgpack-core:0.9.8` |
| msgpack advisory | OSV `GHSA-cw39-r4h6-8j3x` / `CVE-2026-21452` | 0.9.8 is in the affected range (`introduced 0`, `fixed 0.9.11`) |
| Declared-length probe | `javac`/`java` against msgpack 0.9.8 | `unpackBinaryHeader()` returned `2147483632` for a 7-byte payload |
| `unpackString` probe | `java -Xmx128m` | `MessageInsufficientBufferException` — 0.9.8 checks `available()` before allocating |
| Fork bytecode | `javap -c -p` on `rns-core-0.1.0-1a7f6193.jar` | `Link.processResponse`: `unpackBinaryHeader()` → `newarray byte` → `readPayload`, no bound |
| Fork build source | `.github/scripts/build-forks.sh` | pins live on a LAN-only forgejo (`192.168.200.121:3333`) |
| Crash tooling | grep for Crashlytics/Sentry/Bugsnag/Firebase + mapping upload | none |

## Severity scale

**High** = remotely or peer-triggerable availability/correctness impact, no
funds moved. **Medium** = real defect or missing gate with bounded impact.
**Low** = hygiene or defence-in-depth. **Nit** = naming/formatting.

---

## High

### H-1 — Unauthenticated OOM via the delivery-announce parser
**Location:** `android/core/src/main/kotlin/com/neop2p/data/p2p/RnsSession.kt:1340-1342`
**Status:** confirmed, **narrowed** to the delivery announce.

```kotlin
val nameLen = unpacker.unpackBinaryHeader()   // 0x7ffffff0 is accepted
val nameBytes = ByteArray(nameLen)            // allocated before any payload read
unpacker.readPayload(nameBytes)
```

`handlePeerAnnounce` runs for every `lxmf.delivery` announce. Any RNS identity
can announce; the declared `bin32` length is independent of the actual payload
size. `javac` probe: the header returned `2147483632` from a 7-byte payload, so
the `ByteArray` allocation is a ~2 GB request. On Android this is an
`OutOfMemoryError`, which is an `Error` — the surrounding
`catch (e: Exception)` does not catch it, so the process dies.

**Correction from the first review:** `handleIdentityAnnounce`
(`RnsSession.kt:1280-1305`) uses `unpackString()`, which in msgpack 0.9.8 checks
`available()` first (proven: `MessageInsufficientBufferException`). That path is
**not** exploitable. Only the delivery announce needs the bound.

### H-2b — Unbounded declared-length allocation inside the pinned RNS fork
**Location:** `rns-core-0.1.0-1a7f6193.jar`, class
`network.reticulum.link.Link`, `processResponse`.
**Status:** confirmed by bytecode; **new** in this pass.

```
unpackArrayHeader()
unpackBinaryHeader()  -> int
newarray byte         -> ByteArray(declared length)
readPayload(...)
```

The attacker must be the link peer (the payload is inside the encrypted link
response), so this is peer-triggered, not broadcast. Upgrading msgpack does
**not** fix it: the allocation is performed by fork code, not the library.
The same `unpack header → newarray` pattern exists in `LXMRouter`, `LXMessage`,
`Transport`, `Identity`, and `Destination` classes; `Link` is the verified
instance.

### H-3 — Unbounded `prevChainLength` loop in the Double Ratchet
**Location:** `android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet/DoubleRatchet.kt:193`
**Status:** confirmed.

```kotlin
while (count < header.prevChainLength) { ... skipped[...] = mk ... }
```

`prevChainLength` is an 8-byte wire field parsed before AEAD verification
(`RatchetEnvelope.readHeader`). `MAX_SKIPPED` / `MAX_SKIP_PER_CHAIN` are
enforced only in `RatchetReplayWindow.decide`, not here. A chat peer can send a
new `dhPub` with `prevChainLength = Long.MAX_VALUE`; the receiver derives keys
and grows `skipped` without bound → ANR/OOM. The loop runs when the receive
chain is non-empty (any established session); the ratchet state is discarded if
decryption later fails, but the loop runs first.

---

## Medium

### M-1 — Second, unscoped `PeerRegistry` (was H-4)
**Location:** `android/app/src/main/java/com/neop2p/di/AppModule.kt:235`
**Status:** confirmed; downgraded High → Medium (functional, no funds).

`provideOfferRouter(...)` calls `providePeerRegistry()` directly instead of
injecting the scoped instance. `PeerRegistry` is a stateful `@Singleton`
(`MutableStateFlow` maps). `OfferRouter` writes `recordPeerSeen(...)` at three
sites (`OfferRouter.kt:136, 326, 624`) into an orphan registry that the
orchestrator and UI never read, so multiaddrs/quality learned from the offer
feed are lost.

### M-2 — `msgpack-core:0.9.8` on the runtime classpath (was H-2)
**Location:** transitive from `rns-core` / `lxmf-core`
**Status:** confirmed vulnerable dependency; **documented CVE trigger not
reached by our code**.

OSV/GHSA lists 0.9.8 as affected. The advisory trigger is
`ExtensionValue.getData()`; neither `app` nor `core` calls `getData()` or
`unpackValue()`. The fork calls `unpackValue()` only in `Link`, and that code
does not call `getData()`. Upgrade is still required for hygiene and because a
future fork change could expose it — but it is not an independently exploitable
path today. H-2b is the reachable allocation issue.

### M-3 — `createSession` is not serialized; one process-wide init header
**Location:** `android/app/src/main/java/com/neop2p/data/p2p/SignalProtocol.kt:214-316, 350-353`; callers `P2POrchestrator.kt:182, 239`
**Status:** confirmed concurrency defect.

`createSession` is **not** under `ratchetLock` (only `encrypt`, `decrypt`, and
`handleRatchetInit` are). It is reachable from two coroutines at once: the
envelope collector (`handlePreKeyBundle → establishPreKeySession`) and the
`peerSeen` collector (`flushDeferredPreKeyBundles`, `P2POrchestrator.kt:716-720`),
plus the sweep (`:1141`). `pendingInitHeader` is a single `@Volatile` slot, so
interleaved handshakes overwrite each other's header and the persisted ratchet
state can mismatch the header that was sent → the session never converges.
`SignalProtocol.sessions` is a plain `mutableMapOf` that is written and cleared
but never read (dead state).

### M-4 — Migration tests never run in CI
**Location:** `android/app/src/androidTest/java/com/neop2p/data/local/MigrationBoundariesTest.kt`
**Status:** confirmed.

CI runs `:app:testDebugUnitTest` + `:core:test` only. The six instrumentation
tests (including all 33 Room migration boundaries) never execute. A bad
migration is a data-loss class defect on money rows.

### M-5 — Lint baseline is stale; release lint disabled; no new-warning gate
**Location:** `android/app/build.gradle.kts:116-121`, `android/app/lint-baseline.xml`
**Status:** confirmed, with a correction.

The baseline header is `lint 8.7.3` while the build runs AGP 9.4.1; 173 entries
no longer match, and the baseline itself records that a Compose lint registry
was skipped. `checkReleaseBuilds = false`; no detekt/ktlint. The previous
review's claim that "Compose lint is entirely skipped" is **withdrawn** — current
lint still reports `ModifierParameter` etc. The accurate finding is: stale
baseline + no gate on new warnings + release lint off.

### M-6 — DB salt persisted with `apply()` on a new install
**Location:** `android/app/src/main/java/com/neop2p/data/local/SqlCipherPassphraseManager.kt:79-88`
**Status:** confirmed.

On a brand-new install the random `db_salt_v1` is written asynchronously. A
process death before the disk write leaves a DB encrypted with a salt that no
longer exists → the DB is permanently unreadable. The codebase already treats
this class of write as synchronous elsewhere (wallet change index).

### M-7 — `runBlocking` DB open on the caller thread
**Location:** `android/app/src/main/java/com/neop2p/data/local/AppDatabase.kt:495-521`; provider `di/AppModule.kt:48-52`
**Status:** confirmed; impact **bounded** (first open only) and needs measurement.

`getInstance` wraps KeyStore + SQLCipher passphrase derivation +
`Room.databaseBuilder(...).build()` in `runBlocking(Dispatchers.IO)` inside
`synchronized`. Hilt providers are synchronous, so the first injection on the
main thread blocks. After the passphrase is cached the cost is small; on a new
install the StrongBox attempt + key generation can be hundreds of ms.

### M-8 — God files and UI→data leakage
**Location:** `ui/screens/escrow/EscrowScreen.kt` (3572 lines, ViewModel at 2682-3572, 24 direct `escrowService.*` calls), `data/escrow/EscrowService.kt` (3543), `core/.../RnsSession.kt` (1670), `data/p2p/P2POrchestrator.kt` (1572)
**Status:** confirmed (maintainability, no current defect).

### M-9 — Dependency scan cannot fail the build
**Location:** `.github/workflows/ci.yml:156-166`
**Status:** confirmed, **worse than first assessed**.

`dependency-check/Dependency-Check_Action@main`, `continue-on-error: true`,
`fail_on_cvss: 9`. Two independent defects:

1. The action's `action.yml` exposes only `project`, `path`, `format`, `out`,
   and `args`. **`fail_on_cvss` is not an input**, so the threshold is silently
   ignored; the working form is `args: "--failOnCVSS 7"`.
2. The action runs `--scan /github/workspace/<path>`, a filesystem scanner. With
   `path: android` it scans the source tree and the checked-in
   `thirdparty-repo` jars, **not the Gradle-resolved runtime classpath**. The
   msgpack jar in the Gradle cache is never analyzed.

Fix: stage the resolved runtime classpath with a `copyRuntimeDeps` Copy task and
scan that directory, then pass `--failOnCVSS 7` in `args` (Task 8).

### M-10 — No crash reporting, no R8 mapping upload
**Location:** repo-wide; `.github/workflows/ci.yml`
**Status:** confirmed. Privacy posture is deliberate; the consequence is that
field crashes/ANRs are invisible and release stack traces are unreadable.

### M-11 — Fork sources live only on a LAN forgejo
**Location:** `.github/scripts/build-forks.sh:22-23`
**Status:** confirmed (residual REL-1).

The app artifacts are pinned and checked in, so builds are reproducible — but
the fork **sources** are unreachable outside the operator's LAN. That blocks
CI/contributors from rebuilding H-2b and is a maintainability/supply-chain risk.

---

## Low / Defence-in-depth

| ID | Location | Note |
|---|---|---|
| L-1 | `P2POrchestrator.kt:357` | `RatchetInit` is the only envelope type with no `isVerifiedSender` check. Not exploitable today (F1 pins peerId→identity before a session exists), but it is an inconsistent gate. Add it. |
| L-2 | `RatchetState.skippedKey` | `msgNum` is packed into 32 bits; a collision needs `msgNum ≥ 2^32`, reachable only while H-3 is unfixed. Add an explicit upper bound. |
| L-3 | `SignalProtocol.sessions` | Write-only map; remove. |
| L-4 | `RnsSession.kt:1283, 1337` | `MessageUnpacker` is not closed; the new `AnnounceCodec` will use `use {}`. |
| L-5 | `CoinSelector.kt:129-156` | Random/accumulative draws admit coins with non-positive effective value → suboptimal input selection, no mispayment (`build` recomputes the real fee). |
| L-6 | `CreateOfferScreen.kt:880-881, 861, 997` | `parseIdrToLong` has no upper bound; `(sats * price) / 1e8` can overflow. Creator-only; peers reject the resulting offer at ingest. Bound the price to `MAX_OFFER_PRICE`. |
| L-7 | `EscrowCltvGate.kt:22-36` | Parameter `maturityMs` is compared in seconds (`/1000`); correct but easy to misread. Rename/document units. |

## Withdrawn from the first review

- **"Compose lint entirely skipped"** — overstated; see M-5.
- **"`BootReceiver` is exported and unvalidated"** — `shouldRearm(action)`
  rejects anything but `ACTION_BOOT_COMPLETED`; no change needed.
- **"`skippedKey` collision is exploitable"** — only via the H-3 loop; fix H-3
  and add the bound (L-2).

## Carry-over from the 2026-09-13 audit (re-verified)

| Finding | Status |
|---|---|
| REL-1 — SNAPSHOT/LAN fork deps | **Partially fixed**: artifacts pinned + checked in; fork **sources** still LAN-only (M-11) |
| SEC-7 — msgpack 0.9.8 | **Still present**; downgraded to M-2 (trigger not reached); fork allocation is H-2b |
| ARCH-2 — second `PeerRegistry` | **Still present** (M-1) |
| DATA-1 / TEST-1 — schema export / tests | Schema exported (`schemas/33.json`); migration tests still not in CI (M-4) |
| CI-1 / CI-2 — no release pipeline / scan non-blocking | Still present (M-9, M-10) |
| ARCH-3 — god files | Still present, worse (M-8) |
| SEC-3 / SEC-5 / SEC-8 / PRIV-4 / REL-5 / CI-5 | Fixed since the audit (ratchet, log hygiene, bitcoinj 0.17.1, backup rules, BootReceiver, versionCode automation) |

## Verified strengths (unchanged)

The on-chain money path remains the strongest part of the app: attested +
script-anchored destinations, authenticated `escrow_status`, creator-protected
status, and a pre-broadcast `ReleaseIntegrity` gate at both sign and broadcast.
Money is integer-only, ingest bounds sats/fiat/price, and the 1319-test suite is
green. Nothing in this review moves funds; the High findings are availability
and state-convergence defects.

## Reviewer analysis disposition (2026-11-03)

An independent reviewer (`ollama-cloud/beam`, variant `max`) reviewed the plan.
Every claim below was re-verified against the source/jars before acceptance.

### Accepted and applied
- **Task 3/Task 15 ordering:** Task 3 has a hard LAN-forge dependency; Task 15
  cannot unblock it because the mirror source is the same forge. The plan now
  states that explicitly instead of a circular “do Task 15 first” instruction.
- **Task 6:** do not hold `ratchetLock` across `_sessionEstablished.emit`; the
  locked helper mutates/persists, the wrapper emits. Plan restructured.
- **Task 8:** the action is a filesystem scanner and `fail_on_cvss` is not an
  input; stage the resolved classpath and use `args: "--failOnCVSS 7"`.
- **Task 10:** the `NewApi` guard is now inside `buildSpec` (same method as the
  call) instead of an annotation on a split helper, which lint can actually
  follow.
- **Task 12:** warming narrows but does not remove the synchronous provider
  contract; acceptance reworded to a measured target + explicit follow-up.
- **Task 13:** replaced the indented heredoc with `printf`.
- **Task 14:** added P2PKH/P2SH/bech32 redaction test coverage.
- **Task 4:** added `prevChainLength < MAX_MESSAGE_NUMBER` as defence-in-depth.
- **Task 1:** exact array-header bounds and an unambiguous
  `newDefaultBufferPacker()` in the test helper.

### Rejected with evidence
- **“MessagePack.newDefaultPacker() has no overload” (Critical).** False.
  `javap` on 0.9.12 shows `newDefaultPacker(java.io.OutputStream)` and
  `newDefaultBufferPacker()`; the existing `RnsSessionBindingTest.packBinding`
  uses the `OutputStream` form. The plan now names the buffer packer only for
  clarity.
- **“Task 9 migration tests hit `AppDatabase`/`DebugNetworkGate`.”** False.
  `MigrationBoundariesTest` calls `LegacySchema.open(...)` on a raw SQLite
  database and never launches an Activity or the Hilt graph.
- **“Task 10 `NewApi` is the wrong method.”** False. The baseline entry is
  `setIsStrongBoxBacked` at `SqlCipherPassphraseManager.kt:84`.
- **“`MAX_OFFER_PRICE` may not exist.”** False. `NeoP2PConfig.kt:85`.
- **“Crash regex misses bech32.”** False. The plan already listed `bc1`/`tb1`
  patterns; only the P2PKH/P2SH test coverage was missing.
- **“Missing transport-ingest bounds task.”** Not needed for the checked
  codecs: `PreKeyBundleCodec.readBytes`, `EnvelopeCodec.readBytes`, and
  `RatchetCodec` all gate on `available()`; evidence is capped at 80 KiB of
  base64 (`ArbitrationIngest.MAX_EVIDENCE_BASE64_CHARS`).
