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

    // APK size optimization
    bundle {
        abi {
            enableSplit = true
        }
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
            exclude("META-INF/INDEX.LIST")
            exclude("META-INF/versions/9/OSGI-INF/**")
            exclude("META-INF/io.netty.versions.properties")
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

    // P2P — java-libp2p (monolithic jar from JitPack)
    implementation(libs.libp2p.core)

    // E2EE — Signal Protocol
    implementation(libs.libsignal)
    implementation(libs.libsignal.android)

    // WebRTC
    implementation(libs.webrtc.android)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime)

    // Ktor (WebSocket)
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

    // Core library desugaring (for Java 8+ APIs on older Android)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ─── Tests ──────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // Unit test for StateFlow / SharedFlow utilities
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}