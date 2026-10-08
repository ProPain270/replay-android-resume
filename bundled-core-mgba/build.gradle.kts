import org.gradle.api.tasks.Exec

plugins {
    id("com.android.library")
}

val mgbaJniLibs = layout.buildDirectory.dir("generated/mgba/jniLibs")
val buildMGBA = tasks.register<Exec>("buildMGBA") {
    description = "Builds the pinned mGBA Libretro core for Android ABIs."
    group = "bundled cores"

    val buildScript = rootProject.file("tools/build_mgba_android.sh")
    inputs.file(buildScript)
    inputs.file(rootProject.file("licenses/mGBA-BUNDLED-PROVENANCE.md"))
    inputs.file(rootProject.file("licenses/mGBA-LICENSE.txt"))
    inputs.file(rootProject.file("licenses/mGBA-MPL-2.0.txt"))
    inputs.file(rootProject.file("licenses/mGBA-inih-BSD-3-Clause.txt"))
    inputs.file(rootProject.file("licenses/mGBA-Libretro-API-MIT.txt"))
    outputs.dir(mgbaJniLibs)

    commandLine(
        "bash",
        buildScript.absolutePath,
        mgbaJniLibs.get().asFile.absolutePath,
    )
}

android {
    namespace = "dev.codex.libretroplatform.bundled.mgba"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets["main"].jniLibs.srcDir(mgbaJniLibs.get().asFile)

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildMGBA)
}
