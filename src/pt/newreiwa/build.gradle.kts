import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "New Reiwa"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://newreiwa.com"
    }
}

dependencies {
    implementation(project(":lib:googledriveextractor"))
    implementation(project(":lib:googledriveepisodes"))
}
