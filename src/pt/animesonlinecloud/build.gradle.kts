import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesOnlineCloud"
    versionCode = 14
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "dooplay"

    source {
        name = "Animes Online Cloud"
        lang = "pt-BR"
        baseUrl = "https://animesonline.cloud"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
