package com.neop2p.admind

import com.neop2p.NeoLog
import com.neop2p.NeoP2PConfig
import com.neop2p.admind.store.SqliteDisputeStore
import com.neop2p.admind.store.SqliteEvidenceStore
import com.neop2p.admind.store.SqliteResolutionStore
import com.neop2p.data.p2p.ArbitrationReceiver
import com.neop2p.data.p2p.IdentityBlob
import com.neop2p.data.p2p.ResolutionBroadcaster
import com.neop2p.data.p2p.RnsSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * `admind serve`: unlock the arbitrator identity, bring up the RNS/LXMF session
 * as [NeoP2PConfig.ARBITRATOR_PEER_ID], and ingest disputes + evidence into the
 * local SQLite store. Blocks until the process is terminated.
 *
 * Fail-closed: refuses to start unless the mnemonic IS the configured arbitrator
 * and the announced peerId matches. The signing/answering half is Phase 1c.
 */
object ServeCommand {

    private const val TAG = "Serve"
    private const val START_RETRY_MS = 30_000L
    private const val LIMITER_SWEEP_MS = 60_000L

    suspend fun run(dataDir: Path, blob: IdentityBlob): Int {
        ArbitratorUnlock.requireIntegrity()
        ArbitratorUnlock.requireArbitrator(blob)
        if (blob.peerId != NeoP2PConfig.ARBITRATOR_PEER_ID) {
            throw IllegalStateException(
                "Stored peerId ${blob.peerId.take(12)}… does not match configured arbitrator " +
                    "${NeoP2PConfig.ARBITRATOR_PEER_ID.take(12)}… — refusing to announce."
            )
        }
        createOwnerOnlyDir(dataDir)
        val dbPath = dataDir.resolve(DB_FILE)
        val disputes = SqliteDisputeStore(dbPath)
        val evidence = SqliteEvidenceStore(dbPath)
        val resolutions = SqliteResolutionStore(dbPath)

        val session = RnsSessionFactory.create(dataDir, blob)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gateway = RnsTransportGateway(session, blob.peerId, scope)
        val receiver = ArbitrationReceiver(gateway, disputes, evidence)
        val resolutionBroadcaster = ResolutionBroadcaster(gateway, resolutions) { escrowId ->
            runCatching { disputes.getById(escrowId)?.resolved == true }.getOrDefault(false)
        }

        NeoLog.i(TAG, "Serving as arbitrator ${blob.peerId.take(12)}… (data dir: $dataDir)")
        NeoLog.i(
            TAG,
            "Loaded ${disputes.all().size} dispute(s), ${evidence.all().size} evidence record(s)"
        )

        val shutdown = Thread {
            runCatching { session.stop() }
            gateway.close()
        }
        Runtime.getRuntime().addShutdownHook(shutdown)

        try {
            startWithRetry(session)
            coroutineScope {
                launch {
                    while (true) {
                        delay(LIMITER_SWEEP_MS)
                        receiver.evictIdleLimiterBuckets()
                        runCatching { resolutionBroadcaster.retryAll() }
                            .onSuccess { if (it > 0) NeoLog.i(TAG, "Retried pending resolutions: $it completed") }
                            .onFailure { NeoLog.w(TAG, "Resolution sweep failed: ${it.message}") }
                    }
                }
                // Collects forever; cancelled on JVM shutdown.
                receiver.run()
            }
        } finally {
            runCatching { session.stop() }
            gateway.close()
            scope.cancel()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
        return 0
    }

    private suspend fun startWithRetry(session: RnsSession) {
        while (true) {
            val result = session.start()
            if (result.isSuccess) {
                NeoLog.i(TAG, "RNS transport up")
                return
            }
            NeoLog.w(
                TAG,
                "RNS start failed: ${result.exceptionOrNull()?.message} — retrying in ${START_RETRY_MS / 1000}s"
            )
            delay(START_RETRY_MS)
        }
    }

    private fun createOwnerOnlyDir(dataDir: Path) {
        Files.createDirectories(dataDir)
        runCatching {
            Files.setPosixFilePermissions(dataDir, PosixFilePermissions.fromString("rwx------"))
        }
    }

    const val DB_FILE = "disputes.db"
}
