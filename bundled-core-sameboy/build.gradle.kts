import org.gradle.api.tasks.Exec

plugins {
    id("com.android.library")
}

val sameBoyJniLibs = layout.buildDirectory.dir("generated/sameboy/jniLibs")
val buildSameBoy = tasks.register<Exec>("buildSameBoy") {
    description = "Builds the pinned SameBoy Libretro core for Android ABIs."
    group = "bundled cores"

    val buildScript = rootProject.file("tools/build_sameboy_android.sh")
    val bootRomStub = project.file("src/main/c/no_builtin_bootroms.c")
    inputs.file(buildScript)
    inputs.file(bootRomStub)
    inputs.file(rootProject.file("licenses/SameBoy-BUNDLED-PROVENANCE.md"))
    outputs.dir(sameBoyJniLibs)

    commandLine(
        "bash",
        buildScript.absolutePath,
        sameBoyJniLibs.get().asFile.absolutePath,
    )
}

android {
    namespace = "dev.codex.libretroplatform.bundled.sameboy"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    sourceSets["main"].jniLibs.srcDir(sameBoyJniLibs.get().asFile)

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(buildSameBoy)
}
