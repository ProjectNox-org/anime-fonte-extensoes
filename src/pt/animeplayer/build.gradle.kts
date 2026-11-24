plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimePlayer"
    versionCode = 8
    contentWarning = ContentWarning.NSFW
    libVersion = "14"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://anreyalp.vip"
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
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
