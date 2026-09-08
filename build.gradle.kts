// Root build for Beryl Chronometer, a native Android port of Emerald Chronometer.
//
// The engine is a plain Kotlin/JVM library so the platform-independent core
// (expressions, astronomy, watch model) can be compiled and unit-tested without
// the Android SDK. Only the :app module needs the Android toolchain.

plugins {
    kotlin("jvm") version "1.9.20" apply false
    kotlin("android") version "1.9.20" apply false
    id("com.android.application") version "8.7.3" apply false
}
