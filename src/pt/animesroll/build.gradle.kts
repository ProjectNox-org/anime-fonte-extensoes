import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimesROLL"
    versionCode = 22
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://anroll.io"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:m3u8server"))
}
