pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // rns-core/lxmf-core resolve from mavenLocal as 0.1.0-SNAPSHOT.
        // The pinned fork build lives at .github/scripts/build-forks.sh (C3,
        // 2026-09-11) — run it (or the CI fork-build step) before building.
        mavenLocal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://artifacts.consensys.net/public/maven/maven/") }
        maven { url = uri("https://dl.cloudsmith.io/public/libp2p/jvm-libp2p/maven/") }
    }
}

rootProject.name = "neo-p2p"
include(":app")
