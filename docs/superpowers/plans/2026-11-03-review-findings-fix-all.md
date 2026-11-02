# NEO-P2P Review-Findings Remediation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every confirmed finding from the 2026-11-03 review — three High availability/state defects, eight Medium release-readiness gaps, and the Low/nit hardening items — without touching the money-path semantics.

**Architecture:** Phase A fixes three remotely/peer-triggerable allocation bugs and two state-convergence defects at their parse/ingest/session boundaries. Phase B puts the missing CI and release gates in place. Phase C is maintainability work that must land in a separate PR (no bundle with the release fixes). Every behavioural fix starts with a failing pure-JVM test in `:core` (or an existing test seam) before the production change.

**Tech Stack:** Kotlin 2.4.20, AGP 9.4.1, Gradle 9.7.1, JDK 21, JUnit 4, Room/SQLCipher, msgpack 0.9.12, RNS/LXMF fork jars.

**Spec:** `docs/audits/2026-11-03-review-findings.md`

## Global Constraints

- Run every Gradle command from `android/`; the repo root is not a Gradle project.
- Never edit `legacy/`; it is dead KMM code outside every build.
- Money stays integer-only: `fee = sats * FEE_NUM / FEE_DEN`, fiat `= sats * price / 100_000_000`; no `Double` round-trip on a money value.
- `NETWORK` invariant: `main` = `testnet`; this branch (`v0.3.0`) = `mainnet`. Do not change it in these tasks.
- `versionName` is `0.3.0`; `versionCode` is the git commit count. Do not hand-edit either.
- One commit per task, conventional-commit subject, no Co-Authored-By.
- No new runtime dependency without a pinned version and `:app:dependencies` evidence in the commit body.
- Receiver-side hardening must not change the wire format. H-1/H-3 fixes reject malformed input; they do not alter what an honest peer sends.
- Before every commit: `./gradlew :app:testDebugUnitTest :core:test` (plus `:app:assembleDebug` for any `:app` change, `:app:lintDebug` for UI/build changes).

## Review Focus

1. A hostile announce declaring a 2 GB binary payload is rejected **before allocation** and the process survives (Task 1).
2. A ratchet header with `prevChainLength = Long.MAX_VALUE` (or `msgNum > 2^32`) is rejected in under 100 ms with the session state unchanged (Task 4).
3. A pre-key bundle arriving while the `peerSeen` collector flushes the same peer produces exactly one session and the header that matches the persisted state (Task 6).
4. A cold start dispatches the SQLCipher open off the main thread and the measured `db open` completes before the first screen needs it; a warm start shows no StrictMode disk-read violation. If the first-access path still blocks, record it as an explicit async-DB follow-up rather than claiming the gate is closed (Task 12).
5. A new lint warning or a runtime dependency at CVSS ≥ 7 fails CI (Tasks 8 and 10).

---

# Phase A — Security hardening (release-blocking)

### Task 1: Bound every announce length before allocation

**Files:**
- Create: `android/core/src/main/kotlin/com/neop2p/data/p2p/AnnounceCodec.kt`
- Create: `android/core/src/test/kotlin/com/neop2p/data/p2p/AnnounceCodecTest.kt`
- Modify: `android/core/src/main/kotlin/com/neop2p/data/p2p/RnsSession.kt:1280-1305` (`handleIdentityAnnounce`)
- Modify: `android/core/src/main/kotlin/com/neop2p/data/p2p/RnsSession.kt:1334-1384` (`handlePeerAnnounce`)

**Interfaces:**
- Produces: `object AnnounceCodec` with `const val MAX_PEER_ID_BYTES = 128`, `const val MAX_IDENTITY_FIELD_BYTES = 256`, `data class IdentityFields(peerId: String, pubKeyHex: String, signatureHex: String)`, `fun peerIdFromDelivery(appData: ByteArray): String?`, `fun identityFields(appData: ByteArray): IdentityFields?`.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write the failing tests**

```kotlin
// AnnounceCodecTest.kt
class AnnounceCodecTest {
    private fun deliveryPayload(peerId: ByteArray): ByteArray = buildList {
        add(0x92.toByte()); add(0xc6.toByte()) // array(2), bin32
        add((peerId.size ushr 24).toByte()); add((peerId.size ushr 16).toByte())
        add((peerId.size ushr 8).toByte()); add(peerId.size.toByte())
        addAll(peerId.toList()); add(0xc0.toByte()) // nil stamp cost
    }.toByteArray()

    @Test fun `valid delivery announce returns the peer id`() {
        assertEquals("12D3KooWTest", AnnounceCodec.peerIdFromDelivery(deliveryPayload("12D3KooWTest".toByteArray())))
    }

    @Test fun `oversized declared length is rejected without allocating`() {
        val p = byteArrayOf(0x92, 0xc6.toByte(), 0x7f, 0xff.toByte(), 0xff.toByte(), 0xf0.toByte(), 0x41)
        val started = System.nanoTime()
        assertNull(AnnounceCodec.peerIdFromDelivery(p))
        assertTrue("must reject before allocating", (System.nanoTime() - started) / 1_000_000 < 50)
    }

    @Test fun `peer id over the cap is rejected`() {
        assertNull(AnnounceCodec.peerIdFromDelivery(deliveryPayload(ByteArray(129) { 'a'.code.toByte() })))
    }

    @Test fun `truncated payload is rejected`() {
        assertNull(AnnounceCodec.peerIdFromDelivery(byteArrayOf(0x92, 0xc6.toByte(), 0, 0, 0, 10, 0x41)))
    }

    @Test fun `identity fields parse`() {
        val fields = AnnounceCodec.identityFields(bindingPayload("peer", "ab", "cd"))
        assertEquals(AnnounceCodec.IdentityFields("peer", "ab", "cd"), fields)
    }

    @Test fun `oversized identity field is rejected`() {
        assertNull(AnnounceCodec.identityFields(bindingPayload("peer", "ab", "c".repeat(257))))
    }
}
```

