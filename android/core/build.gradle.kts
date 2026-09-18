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

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
