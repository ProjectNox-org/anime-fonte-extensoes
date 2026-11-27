import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anikatsu"
    versionCode = 7
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://anikatsu.top"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:voeextractor"))
}