Use `MessagePack.newDefaultBufferPacker()` for `bindingPayload` (`packArrayHeader(3); packString(peerId); packString(pub); packString(sig)`), matching `RnsSessionBindingTest.packBinding`. (`newDefaultPacker(OutputStream)` also exists, but the buffer packer is the unambiguous one-arg form.)

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.AnnounceCodecTest'`
Expected: compile failure — `AnnounceCodec` does not exist.

- [ ] **Step 3: Implement `AnnounceCodec`**

```kotlin
object AnnounceCodec {
    const val MAX_PEER_ID_BYTES = 128
    const val MAX_IDENTITY_FIELD_BYTES = 256

    data class IdentityFields(val peerId: String, val pubKeyHex: String, val signatureHex: String)

    fun peerIdFromDelivery(appData: ByteArray): String? = runCatching {
        MessagePack.newDefaultUnpacker(appData).use { u ->
            val size = u.unpackArrayHeader()
            if (size !in 1..2) return null
            if (u.tryUnpackNil()) return null
            readBinaryString(u, MAX_PEER_ID_BYTES)?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    fun identityFields(appData: ByteArray): IdentityFields? = runCatching {
        MessagePack.newDefaultUnpacker(appData).use { u ->
            if (u.unpackArrayHeader() != 3) return null
            val peerId = readString(u, MAX_PEER_ID_BYTES) ?: return null
            val pub = readString(u, MAX_IDENTITY_FIELD_BYTES) ?: return null
            val sig = readString(u, MAX_IDENTITY_FIELD_BYTES) ?: return null
            IdentityFields(peerId, pub, sig)
        }
    }.getOrNull()

    private fun readBinaryString(u: MessageUnpacker, max: Int): String? {
        val len = u.unpackBinaryHeader()
        if (len < 0 || len > max) return null
        val bytes = ByteArray(len)
        u.readPayload(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readString(u: MessageUnpacker, max: Int): String? {
        val len = u.unpackRawStringHeader()
        if (len < 0 || len > max) return null
        val bytes = ByteArray(len)
        u.readPayload(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.AnnounceCodecTest'`
Expected: PASS; the oversized case returns in < 50 ms.

- [ ] **Step 5: Wire the codec into `RnsSession`**

In `handlePeerAnnounce`, replace the inline `unpackArrayHeader`/`unpackBinaryHeader`/`ByteArray(nameLen)` block with:

```kotlin
val peerId = AnnounceCodec.peerIdFromDelivery(appData) ?: return
```

Keep the existing F1 binding guard, registry writes, flush, and `_peerSeen` emission after it. In `handleIdentityAnnounce`, replace the three `unpackString()` calls with `AnnounceCodec.identityFields(appData) ?: return`; keep the `PeerBinding.verify(...)` call unchanged. Remove the now-unused `org.msgpack.core.MessagePack` / `MessageUnpacker` imports from `RnsSession.kt` (they move into `AnnounceCodec`).

- [ ] **Step 6: Run the regression suite**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.RnsSessionBindingTest' :app:assembleDebug`
Expected: PASS. Any wire-format mistake fails the binding tests.

- [ ] **Step 7: Commit**

```bash
git add android/core/src/main/kotlin/com/neop2p/data/p2p/AnnounceCodec.kt \
        android/core/src/test/kotlin/com/neop2p/data/p2p/AnnounceCodecTest.kt \
        android/core/src/main/kotlin/com/neop2p/data/p2p/RnsSession.kt
git commit -m "fix(p2p): bound announce lengths before allocation"
```

---

### Task 2: Force msgpack ≥ 0.9.11 on every runtime classpath

**Files:**
- Modify: `android/app/build.gradle.kts` (top-level `configurations.configureEach`)
- Modify: `android/core/build.gradle.kts` (same block)
- Modify: `.github/workflows/ci.yml` (new step after `Run unit tests`)

**Interfaces:**
- Produces: a runtime classpath where `org.msgpack:msgpack-core` is `0.9.12` for `:app` and `:core`.

- [ ] **Step 1: Prove the current failure**

Run: `./gradlew :app:dependencyInsight --dependency msgpack-core --configuration debugRuntimeClasspath | grep 'msgpack-core:'`
Expected: `org.msgpack:msgpack-core:0.9.8` (the vulnerable version).

- [ ] **Step 2: Add the resolution constraint**

At the top level of both `android/app/build.gradle.kts` and `android/core/build.gradle.kts` (after `plugins { }`):

```kotlin
configurations.configureEach {
    resolutionStrategy.force("org.msgpack:msgpack-core:0.9.12")
}
```

Comment why: GHSA-cw39-r4h6-8j3x / CVE-2026-21452 is fixed in 0.9.11; the app and the pinned forks must never resolve 0.9.8.

If the Gradle module metadata carries a strict constraint that wins over `force`, use `eachDependency` instead:

```kotlin
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.msgpack" && requested.name == "msgpack-core") {
            useVersion("0.9.12")
            because("CVE-2026-21452 is fixed in 0.9.11")
        }
    }
}
```

- [ ] **Step 3: Verify the resolved version**

Run: `./gradlew :app:dependencyInsight --dependency msgpack-core --configuration debugRuntimeClasspath | grep 'msgpack-core:'`
Expected: `org.msgpack:msgpack-core:0.9.12`.

- [ ] **Step 4: Run the full suite**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS. If the fork's compiled API is incompatible, the build fails here; the fork uses stable `MessagePack`/`MessageUnpacker` APIs, so this is not expected.

- [ ] **Step 5: Add the CI assertion**

