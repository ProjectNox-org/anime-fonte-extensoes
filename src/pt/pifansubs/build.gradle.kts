import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Pi Fansubs"
    versionCode = 39
    contentWarning = ContentWarning.NSFW
    libVersion = "16"
    theme = "dooplay"

    source {
        lang = "pt-BR"
        baseUrl = "https://pifansubs.club"
    }
}

dependencies {
    implementation(project(":lib:vidhideextractor"))
}
