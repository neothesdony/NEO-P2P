plugins {
    alias(libs.plugins.kotlin.jvm)
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

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.okhttp)
}
