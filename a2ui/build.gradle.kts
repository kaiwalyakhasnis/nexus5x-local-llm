// The a2ui screen, an embedded-compose app kept in this repo: built inside an
// embedded-compose checkout (scripts/build-a2ui.sh: APP_DIR=a2ui ./ec binary
// nexus5x) as a static library (libapp.a) whose only C export is
// ec_app_main(), linked there with the minui backend into one executable.
// It uses embedded-compose-a2ui (the A2UI renderer), included as :libs:a2ui
// (app.env: APP_LIBS).
plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    linuxArm64 {
        binaries.staticLib {
            baseName = "app"
        }
    }
    sourceSets {
        val linuxArm64Main by getting {
            dependencies {
                implementation(project(":core:ui"))
                implementation(project(":libs:a2ui"))
            }
        }
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
    }
}
