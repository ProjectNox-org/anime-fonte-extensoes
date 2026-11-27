import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesOnlines Net"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animesonlines.net"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:m3u8server"))
}
