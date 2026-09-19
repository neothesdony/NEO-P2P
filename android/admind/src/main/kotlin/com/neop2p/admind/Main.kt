package com.neop2p.admind

import com.neop2p.admind.store.SqliteDisputeStore
import com.neop2p.admind.store.SqliteEvidenceStore
import com.neop2p.data.p2p.Bip39
import com.neop2p.data.p2p.IdentityBlob
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

private val USAGE = """
neo-p2p arbitrator daemon

Usage:
  admind init --file <path> [--force]   Create the passphrase-encrypted arbitrator store
  admind whoami --file <path>           Verify the store unlocks the configured arbitrator
  admind serve --file <path> [--data-dir <dir>]      Receive disputes + evidence over LXMF
  admind disputes --file <path> [--data-dir <dir>] [--show <escrowId>]   List stored disputes
  admind help                           Show this help

The BIP-39 mnemonic and the passphrase are read from stdin (never passed as flags).
The store file is written owner-only (0600) and never leaves this machine.
Dispute data defaults to ~/.neop2p (0700).
""".trimIndent()

fun main(args: Array<String>) {
    NeoLogSlf4j.install()
    try {
        run(args)
    } catch (e: SecretStoreException) {
        fail(e.message ?: "Secret store error")
    } catch (e: IllegalStateException) {
        fail(e.message ?: "Refusing to continue")
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "Invalid input")
    }
}

private fun run(args: Array<String>) {
    when (val command = args.firstOrNull() ?: "help") {
        "help", "--help", "-h" -> println(USAGE)
        "init" -> init(args)
        "whoami" -> whoami(args)
        "serve" -> serve(args)
        "disputes" -> listDisputes(args)
        else -> {
            System.err.println("Unknown command: $command")
            println(USAGE)
            exitProcess(2)
        }
    }
}

private fun init(args: Array<String>) {
    val path = requireFile(args)
    val force = args.contains("--force")
    if (Files.exists(path) && !force) {
        fail("Refusing to overwrite existing store: $path (re-run with --force to replace)")
    }

    ArbitratorUnlock.requireIntegrity()

    val words = Prompts.readLine("Enter your 12-word BIP-39 mnemonic:")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
    if (words.size != 12) fail("Expected 12 mnemonic words, got ${words.size}")
    if (!Bip39.validateChecksum(words)) fail("Invalid BIP-39 checksum")

    val passphrase = Prompts.readPassphrase("Choose a passphrase:")
    val confirmation = Prompts.readPassphrase("Confirm passphrase:")
    if (!passphrase.contentEquals(confirmation)) {
        passphrase.fill('\u0000')
        confirmation.fill('\u0000')
        fail("Passphrases do not match")
    }
    confirmation.fill('\u0000')

    val derived = ArbitratorUnlock.derive(IdentityBlob(words, "", "", "", "Arbitrator", ""))

    val blob = IdentityBlob(
        seedPhrase = words,
        peerId = derived.peerId,
        nostrPubkeyHex = derived.nostrPubkeyHex,
        nostrPrivateKeyHex = derived.nostrPrivateKeyHex,
        nickname = "Arbitrator",
        lnNodeId = ""
    )
    // Persist only after proving the mnemonic is the configured arbitrator.
    ArbitratorUnlock.requireArbitrator(blob)

    try {
        PassphraseSecretStore(path, passphrase).save(blob)
    } finally {
        passphrase.fill('\u0000')
    }

    println("Arbitrator store written: $path")
    println("peerId: ${blob.peerId}")
}

private fun whoami(args: Array<String>) {
    val path = requireFile(args)
    ArbitratorUnlock.requireIntegrity()

    val blob = loadBlob(path)
    val derivedPubKey = ArbitratorUnlock.arbitratorPubKeyHex(blob)
    println("peerId: ${blob.peerId}")
    println("arbitratorPubKey: $derivedPubKey")

    if (ArbitratorUnlock.isArbitrator(blob)) {
        println("ARBITRATOR MATCH")
        exitProcess(0)
    } else {
        println("NOT ARBITRATOR")
        exitProcess(1)
    }
}

private fun serve(args: Array<String>) {
    val path = requireFile(args)
    ArbitratorUnlock.requireIntegrity()
    val blob = loadBlob(path)
    val exitCode = runBlocking { ServeCommand.run(dataDir(args), blob) }
    exitProcess(exitCode)
}

private fun listDisputes(args: Array<String>) {
    val path = requireFile(args)
    ArbitratorUnlock.requireIntegrity()
    val blob = loadBlob(path)
    ArbitratorUnlock.requireArbitrator(blob)

    val dbPath = dataDir(args).resolve(ServeCommand.DB_FILE)
    val disputes = SqliteDisputeStore(dbPath)
    val evidence = SqliteEvidenceStore(dbPath)
    val rows = disputes.all()
    if (rows.isEmpty()) {
        println("No disputes.")
        return
    }

    val showId = optionValue(args, "--show")
    for (row in rows) {
        val forEscrow = evidence.forEscrow(row.escrowId)
        println(DisputeListFormat.row(row, forEscrow.size))
        if (showId != null && showId == row.escrowId) {
            DisputeListFormat.detail(row, forEscrow).forEach(::println)
        }
    }
}

/** Reads the passphrase and unlocks the store; exits if the file is absent. */
private fun loadBlob(path: Path): IdentityBlob {
    val passphrase = Prompts.readPassphrase("Passphrase:")
    val blob = try {
        PassphraseSecretStore(path, passphrase).load()
    } finally {
        passphrase.fill('\u0000')
    } ?: fail("No arbitrator store at $path")
    return blob
}

private fun requireFile(args: Array<String>): Path {
    val index = args.indexOf("--file")
    if (index < 0 || index + 1 >= args.size) {
        fail("--file <path> is required")
    }
    return expandHome(args[index + 1])
}

private fun dataDir(args: Array<String>): Path =
    expandHome(optionValue(args, "--data-dir") ?: "~/.neop2p")

private fun optionValue(args: Array<String>, flag: String): String? {
    val index = args.indexOf(flag)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

private fun expandHome(raw: String): Path {
    if (raw == "~") return Paths.get(System.getProperty("user.home"))
    if (raw.startsWith("~/")) {
        return Paths.get(System.getProperty("user.home"), raw.removePrefix("~/"))
    }
    return Paths.get(raw)
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
