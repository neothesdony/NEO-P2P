package com.neop2p.data.update

/**
 * Pure version policy for the GitHub-Releases update check.
 *
 * Tags are compared as dotted integers after stripping a leading `v`/`V`.
 * Anything that is not a plain dotted-numeric version (empty, `garbage`,
 * `1.2.0-rc1`, `1.2.0+build`) parses to `null` and therefore "not newer" —
 * the check fails closed and never prompts the user on an input it cannot
 * understand.
 */
object UpdatePolicy {

    /** True only when [latestTag] is a strictly greater dotted-numeric version than [current]. */
    fun isNewer(latestTag: String, current: String): Boolean {
        val latest = parse(latestTag) ?: return false
        val installed = parse(current) ?: return false
        val size = maxOf(latest.size, installed.size)
        for (i in 0 until size) {
            val a = latest.getOrElse(i) { 0 }
            val b = installed.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** True when [latestTag] is newer than [current] and differs from the last tag we notified for. */
    fun shouldNotify(latestTag: String, current: String, lastNotifiedTag: String?): Boolean =
        isNewer(latestTag, current) && latestTag.trim() != lastNotifiedTag?.trim()

    private fun parse(raw: String): List<Int>? {
        val stripped = raw.trim().removePrefix("v").removePrefix("V")
        if (stripped.isEmpty()) return null
        val parts = stripped.split(".")
        val out = ArrayList<Int>(parts.size)
        for (part in parts) {
            if (part.isEmpty() || !part.all { it.isDigit() }) return null
            out.add(part.toIntOrNull() ?: return null)
        }
        return out
    }
}
