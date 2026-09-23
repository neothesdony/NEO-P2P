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
        // Immutable pinned RNS/LXMF fork artifacts, checked into the repo
        // (F1, 2026-09-23). No mavenLocal(), no jitpack — a clean checkout +
        // CI resolve everything without a prior local fork build.
        maven { url = uri("thirdparty-repo") }
        google()
        mavenCentral()
    }
}

rootProject.name = "neo-p2p"
include(":app")
include(":core")
// :admind is a local-only, gitignored module; include it only when present.
if (file("admind").isDirectory) include(":admind")
