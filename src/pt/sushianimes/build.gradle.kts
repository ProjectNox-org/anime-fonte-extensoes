import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "SushiAnimes"
    versionCode = 6
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        name = "Sushi Animes"
        lang = "pt-BR"
        baseUrl = "https://sushianimes.com.br"
    }

    deeplink {
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
