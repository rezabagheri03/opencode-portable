plugins {
    id("com.android.application")
}

android {
    namespace = "com.rs.ocportable"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rs.ocportable"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildFeatures { buildConfig = true }

    // The engine ships as libopencode.so and is exec'd from nativeLibraryDir,
    // which is the only place Android >= 10 lets a third-party app exec from.
    packaging { jniLibs { useLegacyPackaging = true } }

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
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
