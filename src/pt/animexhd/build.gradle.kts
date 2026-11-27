import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeXHD"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://animexhd.xyz"
    }
}

dependencies {
    implementation(project(":lib-multisrc:dooplay"))
    implementation(project(":lib:bloggerextractor"))
    implementation(project(":lib:fireplayerextractor"))
}
