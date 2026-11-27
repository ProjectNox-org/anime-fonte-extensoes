import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SmartAnimes"
    versionCode = 4
    contentWarning = ContentWarning.MIXED
    libVersion = "16"
    theme = "animestream"

    source {
        lang = "pt-BR"
        baseUrl = "https://smartanimes.net"
    }
}

dependencies {
    implementation(project(":lib:googledriveplayerextractor"))
}
