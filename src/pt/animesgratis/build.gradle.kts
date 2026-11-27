import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "TopAnimes"
    versionCode = 51
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        id = 2969482460524685571L
        baseUrl = "https://topanimes.net"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:m3u8server"))
    implementation(project(":lib:mixdropextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:streamtapeextractor"))
    implementation(project(":lib:streamwishextractor"))
}
