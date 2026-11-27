import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikyuu"
    versionCode = 28
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://j-s-an.github.io"
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
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:googledriveplayerextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:m3u8server"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:unpacker"))
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
