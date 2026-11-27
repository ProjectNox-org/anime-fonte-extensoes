import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Meus Animes"
    versionCode = 9
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://meusanimes.blog"
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
