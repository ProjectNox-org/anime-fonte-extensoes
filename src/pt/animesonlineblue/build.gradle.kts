import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesOnline Blue"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animesonline.blue"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
