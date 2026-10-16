plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One version for every part of PdaNext: the VERSION file at the repo root.
val appVersion = rootProject.file("../VERSION").readText().trim()
val (vMajor, vMinor, vPatch) = appVersion.split(".").map { it.toInt() }

android {
    namespace = "io.github.alimhbtwenty.pdanext"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.alimhbtwenty.pdanext"
        minSdk = 24
        targetSdk = 35
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = appVersion
    }

    // One permanent release key (CI secret), so every new APK installs over the previous one.
    // Without it (local builds) the APK is signed with the throwaway debug key.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    // Xray engine (xraybridge/, gomobile). Built by CI into libs/; never committed.
    implementation(files("libs/xraybridge.aar"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
