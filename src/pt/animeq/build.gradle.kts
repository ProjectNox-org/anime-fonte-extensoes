import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeQ"
    versionCode = 14
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://animeq.cloud"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
