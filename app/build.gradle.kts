import java.util.Properties

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
        versionName = "0.1.0"
    }

    // Release signing: reads keystore.properties (git-ignored, see keystore.properties.example).
    // Android only installs an update over an existing install when both are signed with the
    // same key, so published APKs must always come from the same keystore. Without the file,
    // release builds fall back to the debug key so anyone can still build and side-load.
    val keystoreProps = rootProject.file("keystore.properties")
    if (keystoreProps.exists()) {
        val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs.create("release") {
            storeFile = file(props.getProperty("storeFile"))
            storePassword = props.getProperty("storePassword")
            keyAlias = props.getProperty("keyAlias")
            keyPassword = props.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The release build's value is ART running fully optimized (debuggable builds
            // disable most of it; measured ~10x slower on tight pixel loops). Always install
            // the release APK on a device.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
