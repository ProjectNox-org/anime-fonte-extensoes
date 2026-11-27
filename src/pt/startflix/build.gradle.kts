import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Startflix"
    versionCode = 9
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "pt-BR"
        baseUrl = "https://www.startflix.biz"
    }

    deeplink {
        host("www.startflix.biz")
        path("/.*")
    }
}

dependencies {
    implementation(project(":lib-multisrc:dooplay"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:filemoonextractor"))
    implementation(project(":lib:vidsrcextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:universalextractor"))
}
