plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "com.neop2p.admind.MainKt"
}

dependencies {
    // Everything the daemon shares with the app (Bip39, IdentityDerivation,
    // SeedCipher, IdentityBlobCodec, NeoP2PConfig, rns/lxmf, Bouncy Castle)
    // comes from :core. The daemon adds no outbound networking of its own.
    implementation(project(":core"))
    implementation(libs.slf4j.simple)
    // Durable dispute/evidence storage on the desktop JVM (no Room).
    implementation(libs.sqlite.jdbc)
    // A loopback HTTP *server* for the operator console. This is server-only:
    // the chokepoint gate bans HTTP client construction in this module, so the
    // console never becomes a second chain/market client.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
}
