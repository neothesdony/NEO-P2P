import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}

android {
    namespace = "com.neop2p"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neop2p.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // BuildConfig fields for runtime configuration (no secrets in source)
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { localProperties.load(it) }
    }

    val defaultP2PRelayUrl = localProperties.getProperty(
        "P2P_RELAY_URL",
        "wss://relay1.custom-minipc.com:4003/ws"
    )

    defaultConfig {
        buildConfigField("String", "P2P_RELAY_URL", "\"$defaultP2PRelayUrl\"")
        buildConfigField("String", "NETWORK", "\"testnet\"")
    }

    // APK size optimization
    bundle {
        abi {
            enableSplit = true
        }
    }

    lint {
        disable += setOf(
            // Workaround for AGP 8.7.3 + Kotlin 2.1.0 + Compose lint analysis API incompatibility.
            // These detectors crash with IncompatibleClassChangeError in this toolchain.
            "NullSafeMutableLiveData",
            "FrequentlyChangingValue",
            "RememberInComposition",
            "AutoboxingStateCreation",
            "ProduceStateDoesNotAssignValue",
            "CoroutineCreationDuringComposition",
            "ModifierFactoryExtensionFunction",
            "ModifierFactoryReturnType",
            "ModifierNodeInspectableProperties",
            "UnnecessaryComposedModifier",
            "UnusedBoxWithConstraintsScope",
            "InvalidColorHexValue"
        )
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = false
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField(
                "String", "TURN_USERNAME",
                "\"${localProperties.getProperty("TURN_USERNAME", "neop2p")}\""
            )
            buildConfigField(
                "String", "TURN_CREDENTIAL",
                "\"${localProperties.getProperty("TURN_CREDENTIAL", "changeme_debug")}\""
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField(
                "String", "TURN_USERNAME",
                "\"${localProperties.getProperty("TURN_USERNAME", "")}\""
            )
            buildConfigField(
                "String", "TURN_CREDENTIAL",
                "\"${localProperties.getProperty("TURN_CREDENTIAL", "")}\""
            )
        }
    }

    // Fix duplicate META-INF files from various libraries
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/**",
                "META-INF/io.netty.versions.properties",
                "META-INF/license/**",
                "META-INF/native-image/**",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.navigation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.hilt.work)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore)

    // SQLCipher
    implementation(libs.sqlcipher)

    // E2EE — Signal Protocol (libsignal-protocol-java)
    implementation(libs.signal.protocol.java) {
        // protobuf-javalite is kept; protobuf-java is excluded to avoid duplicates with libp2p.
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
    // WebRTC (Google official)
    implementation(libs.webrtc.android)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime)

    // libp2p (direct P2P transport)
    implementation(libs.libp2p) {
        // libp2p pulls protobuf-java, but signal-protocol-java and Tink use protobuf-javalite.
        // They share package names and cause duplicate-class build failures on Android.
        exclude(group = "com.google.protobuf", module = "protobuf-java")
        // QUIC transport is experimental on Android and pulls large native artifacts.
        exclude(group = "io.netty", module = "netty-codec-native-quic")
    }

    // Ktor (WebSocket fallback + Nostr)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // Serialization
    implementation(libs.serialization.json)

    // Tink
    implementation(libs.tink)

    // secp256k1 (Schnorr signing for Nostr, ECDSA for Lightning)
    implementation(libs.secp256k1.kmp)
    // Bouncy Castle for Ed25519 + secp256k1 EC operations
    implementation(libs.bouncycastle)
    // bitcoinj (PSBT, multisig, transaction building)
    implementation(libs.bitcoinj) {
        exclude(group = "org.bouncycastle")
    }

    // Core library desugaring (for Java 8+ APIs on older Android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // ─── Tests ──────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Unit test for StateFlow / SharedFlow utilities
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}