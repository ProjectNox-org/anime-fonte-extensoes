import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesDrive"
    versionCode = 18
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
    theme = "dooplay"

    source {
        name = "Animes Drive"
        lang = "pt-BR"
        baseUrl = "https://animesdrive.online"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:playlistutils"))
}
