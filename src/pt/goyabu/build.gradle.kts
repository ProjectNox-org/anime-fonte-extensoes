import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Goyabu"
    versionCode = 10
    contentWarning = ContentWarning.MIXED
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://goyabu.io"
    }

    deeplink {
        path("/..*")
        path("/anime/..*")
    }

    // Old domains: keep in sync with LEGACY_HOSTS in Goyabu.kt
    deeplink {
        host("goyabu.to")
        path("/..*")
        path("/anime/..*")
    }
}

dependencies {
    implementation(project(":lib:bloggerextractor"))
}
