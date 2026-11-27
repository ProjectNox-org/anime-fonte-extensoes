import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Animes HD"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animeshd.to"
    }
}

dependencies {
    implementation(project(":lib-multisrc:dooplay"))
    implementation(project(":lib:bloggerextractor"))
}
