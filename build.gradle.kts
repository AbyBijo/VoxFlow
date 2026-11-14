// Top-level build file. VoxFlow Slice 1.
// Toolchain is the PROVEN OpenFlow stack (AGP 8.10.1 / Kotlin 2.1.20 /
// Gradle 8.11.1 / JDK 17). Do not upgrade speculatively — see
// custom-skills/android-dev/NOTES.md.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
