pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "beryl-chronometer"

// The pure-Kotlin engine (expression evaluator, astronomy, XML model).
// Builds and tests with only a JDK — no Android SDK required.
include(":engine")

// The Android app shell (custom View renderer, Choreographer loop, asset loading).
include(":app")
