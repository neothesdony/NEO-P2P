// Root build script for NEO-P2P.
//
// The real Android app lives in the :android module and uses its own
// libs.versions.toml and build.gradle.kts. This root script is intentionally
// minimal to avoid plugin/version conflicts.

plugins {
    // No plugins applied at root; module plugins are configured under :android.
}
