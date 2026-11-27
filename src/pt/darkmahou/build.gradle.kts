import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DarkMahou"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "animestream"
    torrent = true

    source {
        name = "DarkMahou (Torrent)"
        lang = "pt-BR"
        baseUrl = "https://darkmahou.io"
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
}