```yaml
      - name: Assert msgpack is patched
        run: |
          ./gradlew :app:dependencyInsight --dependency msgpack-core \
            --configuration debugRuntimeClasspath -q \
            | grep -q 'msgpack-core:0.9.12'
```

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle.kts android/core/build.gradle.kts .github/workflows/ci.yml
git commit -m "build(deps): force msgpack-core 0.9.12 (CVE-2026-21452)"
```

---

### Task 3: Bound declared-length allocations inside the pinned RNS fork

**Files:**
- Modify (fork repo `reticulum-kt`, branch from pin `1a7f6193`): `rns-core/src/main/kotlin/network/reticulum/link/Link.kt` and every other `unpackBinaryHeader`/`unpackRawStringHeader` → `newarray` site (`LXMRouter`, `LXMessage`, `Transport`, `Identity`, `Destination`).
- Modify: `.github/scripts/build-forks.sh` (new pins)
- Modify: `android/gradle/libs.versions.toml` (`rns-core`, `rns-interfaces`, `lxmf-core` versions)
- Modify: `android/thirdparty-repo/**` (regenerated artifacts)

**Interfaces:**
- Produces: fork guard `MsgPackLimits.requireDeclaredLength(len: Int, what: String)`; new pinned artifact versions; no app API change.

**Precondition:** the fork sources live on the LAN forge (`http://192.168.200.121:3333/thesdony/reticulum-kt.git`). Task 3 requires it to be reachable (clone → patch → push → rebuild). If it is not, stop and schedule this task on the operator's machine; Task 15 (mirror) cannot unblock it because the mirror source is the same forge.

- [ ] **Step 1: Reproduce the defect against the fork**

```bash
git clone http://192.168.200.121:3333/thesdony/reticulum-kt.git /tmp/opencode/reticulum-kt
git -C /tmp/opencode/reticulum-kt checkout 1a7f61937b2200aa98a646e592cd208b016d6277
git -C /tmp/opencode/reticulum-kt switch -c fix/bound-declared-lengths
```

`javap -c -p` on the built jar shows `Link.processResponse`: `unpackBinaryHeader()` → `newarray byte` → `readPayload`, with no bound.

- [ ] **Step 2: Add the guard and a failing fork test**

Create `rns-core/src/main/kotlin/network/reticulum/common/MsgPackLimits.kt`:

```kotlin
object MsgPackLimits {
    const val MAX_DECLARED_BYTES = 1_048_576 // 1 MiB; no legitimate link field is larger
    fun requireDeclaredLength(len: Int, what: String) {
        require(len in 0..MAX_DECLARED_BYTES) { "$what declared length $len exceeds $MAX_DECLARED_BYTES" }
    }
}
```

Test: build a link response whose first field declares `0x7ffffff0`, call the parser, assert `IllegalArgumentException` in < 100 ms.

- [ ] **Step 3: Apply the guard at every allocation site**

At each `unpackBinaryHeader()` / `unpackRawStringHeader()` followed by `ByteArray(len)` / `newarray`, insert `MsgPackLimits.requireDeclaredLength(len, "<field name>")` before the allocation. Do not replace the protocol length checks; add the cap.

- [ ] **Step 4: Run the fork tests**

Run: `cd /tmp/opencode/reticulum-kt && ./gradlew :rns-core:test :rns-interfaces:test`
Expected: PASS, including the new oversized-length test.

- [ ] **Step 5: Push the fork commit and bump the pins**

```bash
git -C /tmp/opencode/reticulum-kt push origin fix/bound-declared-lengths
NEW_PIN=$(git -C /tmp/opencode/reticulum-kt rev-parse HEAD)
# update RETICULUM_PIN + RETICULUM_VERSION in .github/scripts/build-forks.sh
# update the pin comment at the top of the script
```

- [ ] **Step 6: Regenerate the checked-in Maven repo**

```bash
cd /home/thesdony/neop2p-reticulum
.github/scripts/build-forks.sh
# verifies: no 0.1.0-SNAPSHOT remains
```

- [ ] **Step 7: Bump the version catalog and verify the app**

Update `rns-core = "0.1.0-<new8>"`, `rns-interfaces = ...`, and rebuild `lxmf-core` against the new `rns-core` (the script does this). Then:

Run: `cd android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS with the new pins.

- [ ] **Step 8: Commit**

```bash
git add .github/scripts/build-forks.sh android/gradle/libs.versions.toml android/thirdparty-repo
git commit -m "fix(deps): bound declared-length allocations in the RNS fork"
```

---

### Task 4: Bound the Double Ratchet's chain lengths and message numbers

**Files:**
- Modify: `android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet/DoubleRatchet.kt:187-211` (`receiveRatchet`)
- Modify: `android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet/RatchetReplayWindow.kt:22-35`
- Modify: `android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet/RatchetState.kt` (add `MAX_MESSAGE_NUMBER`)
- Test: `android/core/src/test/kotlin/com/neop2p/data/p2p/ratchet/DoubleRatchetSymmetricTest.kt`
- Test: `android/core/src/test/kotlin/com/neop2p/data/p2p/ratchet/RatchetReplayWindowTest.kt`

**Interfaces:**
- Produces: `RatchetState.MAX_MESSAGE_NUMBER = 0xFFFF_FFFFL`; `receiveRatchet` rejects a `prevChainLength` gap above `MAX_SKIP_PER_CHAIN` and a total skipped store above `MAX_SKIPPED`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `huge prevChainLength is rejected without deriving keys`() {
    val (alice, bob) = establishedPair()
    val forged = RatchetHeader(ByteArray(32) { 7 }, 0, Long.MAX_VALUE)
    val env = RatchetEnvelope.encode(forged, ByteArray(16))
    val started = System.nanoTime()
    assertThrows(IllegalStateException::class.java) {
        DoubleRatchet.decrypt(bob, "s", "alice", "offer", env)
    }
    assertTrue((System.nanoTime() - started) / 1_000_000 < 100)
}

@Test fun `prevChainLength just over the skip budget is rejected`() {
    val (_, bob) = establishedPair()
    val forged = RatchetHeader(ByteArray(32) { 9 }, 0, RatchetState.MAX_SKIP_PER_CHAIN + 1)
    assertThrows(IllegalStateException::class.java) {
        DoubleRatchet.decrypt(bob, "s", "alice", "offer", RatchetEnvelope.encode(forged, ByteArray(16)))
    }
}

@Test fun `message numbers above 32 bits are rejected`() {
    assertEquals(
        RatchetReplayWindow.Decision.Reject("message_number_too_large"),
        RatchetReplayWindow.decide(recvCount = 0, msgNum = RatchetState.MAX_MESSAGE_NUMBER + 1,
            hasSkippedKey = false, skippedSize = 0)
    )
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.ratchet.DoubleRatchetSymmetricTest' --tests 'com.neop2p.data.p2p.ratchet.RatchetReplayWindowTest'`
Expected: the huge-length test hangs/fails; the message-number test fails with `InOrder`/`SkipAhead`.

- [ ] **Step 3: Implement the bounds**

In `receiveRatchet`, before the loop (use `check`, not `require`, so the
public `decrypt` contract keeps throwing `IllegalStateException` like the
existing replay rejection):

```kotlin
check(header.prevChainLength >= 0) { "negative prevChainLength" }
check(header.prevChainLength < RatchetState.MAX_MESSAGE_NUMBER) {
    "prevChainLength ${header.prevChainLength} exceeds the message-number bound"
}
val toSkip = header.prevChainLength - state.recvCount
check(toSkip <= RatchetState.MAX_SKIP_PER_CHAIN) {
    "prevChainLength gap $toSkip exceeds ${RatchetState.MAX_SKIP_PER_CHAIN}"
}
check(skipped.size + toSkip.coerceAtLeast(0) <= RatchetState.MAX_SKIPPED) {
    "skipped-key store would exceed ${RatchetState.MAX_SKIPPED}"
}
```

The loop only runs when the receive chain is non-empty (any established session); the guards run unconditionally, so the empty-chain/initial-ratchet path is also bounded.

In `RatchetReplayWindow.decide`, after the negative check:

```kotlin
if (msgNum > RatchetState.MAX_MESSAGE_NUMBER) return Decision.Reject("message_number_too_large")
```

- [ ] **Step 4: Run the tests and the full ratchet suite**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.ratchet.*'`
Expected: PASS, including the existing out-of-order/skip tests (they use small gaps).

- [ ] **Step 5: Commit**

```bash
git add android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet \
        android/core/src/test/kotlin/com/neop2p/data/p2p/ratchet
git commit -m "fix(e2ee): bound ratchet chain lengths and message numbers"
```

---

### Task 5: Inject the scoped `PeerRegistry` into `OfferRouter`

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/di/AppModule.kt:226-236`

**Interfaces:**
- Consumes: `PeerRegistry` (`@Singleton`, `app/src/main/java/com/neop2p/data/p2p/store/PeerRegistry.kt`).
- Produces: `provideOfferRouter(..., peerRegistry: PeerRegistry, ...)` — the parameter is required, so the orphan call cannot compile back in silently.

- [ ] **Step 1: Change the provider**

Add `peerRegistry: com.neop2p.data.p2p.store.PeerRegistry` to `provideOfferRouter` and pass it to `OfferRouter(...)`; delete the direct `providePeerRegistry()` call.

- [ ] **Step 2: Verify the Dagger graph**

Run: `./gradlew :app:assembleDebug`
Expected: PASS. A missing binding fails at compile time.

- [ ] **Step 3: Verify the shared instance at runtime**

Add a debug-only log in `OfferRouter.recordPeerSeen` call sites? No. Instead, run the existing orchestration test that exercises offer ingest, or inspect the Hilt graph:

Run: `./gradlew :app:dependencyInsight --dependency peer-registry` is not applicable (local class). Use `grep -n 'providePeerRegistry' android/app/src/main/java/com/neop2p/di/AppModule.kt` and confirm exactly one direct call remains (the `@Provides` body itself).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/di/AppModule.kt
git commit -m "fix(di): inject the singleton PeerRegistry into OfferRouter"
```

---

### Task 6: Serialize `createSession` and scope the init header per peer

**Files:**
- Create: `android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet/PendingInitHeaders.kt`
- Create: `android/core/src/test/kotlin/com/neop2p/data/p2p/ratchet/PendingInitHeadersTest.kt`
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/SignalProtocol.kt:214-316, 350-357`
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt:201`

**Interfaces:**
- Produces: `class PendingInitHeaders { fun put(peerId: String, header: ByteArray); fun consume(peerId: String): ByteArray? }`; `SignalProtocol.consumePendingInitHeader(peerId: String): ByteArray?`; private `SignalProtocol.createSessionLocked(remotePeerId: String, remoteBundle: RatchetPreKeyBundle, authenticated: Boolean, verifiedIdentityHashHex: String?): Result<SignalSession>`.

- [ ] **Step 1: Write the failing map test**

```kotlin
class PendingInitHeadersTest {
    @Test fun `headers are scoped per peer`() {
        val m = PendingInitHeaders()
        m.put("a", byteArrayOf(1)); m.put("b", byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), m.consume("a"))
        assertArrayEquals(byteArrayOf(2), m.consume("b"))
        assertNull(m.consume("a"))
    }

    @Test fun `concurrent puts and consumes stay per-peer`() = runBlocking {
        val m = PendingInitHeaders()
        (1..100).map { i ->
            async(Dispatchers.Default) {
                val p = "p$i"; val h = byteArrayOf(i.toByte())
                m.put(p, h)
                assertArrayEquals(h, m.consume(p))
            }
        }.awaitAll()
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.ratchet.PendingInitHeadersTest'`
Expected: compile failure — class does not exist.

- [ ] **Step 3: Implement the class**

```kotlin
class PendingInitHeaders {
    private val headers = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    fun put(peerId: String, header: ByteArray) { headers[peerId] = header }
    fun consume(peerId: String): ByteArray? = headers.remove(peerId)
}
```

- [ ] **Step 4: Run the map test**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.ratchet.PendingInitHeadersTest'`
Expected: PASS.

- [ ] **Step 5: Wire the lock and the map**

In `SignalProtocol`:
- Replace `@Volatile var pendingInitHeader` with `private val pendingInitHeaders = PendingInitHeaders()`.
- In `createSession`, replace `pendingInitHeader = RatchetEnvelope.encodeHeader(...)` with `pendingInitHeaders.put(remotePeerId, RatchetEnvelope.encodeHeader(...))`.
- Replace `consumePendingInitHeader()` with `fun consumePendingInitHeader(peerId: String): ByteArray? = pendingInitHeaders.consume(peerId)`.
- Split `createSession` into an inner `private suspend fun createSessionLocked(...): Result<SignalSession>` (load → mutate → persist; the helper uses ordinary `return Result.failure(...)` for its early exits) and an outer wrapper:
  ```kotlin
  suspend fun createSession(...): Result<SignalSession> = withContext(Dispatchers.IO) {
      val result = ratchetLock.withPeer {
          createSessionLocked(remotePeerId, remoteBundle, authenticated, verifiedIdentityHashHex)
      }
      // Emit OUTSIDE the ratchet lock: a zero-buffer SharedFlow emit can
      // suspend until a collector drains, and no ratchet op may wait on it.
      if (result.isSuccess) {
          _sessionEstablished.emit(result.getOrThrow().remotePeerId)
      }
      result
  }
  ```
- Do not hold `ratchetLock` across `_sessionEstablished.emit`; the locked helper performs no flow emission and uses ordinary `return Result.failure(...)` for its early exits (the old `return@withContext` labels disappear).
- Delete the `sessions` field, `sessions.clear()`, the two `sessions[...] = ...` writes, and `sessions.remove(peerId)` — the map is never read.
- In `P2POrchestrator.kt:201`, call `signal.consumePendingInitHeader(peerId)`.

- [ ] **Step 6: Run the app tests and build**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS. The handshake tests (`DoubleRatchetEndToEndTest`, `ChatSessionBindingGateTest`) are the behavioural gate.

- [ ] **Step 7: Commit**

```bash
git add android/core/src/main/kotlin/com/neop2p/data/p2p/ratchet/PendingInitHeaders.kt \
        android/core/src/test/kotlin/com/neop2p/data/p2p/ratchet/PendingInitHeadersTest.kt \
        android/app/src/main/java/com/neop2p/data/p2p/SignalProtocol.kt \
        android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt
git commit -m "fix(e2ee): serialize session creation and scope init headers"
```

---

### Task 7: Gate `RatchetInit` on the verified sender binding

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt:357`
- Test: `android/core/src/test/kotlin/com/neop2p/data/p2p/IngestGatesTest.kt` (`SignalingSenderGate` lives in `core/.../IngestGates.kt:35`)

**Interfaces:**
- Consumes: `rnsTransport.isVerifiedSender(peerId, senderDestHash)` (already used by `offer`/`offer_status`/chat).
- Produces: `RatchetInit` is dropped for an unverified sender, with a warning log.

- [ ] **Step 1: Write the gate regression test**

Add to `android/core/src/test/kotlin/com/neop2p/data/p2p/IngestGatesTest.kt`: (this pins the helper contract the wiring relies on; the wiring itself is compile-checked in Step 3)

```kotlin
@Test fun `ratchet init requires a verified sender`() {
    assertNull(SignalingSenderGate.authorOf(verifiedForSender = false, fromPeerId = "peer"))
    assertEquals("peer", SignalingSenderGate.authorOf(verifiedForSender = true, fromPeerId = "peer"))
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :core:test --tests 'com.neop2p.data.p2p.IngestGatesTest'`
Expected: PASS (`SignalingSenderGate` already behaves this way); the test pins the contract used by the wiring below.

- [ ] **Step 3: Wire the gate**

Replace `is AppMessage.RatchetInit -> signal.handleRatchetInit(msg.from, msg.headerBytes)` with:

```kotlin
is AppMessage.RatchetInit -> {
    if (!rnsTransport.isVerifiedSender(msg.from, env.senderDestHash)) {
        Log.w(TAG, "Dropping ratchet_init from unverified sender ${msg.from}")
        return@collect
    }
    signal.handleRatchetInit(msg.from, msg.headerBytes)
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/p2p/P2POrchestrator.kt \
        android/core/src/test/kotlin/com/neop2p/data/p2p/IngestGatesTest.kt
git commit -m "fix(p2p): require a verified sender for ratchet_init"
```

---

### Task 8: Make the dependency scan blocking and pinned

**Files:**
- Modify: `android/app/build.gradle.kts` (new `copyRuntimeDeps` task)
- Modify: `.github/workflows/ci.yml:150-166`

**Interfaces:**
- Produces: a `security-audit` job that scans the **resolved runtime classpath**, fails on CVSS ≥ 7, uses a pinned action SHA, and uploads SARIF.

- [ ] **Step 1: Look up the action SHA**

Run: `gh api repos/dependency-check/Dependency-Check_Action/commits/main --jq .sha`
Record the 40-char SHA and the release tag it belongs to.

- [ ] **Step 2: Make the scan see the real dependency graph**

The action's `action.yml` runs `--scan /github/workspace/<path>`; it is a filesystem scanner, not a Gradle resolver. Pointing it at `android` misses every jar in the Gradle cache. Add to `android/app/build.gradle.kts`:

```kotlin
val copyRuntimeDeps by tasks.registering(Copy::class) {
    from(configurations.named("debugRuntimeClasspath").map { cfg ->
        cfg.filter { it.name.endsWith(".jar") }
    })
    into(layout.buildDirectory.dir("dependency-scan"))
}
```

`configurations.named` is lazy: AGP creates the variant configurations during
`afterEvaluate`, so an eager `getByName` in the script body would throw.

Then the CI step resolves and stages the actual jars before scanning:

```yaml
      - name: Stage resolved runtime dependencies
        run: ./gradlew :app:copyRuntimeDeps
      - name: Dependency scan (blocking)
        uses: dependency-check/Dependency-Check_Action@<SHA> # <tag>
        with:
          project: NEO-P2P
          path: android/app/build/dependency-scan
          format: SARIF
          out: reports
          args: "--failOnCVSS 7"
      - name: Upload SARIF
        if: always()
        uses: github/codeql-action/upload-sarif@<SHA> # v3
        with:
          sarif_file: reports/dependency-check-report.sarif
```

`fail_on_cvss` is **not an input of this action** (its `action.yml` exposes only `project`, `path`, `format`, `out`, `args`); the existing `fail_on_cvss: 9` is silently ignored. `--failOnCVSS 7` in `args` is the working form.

Add `permissions: { contents: read, security-events: write }` at job level, remove `continue-on-error`, and add a weekly `schedule:` trigger.

- [ ] **Step 3: Validate the workflow syntax**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"`
Expected: no exception.

- [ ] **Step 4: Verify on a PR**

Push the branch and confirm the job runs and reports the remaining findings (msgpack must be gone after Task 2; triage anything else before merge).

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci(security): pin and enforce the dependency scan"
```

---

# Phase B — Release readiness

### Task 9: Run the Room migration tests in CI

**Files:**
- Modify: `.github/workflows/ci.yml` (new `migration-tests` job)

**Interfaces:**
- Produces: a job that runs `MigrationBoundariesTest` on an emulator for every PR to `main`.

- [ ] **Step 1: Confirm the test class and runner**

Run: `grep -n 'class MigrationBoundariesTest\|@RunWith' android/app/src/androidTest/java/com/neop2p/data/local/MigrationBoundariesTest.kt`
Expected: `AndroidJUnit4` runner and the class name used below.

- [ ] **Step 2: Add the job**

```yaml
  migration-tests:
    name: Room migrations (emulator)
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          target: google_apis
          arch: x86_64
          script: >-
            ./gradlew :app:connectedDebugAndroidTest
            -Pandroid.testInstrumentationRunnerArguments.class=com.neop2p.data.local.MigrationBoundariesTest
```

Run only the migration class: the debug build on `v0.3.0` is `mainnet`, and `DebugNetworkGate` would block any Activity-launching smoke test.

- [ ] **Step 3: Verify locally (if an emulator is available)**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.neop2p.data.local.MigrationBoundariesTest`
Expected: `BUILD SUCCESSFUL` and the migration tests in the report.

- [ ] **Step 4: Verify in CI**

Push a PR; confirm the job is green and the report artifact contains the migration tests.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: run Room migration tests on an emulator"
```

---

### Task 10: Rebuild the lint baseline and gate new warnings

**Files:**
- Modify: `android/app/build.gradle.kts:116-121`
- Modify: `android/app/src/main/java/com/neop2p/data/local/SqlCipherPassphraseManager.kt:151-167`
- Regenerate: `android/app/lint-baseline.xml`

- [ ] **Step 1: Fix the `NewApi` false positive first**

The baseline entry is `setIsStrongBoxBacked` at `SqlCipherPassphraseManager.kt:84`, but the guard currently lives in the **caller**, so lint cannot see it. Move the
guard into `buildSpec` so the call and the check are in the same method:

```kotlin
if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
    builder.setIsStrongBoxBacked(true)
}
```

`setUnlockedDeviceRequired` is already guarded inside `buildSpec`. This removes
the warning without a suppression and leaves the API-26 path unchanged
(`strongBox = true` is only requested under the same guard).

- [ ] **Step 2: Regenerate the baseline on the current toolchain**

Run: `cd android && ./gradlew :app:updateLintBaseline`
Expected: `lint-baseline.xml` loses the ~173 stale entries and reflects AGP 9.4.1.

- [ ] **Step 3: Enable the gate**

In the `lint { }` block: delete `checkReleaseBuilds = false` and add `warningsAsErrors = true`. Keep the existing `disable` list (it is a documented toolchain workaround).

- [ ] **Step 4: Verify lint passes**

Run: `./gradlew :app:lintDebug`
Expected: `BUILD SUCCESSFUL`; only baseline-suppressed issues remain.

- [ ] **Step 5: Prove the gate works**

Temporarily add `String.format("%d", 1)` to a screen, run `:app:lintDebug`, confirm it fails on `DefaultLocale`, then revert.

- [ ] **Step 6: Commit**

```bash
git add android/app/build.gradle.kts android/app/lint-baseline.xml \
        android/app/src/main/java/com/neop2p/data/local/SqlCipherPassphraseManager.kt
git commit -m "build(lint): refresh the baseline and fail on new warnings"
```

---

### Task 11: Persist the DB salt synchronously

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/data/local/SqlCipherPassphraseManager.kt:79-88`

- [ ] **Step 1: Replace `apply()` with a checked `commit()`**

```kotlin
val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
val persisted = prefs.edit()
    .putString(SALT_KEY, android.util.Base64.encodeToString(fresh, android.util.Base64.NO_WRAP))
    .commit()
check(persisted) { "Could not persist $SALT_KEY — refusing to create a DB with a lost salt" }
MessageDigest.getInstance("SHA-256").digest(ciphertext + fresh)
```

`commit()` is required here: the salt must hit disk before the DB is created with it. This mirrors the wallet change-index write-ahead rule.

- [ ] **Step 2: Verify a cold install**

Run on an emulator (fresh install): launch, then
`adb shell run-as com.neop2p.app.debug cat shared_prefs/neop2p_db_key.xml`
Expected: `db_salt_v1` present; the app opens the DB without a passphrase error on the next launch.

- [ ] **Step 3: Run the build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/data/local/SqlCipherPassphraseManager.kt
git commit -m "fix(db): persist the per-install salt before opening the database"
```

---

### Task 12: Warm the database off the main thread and measure startup

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/NeoTradeApp.kt`
- Modify: `android/app/src/main/java/com/neop2p/di/AppModule.kt:48-52` (comment only)

- [ ] **Step 1: Add an application scope and warm-up**

```kotlin
private val appScope = kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
)

override fun onCreate() {
    super.onCreate()
    // ... existing setup ...
    appScope.launch {
        runCatching { com.neop2p.data.local.AppDatabase.getInstance(this@NeoTradeApp) }
    }
}
```

Keep `AppDatabase.getInstance`'s `synchronized` + `runBlocking` as the single-flight fallback; the warm-up makes the main-thread path rare.

- [ ] **Step 2: Add a debug-only timing log**

Wrap the `getInstance` call in `AppModule.provideDatabase` with `android.os.SystemClock.elapsedRealtime()` and `Log.i("Startup", "db open ${elapsed}ms")`.

- [ ] **Step 3: Measure before and after**

Run 5 cold starts on an emulator:

```bash
adb shell am force-stop com.neop2p.app.debug
adb shell am start -W -n com.neop2p.app.debug/com.neop2p.MainActivity
adb logcat -d | grep 'Startup: db open'
```

Record `TotalTime` and the `db open` duration. Target: the `db open` line is logged from the warm-up coroutine before `MainActivity` finishes, and no line exceeds 250 ms after the first install. If a cold-start trace still shows the open on the main thread, record it as a follow-up (async DB provisioning) — the warm-up narrows the window, it does not remove the synchronous provider contract.

- [ ] **Step 4: Run the build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/NeoTradeApp.kt \
        android/app/src/main/java/com/neop2p/di/AppModule.kt
git commit -m "perf(startup): warm the database off the main thread"
```

---

### Task 13: Add a release workflow that uploads the R8 mapping

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Write the workflow**

```yaml
name: NEO-P2P Release
on:
  workflow_dispatch:
    inputs:
      version: { required: true, type: string }
jobs:
  release:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: 21, distribution: temurin }
      - name: Restore signing config
        run: |
          echo "${{ secrets.KEYSTORE_BASE64 }}" | base64 -d > "$RUNNER_TEMP/release.jks"
          printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=%s\nkeyPassword=%s\n' \
            "$RUNNER_TEMP/release.jks" \
            "${{ secrets.KEYSTORE_PASSWORD }}" \
            "${{ secrets.KEY_ALIAS }}" \
            "${{ secrets.KEY_PASSWORD }}" > android/keystore.properties
      - name: Build release
        working-directory: android
        run: ./gradlew :app:assembleRelease
      - uses: actions/upload-artifact@v4
        with:
          name: release-${{ inputs.version }}
          path: |
            android/app/build/outputs/apk/release/*.apk
            android/app/build/outputs/mapping/release/mapping.txt
            android/app/build/outputs/mapping/release/configuration.txt
```

`keystore.properties` is gitignored; the step creates it only on the runner. Add a `permissions: contents: read` block.

- [ ] **Step 2: Validate the YAML**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))"`

- [ ] **Step 3: Dry-run the release build locally**

Run: `cd android && ./gradlew :app:assembleRelease` (with your local `keystore.properties`) and confirm `app/build/outputs/mapping/release/mapping.txt` exists.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): build signed APKs and upload the R8 mapping"
```

---

### Task 14: Add a privacy-preserving local crash log

**Files:**
- Create: `android/core/src/main/kotlin/com/neop2p/data/diagnostics/CrashLogPolicy.kt`
- Create: `android/core/src/test/kotlin/com/neop2p/data/diagnostics/CrashLogPolicyTest.kt`
- Create: `android/app/src/main/java/com/neop2p/service/CrashLogHandler.kt`
- Modify: `android/app/src/main/java/com/neop2p/NeoTradeApp.kt` (install the handler)
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/settings/SettingsScreen.kt` (Diagnostics section)

**Interfaces:**
- Produces: `object CrashLogPolicy` with `const val MAX_FILES = 5`, `fun redact(text: String): String`, `fun shouldRotate(existingCount: Int): Boolean`; `CrashLogHandler.install(context)`.

- [ ] **Step 1: Write the failing policy tests**

```kotlin
@Test fun `redacts peer ids and bitcoin addresses`() {
    val s = "peer 12D3KooWabc bc1qxyz 0xdeadbeef"
    val out = CrashLogPolicy.redact(s)
    assertFalse(out.contains("12D3KooWabc"))
    assertFalse(out.contains("bc1qxyz"))
}
@Test fun `rotation keeps at most five files`() {
    assertTrue(CrashLogPolicy.shouldRotate(5))
    assertFalse(CrashLogPolicy.shouldRotate(4))
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :core:test --tests 'com.neop2p.data.diagnostics.CrashLogPolicyTest'`
Expected: compile failure.

- [ ] **Step 3: Implement the policy and handler**

`CrashLogPolicy.redact` replaces `12D3KooW\w+`, `bc1[0-9a-z]+`, `tb1[0-9a-z]+`, and `[13][a-km-zA-HJ-NP-Z1-9]{25,34}` with `<redacted>`. Add a redaction test covering one P2PKH, one P2SH, and one bech32 address (the plan's original test only covered a peerId + bech32). `CrashLogHandler` wraps the default uncaught handler, writes `filesDir/crash/<ts>.txt` (version, model, redacted stack trace), rotates to 5, then delegates. No network, no analytics.

- [ ] **Step 4: Wire the handler and the export UI**

`NeoTradeApp.onCreate` calls `CrashLogHandler.install(this)`. Settings adds a **Diagnostics** row with "Export crash logs" (share intent) and "Clear", gated behind the existing settings list; no new permission.

- [ ] **Step 5: Run the tests and build**

Run: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/core/src/main/kotlin/com/neop2p/data/diagnostics \
        android/core/src/test/kotlin/com/neop2p/data/diagnostics \
        android/app/src/main/java/com/neop2p/service/CrashLogHandler.kt \
        android/app/src/main/java/com/neop2p/NeoTradeApp.kt \
        android/app/src/main/java/com/neop2p/ui/screens/settings/SettingsScreen.kt
git commit -m "feat(diagnostics): add an opt-in local crash log"
```

---

### Task 15: Mirror the fork sources to a public remote

**Files:**
- Modify: `.github/scripts/build-forks.sh:22-23`
- Modify: `docs/audits/2026-11-03-review-findings.md` (mark M-11 fixed)

- [ ] **Step 1: Push the forks to GitHub**

```bash
# from the fork working copies
git remote add github git@github.com:<owner>/reticulum-kt.git
git push github HEAD:refs/heads/neo-p2p
git remote add github git@github.com:<owner>/LXMF-kt.git
git push github HEAD:refs/heads/neo-p2p
```

- [ ] **Step 2: Update the script URLs**

Replace `RETICULUM_REPO` / `LXMF_REPO` with the GitHub URLs and re-run `.github/scripts/build-forks.sh` to confirm the checked-in repo regenerates identically.

- [ ] **Step 3: Commit**

```bash
git add .github/scripts/build-forks.sh docs/audits/2026-11-03-review-findings.md
git commit -m "chore(build): mirror fork sources to a public remote"
```

---

# Phase C — Maintainability (separate PR; do not bundle with Phase A)

### Task 16: Extract `EscrowViewModel` from `EscrowScreen.kt`

**Files:**
- Create: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowViewModel.kt`
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt:2682-3572` (delete the class)

- [ ] **Step 1: Move the class verbatim**

Cut `class EscrowViewModel @Inject constructor(...)` through the end of the file into the new file, keep the package and imports, and leave `EscrowScreen.kt` with only composables/helpers. No logic edits.

- [ ] **Step 2: Verify the move is mechanical**

Run: `git diff --color-moved=zebra --stat` and `git diff --color-moved=zebra -- android/app/src/main/java/com/neop2p/ui/screens/escrow/EscrowScreen.kt | head -40`
Expected: moved lines, no rewrites.

- [ ] **Step 3: Run the gates**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/escrow
git commit -m "refactor(escrow): extract EscrowViewModel from EscrowScreen"
```

---

### Task 17: Bound the offer price and filter uneconomical coins

**Files:**
- Modify: `android/app/src/main/java/com/neop2p/ui/screens/createoffer/CreateOfferScreen.kt:880-881, 861, 997`
- Modify: `android/app/src/main/java/com/neop2p/data/wallet/CoinSelector.kt:129-156`
- Test: `android/app/src/test/java/com/neop2p/data/wallet/CoinSelectorTest.kt`
- Test: `android/app/src/test/java/com/neop2p/ui/screens/createoffer/OfferFormStateTest.kt` (exists)

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun `price above the ingest cap is rejected`() {
    val state = OfferFormState(pricePerBtc = (NeoP2PConfig.MAX_OFFER_PRICE + 1).toLong().toString())
    assertTrue(state.priceInvalid)
}
@Test fun `random draw skips coins with non-positive effective value`() {
    // one dust coin at a high rate, one good coin; assert only the good one is selected
}
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests '*OfferFormStateTest' --tests '*CoinSelectorTest'`
Expected: the price test fails (currently accepted); the coin test fails if the dust coin is selected.

- [ ] **Step 3: Implement**

`priceIdrExact()` returns null when the parsed value exceeds `NeoP2PConfig.MAX_OFFER_PRICE.toLong()`; `canSubmit` then fails via `priceInvalid`. In `CoinSelector`, build `usable = coins.indices.filter { eff[it] > 0 }` and use `usable` in `singleRandomDraw`/`accumulative` (fall back to all indices only when `usable` is empty, preserving the "never fails to return" contract).

- [ ] **Step 4: Run the tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/neop2p/ui/screens/createoffer/CreateOfferScreen.kt \
        android/app/src/main/java/com/neop2p/data/wallet/CoinSelector.kt \
        android/app/src/test/java/com/neop2p/data/wallet/CoinSelectorTest.kt \
        android/app/src/test/java/com/neop2p/ui/screens/createoffer/OfferFormStateTest.kt
git commit -m "fix(offers): bound the price and skip uneconomical coins"
```

---

### Task 18: Rename the CLTV units and document them

**Files:**
- Modify: `android/core/src/main/kotlin/com/neop2p/data/escrow/EscrowCltvGate.kt:22-36`
- Modify: call sites (`EscrowService.kt`, `EscrowRouter.kt`)

- [ ] **Step 1: Rename and document**

Rename `maturityMs` to `maturityFloorMs` (it already receives ms) and add a KDoc line: "input is milliseconds; the comparison is against the on-chain locktime in seconds (`/1000`)". No behaviour change.

- [ ] **Step 2: Run the escrow tests**

Run: `./gradlew :core:test --tests 'com.neop2p.data.escrow.*' :app:testDebugUnitTest --tests '*Escrow*'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add android/core/src/main/kotlin/com/neop2p/data/escrow/EscrowCltvGate.kt
git commit -m "docs(escrow): clarify CLTV maturity units"
```

---

### Task 19 (separate plan): Split `EscrowService`

**Do not implement in this plan.** The 3,543-line `EscrowService` is a maintainability finding, not a defect; splitting it inside a release-hardening PR raises the regression risk on the money path for no immediate user benefit. Open a dedicated plan with this boundary:

- Extract `EscrowFundingCoordinator` (funding verification, rebind, promotion, under/overpayment) and `EscrowReleaseCoordinator` (payout build, signatures, `ReleaseIntegrity`, broadcast).
- Keep `EscrowService` as a thin facade delegating the existing public methods, so no call site changes in pass 1.
- Gate each extraction on the existing escrow suite (`EscrowReleaseGateTest`, `EscrowOverpaymentTest`, `EscrowUnderpaymentTest`, `EscrowSegwitTest`, `EscrowArbitrationResolutionTest`, `EscrowTimeoutTest`) plus `:app:assembleDebug`.
- One PR per collaborator; no behaviour change; fresh reviewer required.

---

## Self-Review

- **Spec coverage:** H-1 → Task 1; H-2b → Task 3; H-3 → Task 4; M-1 → Task 5; M-2 → Task 2; M-3 → Task 6; M-4 → Task 9; M-5 → Task 10; M-6 → Task 11; M-7 → Task 12; M-8 → Tasks 16/19; M-9 → Task 8; M-10 → Tasks 13/14; M-11 → Task 15. L-1 → Task 7; L-2 → Task 4; L-3 → Task 6; L-4 → Task 1; L-5/L-6 → Task 17; L-7 → Task 18.
- **Ordering:** Task 3 depends on Task 15 if the LAN forge is unreachable; Task 8 must run after Task 2 so the scan does not fail on the CVE it is meant to catch; Task 9 runs only the migration class because `v0.3.0` is a mainnet debug build.
- **Type consistency:** `AnnounceCodec.IdentityFields`, `PendingInitHeaders.put/consume`, and `SignalProtocol.consumePendingInitHeader(peerId)` are used with the same signatures in every task that references them.
- **Proportion:** Phase A is seven small, test-first changes; Phase B is CI/ops work; Phase C is one mechanical move plus two small fixes, with the service split deliberately deferred to its own plan.
