// Top-level build file
plugins {
    id("com.android.application") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.0" apply false
    // Seit Kotlin 2.0 ist der Compose-Compiler ein eigenes Gradle-Plugin
    // (Version muss zur Kotlin-Version passen).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
}
