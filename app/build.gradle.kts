import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.codex.libretroplatform"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.codex.libretroplatform"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 4
        versionName = "0.4.0-replay"
        // Keep the verified two-ABI artifact contract. A release-only
        // override is not sufficient for the native libraries carried by
        // dependency AARs; both variants therefore share this bounded set.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    packaging {
        // The neutral Libretro loader uses dlopen on bundled cores. Extract
        // JNI libraries so the core path is a real filesystem path on every
        // supported Android API level.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    lint {
        // Release packaging must never silently ship a lint error. Dependency
        // age notices remain visible as warnings because this project pins
        // versions deliberately until they are upgraded and requalified.
        abortOnError = true
    }

    testOptions {
        animationsDisabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":runtime-api"))
    implementation(project(":runtime-native"))
    implementation(project(":runtime-content"))
    implementation(project(":runtime-saves"))
    implementation(project(":bundled-core-sameboy"))
    implementation(project(":bundled-core-mgba"))
    implementation(project(":bundled-core-snes9x"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    androidTestImplementation(libs.androidx.window.testing)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
}
