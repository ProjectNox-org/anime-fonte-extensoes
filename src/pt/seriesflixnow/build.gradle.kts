import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SeriesFlixNow"
    versionCode = 10
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.seriesflixnet.com"
    }
}

dependencies {
    implementation(project(":lib:vidsrcextractor"))
    implementation(project(":lib:fireplayerextractor"))
    implementation(project(":lib:playlistutils"))
}
