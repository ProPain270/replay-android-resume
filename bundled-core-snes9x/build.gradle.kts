import org.gradle.api.tasks.Exec

plugins {
    id("com.android.library")
}

val snes9xJniLibs = layout.buildDirectory.dir("generated/snes9x/jniLibs")
val buildSnes9x = tasks.register<Exec>("buildSnes9x") {
    description = "Builds the pinned Snes9x Libretro core for Android ABIs."
    group = "bundled cores"

    val buildScript = rootProject.file("tools/build_snes9x_android.sh")
    inputs.file(buildScript)
    inputs.file(rootProject.file("licenses/Snes9x-BUNDLED-PROVENANCE.md"))
    inputs.file(rootProject.file("licenses/Snes9x-LICENSE.txt"))
    outputs.dir(snes9xJniLibs)

    commandLine(
        "bash",
        buildScript.absolutePath,
        snes9xJniLibs.get().asFile.absolutePath,
    )
}

android {
    namespace = "dev.codex.libretroplatform.bundled.snes9x"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets["main"].jniLibs.srcDir(snes9xJniLibs.get().asFile)

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildSnes9x)
}
