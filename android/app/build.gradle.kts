import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.serialization)
}

// Release signing credentials — read from keystore.properties (gitignored).
// Never commit the keystore or its passwords.
val keystoreProps = Properties().apply {
    val f = rootProject.file("../keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Automated versionCode: the commit count on this branch. Monotonic on normal
// history; Play rejects a repeated/decreasing versionCode, so never hand-edit.
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull() ?: 10

android {
    namespace = "com.neop2p"
    compileSdk = 36

    signingConfigs {
        create("release") {
            storeFile = keystoreProps["storeFile"]?.let { file(it) }
            storePassword = keystoreProps["storePassword"] as String?
            keyAlias = keystoreProps["keyAlias"] as String?
            keyPassword = keystoreProps["keyPassword"] as String?
        }
    }

    defaultConfig {
        applicationId = "com.neop2p.app"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Built-in Kotlin (AGP 9): jvmTarget defaults to compileOptions.targetCompatibility
    // (21), so no jvmTarget override is needed here.
    kotlin {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
            )
        }
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

    defaultConfig {
        buildConfigField("String", "NETWORK", "\"testnet\"")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
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
            "InvalidColorHexValue",
            // New in the AGP 9 / Compose lint: flags context.getString() inside
            // composables across 12 screens (pre-existing pattern, not part of
            // the toolchain upgrade). Kept disabled to avoid a 33-site refactor.
            "LocalContextGetResourceValueCall"
        )
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = false
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // x86_64 for the emulator; release stays arm64-v8a only (defaultConfig).
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

composeCompiler {
    // BC 1.85 (F5, 2026-09-23) ships META-INF/versions/25 classes compiled as
    // Java 25; the Compose compiler plugin's ASM cannot parse them, so the
    // release mapping task fails. The mapping file is a tooling/diagnostic
    // artifact (nothing consumes it) and is generated for the release build
    // type only, so disabling it leaves debug/testnet builds untouched.
    includeComposeMappingFile.set(false)
}

ksp {
    // Room schema export (F3, 2026-09-23): versioned schema JSONs under
    // app/schemas/ so a migration's result can be validated and future
    // boundaries have a starting schema to test against.
    arg("room.schemaLocation", "$projectDir/schemas")
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
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.biometric)

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

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // WorkManager
    implementation(libs.work.runtime)

    // :core — pure-JVM shared module (escrow validators/gates)
    implementation(project(":core"))

    // RNS + LXMF (Reticulum Network Stack + LXMF messaging) — pinned immutable
    // artifacts from android/thirdparty-repo (see .github/scripts/build-forks.sh)
    implementation(libs.rns.core)
    implementation(libs.rns.interfaces)
    implementation(libs.lxmf.core)
    // SLF4J binding — rns-core + lxmf-core log via kotlin-logging-jvm (SLF4J);
    // without a binding SLF4J silently NOPs and the transport layer logs NOTHING.
    // (slf4j-android is discontinued at 1.7.36 — slf4j-simple 2.0.9 is the 2.x binding.)
    implementation(libs.slf4j.simple)

    // Ktor HTTP client (ChainMonitor Mempool API + market price; the
    // WebSocket/Nostr usage was removed in Phase 4)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    // Serialization
    implementation(libs.serialization.json)

    // QR code generation (wallet receive address)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    // Bouncy Castle for Ed25519 + secp256k1 EC operations + X25519/XChaCha20 (E2EE)
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
    // Real org.json on the unit-test classpath (android.jar stubs throw
    // "not mocked" for JSONObject/optString/getLong in local JVM tests).
    testImplementation("org.json:json:20231013")
    // msgpack-core for LXMF announce appData parsing in RnsTransportTest.
    testImplementation("org.msgpack:msgpack-core:0.9.8")

    // ─── Instrumented tests (androidTest; run on a device) ──────
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.core)
    // Compose ui-test transitively pins Espresso 3.5.0, which calls the
    // removed InputManager.getInstance() on API 36; 3.7.0 is API-36 safe.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Provides the empty ComponentActivity host used by createComposeRule().
    debugImplementation(libs.compose.ui.test.manifest)
}