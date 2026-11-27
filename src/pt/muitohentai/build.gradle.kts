import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Muito Hentai"
    versionCode = 6
    contentWarning = ContentWarning.NSFW
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.muitohentai.com"
    }
}
