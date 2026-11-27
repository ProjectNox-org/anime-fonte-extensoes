import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Tomato"
    versionCode = 19
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://prod-api.tomatoanimes.com"
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}
