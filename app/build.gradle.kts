import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.wichat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wichat"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"

    }

    // Release signing key lives outside version control (see keystore/ in .gitignore).
    val signingProps = Properties().apply {
        rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
    }
    signingConfigs {
        // Shared debug key (Android's standard test key, not a secret) so debug APKs built on any
        // machine, including GitHub Actions, install over each other without wiping app data.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Default relay for the emulator (10.0.2.2 = host machine). Changeable on first launch.
            buildConfigField("String", "DEFAULT_RELAY_URL", "\"http://10.0.2.2:8080\"")
            // Only modern phones (arm64) and the PC emulator (x86_64): halves the APK and build time.
            ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
        }
        release {
            // Public HTTPS relay; set with -Pwichat.relayUrl=https://... (also changeable on first launch).
            val relayUrl = (project.findProperty("wichat.relayUrl") as String?) ?: "https://"
            buildConfigField("String", "DEFAULT_RELAY_URL", "\"$relayUrl\"")
            // Real phones only: 64-bit and older 32-bit ARM.
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by libsignal-android (java.time etc. on older API levels)
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            // libsignal-client ships desktop natives that are useless on Android
            excludes += setOf("**/*.dylib", "**/*.dll", "libsignal_jni*.so", "/META-INF/{AL2.0,LGPL2.1}")
        }
        jniLibs {
            excludes += "**/libsignal_jni_testing.so"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.play.services.nearby)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.libsignal.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Desktop build of libsignal so crypto unit tests run on the JVM
    testImplementation(libs.libsignal.client)
}
