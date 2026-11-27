import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "DattebayoBR"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        name = "Dattebayo BR"
        lang = "pt-BR"
        baseUrl = "https://www.dattebayo-br.com"
    }
}
