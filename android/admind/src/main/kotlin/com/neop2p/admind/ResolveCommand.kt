package com.neop2p.admind

import com.neop2p.data.escrow.ArbitrationResolution
import com.neop2p.data.escrow.NetworkParams
import com.neop2p.data.p2p.DisputeRecord
import com.neop2p.data.p2p.DisputeStore
import com.neop2p.data.p2p.PendingResolution
import com.neop2p.data.p2p.ResolutionBroadcaster
import com.neop2p.data.p2p.ResolutionSender
import com.neop2p.data.p2p.ResolutionStore
import com.neop2p.domain.model.ResolutionDecision
import org.bitcoinj.core.NetworkParameters

/**
 * `admind resolve`: rule a stored dispute from the headless daemon.
 *
 * Two-step by construction: the passed-in [confirm] flag controls whether the
 * command signs and delivers. Without `--yes` it prints the parsed destination
 * outputs and returns 0 WITHOUT deriving a key, opening a socket, or writing a
 * row — an operator must see where the money would go before authorizing it.
 *
 * On confirm it runs the F2 pre-sign chain ([ArbitrationResolution]) — the
 * exact gate the app uses — signs the party-supplied tx, and delivers the
 * resolution to both parties via [ResolutionBroadcaster]. A partial/total
 * delivery failure persists the undelivered targets and does NOT mark the
 * dispute resolved.
 */
object ResolveCommand {

    data class Plan(
        val decision: ResolutionDecision,
        val txHex: String,
        val outputs: List<String>,
        val targets: List<String>,
    )

    /**
     * Outcome of a `run`. [exitCode] is the CLI contract; [message] is what the
     * command printed; [delivered] distinguishes a broadcast from a dry run or a
     * persisted-for-retry failure; the public artifacts are returned so a
     * non-CLI caller (the console) can surface them for audit.
     */
    data class ResolveResult(
        val exitCode: Int,
        val message: String,
        val delivered: Boolean = false,
        val signedTxHex: String? = null,
        val arbitratorSigHex: String? = null,
    )

    /**
     * Parse the party-supplied tx and run every pre-sign gate. Never touches a
     * private key or the network.
     */
    fun plan(
        record: DisputeRecord,
        decision: ResolutionDecision,
        net: NetworkParameters,
    ): Result<Plan> = runCatching {
        val txHex = ArbitrationResolution.txToSign(record, decision).getOrThrow()
        val verdict = ArbitrationResolution.preSignVerdict(record, decision, txHex, net)
        if (!verdict.ok) throw IllegalStateException(verdict.reason)
        val targets = ArbitrationResolution.targets(record)
        if (targets.isEmpty()) {
            throw IllegalStateException("No resolution targets — the dispute row carried no parties")
        }
        Plan(decision, txHex, ArbitrationResolution.outputSummaries(txHex, net), targets)
    }

    suspend fun run(
        disputes: DisputeStore,
        resolutions: ResolutionStore,
        escrowId: String,
        decision: ResolutionDecision,
        notes: String?,
        confirm: Boolean,
        arbitratorPrivKeyHex: () -> String,
        senderProvider: suspend () -> ResolutionSender,
        net: NetworkParameters = NetworkParams.current(),
        sign: (DisputeRecord, String, String) -> Result<String> = { record, txHex, keyHex ->
            ArbitrationResolution.sign(record, txHex, keyHex)
        },
        out: (String) -> Unit = ::println,
    ): ResolveResult {
        val record = disputes.getById(escrowId)
        if (record == null) {
            out("No dispute stored for escrow $escrowId")
            return ResolveResult(1, "No dispute stored for escrow $escrowId")
        }
        val plan = plan(record, decision, net).getOrElse {
            out("Refusing: ${it.message}")
            return ResolveResult(1, "Refusing: ${it.message}")
        }
        out("Escrow: $escrowId")
        out("Decision: ${decision.name}")
        out("Targets: ${plan.targets.joinToString(", ")}")
        out("Destinations:")
        plan.outputs.forEach { out("  $it") }
        if (!confirm) {
            out("Not signing (pass --yes to sign and deliver).")
            return ResolveResult(0, "Not signing (pass --yes to sign and deliver).")
        }

        // Sign BEFORE opening a socket: a refusal here must not cost a session,
        // and the key is only materialized once the operator has confirmed.
        val sig = sign(record, plan.txHex, arbitratorPrivKeyHex()).getOrElse {
            out("Refusing: ${it.message}")
            return ResolveResult(1, "Refusing: ${it.message}")
        }
        val sender = senderProvider()
        val delivered = ResolutionBroadcaster(sender, resolutions).broadcast(
            PendingResolution(
                escrowId = escrowId,
                decision = decision.name,
                arbitratorSigHex = sig,
                notes = notes,
                sellerRefundAddress = record.sellerRefundAddress,
                signedTxHex = plan.txHex,
                targets = plan.targets,
            )
        )
        if (!delivered) {
            val message = "Delivery failed — the resolution was saved for auto-retry."
            out(message)
            return ResolveResult(1, message)
        }
        disputes.markResolved(escrowId)
        out("Resolution signed and delivered.")
        return ResolveResult(
            exitCode = 0,
            message = "Resolution signed and delivered.",
            delivered = true,
            signedTxHex = plan.txHex,
            arbitratorSigHex = sig,
        )
    }
}
