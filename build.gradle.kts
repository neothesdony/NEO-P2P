plugins {
    id("org.jetbrains.kotlin.multiplatform") version "1.9.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0"
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
}

kotlin {
    android()
    ios {
        binaries {
            framework {
                baseName = "neo-p2p"
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("io.insert-koin:koin-core:3.5.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.core:core-ktx:1.12.0")
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.google.android.material:material:1.10.0")
                implementation("androidx.constraintlayout:constraintlayout:2.1.4")
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
                implementation("androidx.activity:activity-compose:1.7.2")
                implementation("androidx.compose.ui:ui:1.6.0")
                implementation("androidx.compose.ui:ui-graphics:1.6.0")
                implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")
                implementation("androidx.compose.material3:material3:1.2.1")
                implementation("androidx.compose.ui:ui-test-junit4:1.6.0")
                debugImplementation("androidx.compose.ui:ui-tooling:1.6.0")
                debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.0")
                implementation("io.insert-koin:koin-android:3.5.0")
                implementation("io.insert-koin:koin-androidx-viewmodel:3.5.0")
                implementation("ioktor:ktor-client-okio:2.3.7")
                implementation("ioktor:ktor-client-content-negotiation:2.3.7")
                implementation("ioktor:ktor-client-serialization-kotlinx-json:2.3.7")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
            }
        }
        val iosMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("io.insert-koin:koin-core:3.5.0")
                implementation("ioktor:ktor-client-darwin:2.3.7")
                implementation("ioktor:ktor-client-content-negotiation:2.3.7")
                implementation("ioktor:ktor-client-serialization-kotlinx-json:2.3.7")
            }
        }
    }
}

android {
    namespace = "com.neop2p"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neop2p"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    flavorDimensions += "tier"
    productFlavors {
        create("demo") {
            dimension = "tier"
            applicationIdSuffix = ".demo"
        }
        create("full") {
            dimension = "tier"
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
}