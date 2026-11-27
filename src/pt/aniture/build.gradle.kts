import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Aniture PT"
    versionCode = -2
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://aniture-pt.com.br"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:unpacker"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:m3u8server"))
}
