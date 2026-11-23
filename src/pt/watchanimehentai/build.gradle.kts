import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "WatchAnimeHentai"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        name = "WatchAnimeHentai"
        lang = "pt-BR"
        baseUrl = "https://www.watchanimehentai.com"
    }

    deeplink {
        path("/pt/info/..*")
        path("/pt/episodios/..*")
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
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
