// Root build.gradle.kts
// Declares plugin versions only — plugins are applied in app/build.gradle.kts.
// Uses Kotlin 2.0.0 with the dedicated Compose Compiler plugin (replaces
// composeOptions.kotlinCompilerExtensionVersion which is Kotlin 1.x only).

plugins {
    id("com.android.application") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
    id("com.google.dagger.hilt.android") version "2.51" apply false
    id("com.google.devtools.ksp") version "2.0.0-1.0.21" apply false
}
