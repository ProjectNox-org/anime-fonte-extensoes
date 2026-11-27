import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Donghua no Sekai"
    versionCode = 6
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://donghuanosekai.com"
    }

    deeplink {
        path("/..*")
    }
}
