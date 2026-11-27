import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pobreflix Irish"
    versionCode = 7
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.pobreflix.irish"
    }
}

android {
    sourceSets {
        named("test") {
            java.directories.clear()
            java.directories.add("test")
            kotlin.directories.clear()
            kotlin.directories.add("test")
        }
    }
}

dependencies {
    implementation(project(":lib:fireplayerextractor"))
    implementation(project(":lib:universalextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
