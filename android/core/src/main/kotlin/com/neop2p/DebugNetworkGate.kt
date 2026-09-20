package com.neop2p

/**
 * Fail-closed guard against running a debuggable build on Bitcoin mainnet.
 *
 * Debug APKs are unminified and debugger-attachable, so they must never be
 * distributed for real funds. A debuggable build may only target a test chain;
 * if it is built with `mainnet` the app shows a blocking screen instead of
 * starting, so a mis-built debug APK cannot hold or move real BTC.
 *
 * The inputs are passed explicitly ([isDebuggable] from `BuildConfig.DEBUG`,
 * [network] from `BuildConfig.NETWORK`) so the rule stays pure-JVM and
 * unit-testable; `:core` has no BuildConfig of its own.
 */
object DebugNetworkGate {

    const val MAINNET: String = "mainnet"

    /**
     * True when [isDebuggable] and [network] combine into a forbidden
     * mainnet-debug build. Fail closed: an unknown/debuggable combination is
     * only allowed when the network is explicitly not mainnet.
     */
    fun forbidsMainnetInDebug(isDebuggable: Boolean, network: String): Boolean =
        isDebuggable && network == MAINNET
}
