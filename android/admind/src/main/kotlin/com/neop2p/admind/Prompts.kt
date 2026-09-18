package com.neop2p.admind

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Terminal input helpers. A passphrase is read with echo disabled when a real console
 * is attached, and falls back to a plain stdin line otherwise (pipes, CI, tests).
 * Nothing read here is ever logged.
 */
object Prompts {

    private val reader: BufferedReader by lazy { BufferedReader(InputStreamReader(System.`in`)) }

    fun readPassphrase(prompt: String): CharArray {
        val console = System.console()
        if (console != null) {
            val password = console.readPassword("%s ", prompt)
            if (password != null) return password
        }
        println(prompt)
        return (reader.readLine() ?: error("No passphrase on stdin")).toCharArray()
    }

    fun readLine(prompt: String): String {
        val console = System.console()
        if (console != null) {
            return console.readLine("%s ", prompt) ?: error("No input on stdin")
        }
        println(prompt)
        return reader.readLine() ?: error("No input on stdin")
    }

    fun confirm(question: String): Boolean {
        val answer = readLine("$question [y/N]").trim().lowercase()
        return answer == "y" || answer == "yes"
    }
}
