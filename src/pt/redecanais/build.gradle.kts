import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Rede Canais"
    versionCode = 5
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://redecanais.plus"
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}
