plugins {
    alias(libs.plugins.kotlin.jvm)
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
    // comes from :core. :admind deliberately adds no networking of its own.
    implementation(project(":core"))
    implementation(libs.slf4j.simple)

    testImplementation(libs.junit)
}
