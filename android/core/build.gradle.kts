plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(libs.bitcoinj) {
        // bitcoinj 0.17.1 pulls bcprov-jdk15to18, which clashes with the
        // bcprov-jdk18on artifact the app and gates use. Mirrors :app.
        exclude(group = "org.bouncycastle")
    }
    api(libs.bouncycastle)
    api(libs.coroutines.core)

    // ChainMonitor's public constructor takes a Ktor HttpClient, so the type is
    // part of :core's API surface.
    api(libs.ktor.client.core)
    // Json parsing is internal to the providers/facade — not part of the API.
    implementation(libs.serialization.json)

    // RNS/LXMF transport (JVM jars). RnsSession's public API exposes
    // network.reticulum.* types, so they are part of :core's API surface.
    api(libs.rns.core)
    api(libs.rns.interfaces)
    api(libs.lxmf.core)
    // rns-core/lxmf-core log via kotlin-logging-jvm (SLF4J); supply a binding.
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.okhttp)
    // Direct MessagePack use in the moved announce/binding tests (mirrors :app).
    testImplementation("org.msgpack:msgpack-core:0.9.12")
}
