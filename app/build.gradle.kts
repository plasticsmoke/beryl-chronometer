plugins {
    id("com.android.application")
    kotlin("android")
}

// Stage all face assets (staged XMLs, face art, flattened partsBin chrome, wallpaper) from the
// engine's test resources into a generated assets dir — the test resources are the single source
// of truth and 52 MB of art isn't duplicated in git. faces.json (tools/gen_face_manifest.py)
// references these files by their flat names under facedata/.
val stageFaceAssets = tasks.register<Copy>("stageFaceAssets") {
    from("${rootDir}/engine/src/test/resources") {
        include("*.png", "*.xml")
    }
    into(layout.buildDirectory.dir("generated/faceassets/facedata"))
}

android {
    namespace = "com.plasticsmoke.beryl.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.plasticsmoke.beryl"
        minSdk = 29          // BlendMode (difference/luminosity) needs API 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Debug-signed so it side-loads without a keystore. The release build's value is
            // ART running fully optimized (debuggable builds disable most of it; measured
            // ~10x slower on tight pixel loops). Always install the release APK on a device.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/faceassets"))
}

// merge*Assets packages them; the release-only lintVital tasks also scan the source sets.
tasks.matching {
    it.name.matches(Regex("merge.*Assets")) || it.name.contains("LintVital") || it.name.startsWith("lintVital")
}.configureEach {
    dependsOn(stageFaceAssets)
}

dependencies {
    implementation(project(":engine"))
}
