import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Berry Animes"
    versionCode = 11
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://berryanimes.com"
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
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:bloggerextractor"))
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
