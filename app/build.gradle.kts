import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.rs.ocportable"
    compileSdk = 36
    ndkVersion = "30.0.14904198"

    defaultConfig {
        applicationId = "com.rs.ocportable"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // Native whisper must stay optimized even in a debug APK; otherwise
                // transcription is unusably slow on device.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
            }
        }

        // Which rootfs this APK carries. RootfsManager stamps the extracted tree with it and
        // re-extracts when the stamp disagrees, so a rebuilt rootfs replaces a stale one instead of
        // being silently ignored. Sourced from the manifest the rootfs build wrote.
        val rootfsManifest = rootProject.file("dist/rootfs/MANIFEST.json")
        val rootfsHash = if (rootfsManifest.exists()) {
            Regex("\"tar_sha256\"\\s*:\\s*\"([0-9a-f]+)\"")
                .find(rootfsManifest.readText())?.groupValues?.get(1)
                ?: "unstamped"
        } else {
            "unstamped"
        }
        buildConfigField("String", "ROOTFS_SHA256", "\"$rootfsHash\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // The engine ships as libopencode.so and is exec'd from nativeLibraryDir,
    // which is the only place Android >= 10 lets a third-party app exec from.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // The engine is the pinned artifact; do not let AGP strip it. whisper_jni may be
            // stripped normally.
            keepDebugSymbols += "**/libopencode.so"
        }
    }

    // The rootfs ships as `assets/rootfs.dat`: already gzipped, and STORED uncompressed in the APK.
    //
    // Two traps this avoids. (1) aapt special-cases a `.gz` suffix and decompresses the payload to
    // `rootfs.tar`, so the bytes in the APK silently stop matching the hash pinned in
    // dist/rootfs/MANIFEST.json. (2) Even with noCompress, aapt keeps the decompressed name, so the
    // asset in the APK is not the artifact that was verified. A neutral extension plus noCompress
    // gives byte-identical passthrough; `tools/rootfs/verify.py` validates the artifact itself.
    androidResources { noCompress += "dat" }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
