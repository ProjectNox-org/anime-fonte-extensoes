plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animes Digital"
    versionCode = 14
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animesdigital.org"
    }

    deeplink {
        path("/anime/a/..*")
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
    implementation(project(":lib:unpacker"))
    testImplementation(libs.junit)
    testImplementation(libs.bundles.common)
    testImplementation(project(":extensions-lib"))
}

tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("UnitTestKotlin") }.configureEach {
    enabled = false
}
