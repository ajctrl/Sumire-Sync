import java.util.Base64

plugins {
    id("com.android.application")
}

val releaseStorePassword = providers.gradleProperty("ANDROID_KEYSTORE_PASSWORD")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD"))
val releaseKeyAlias = providers.gradleProperty("ANDROID_KEY_ALIAS")
    .orElse(providers.environmentVariable("ANDROID_KEY_ALIAS"))
val releaseKeyPassword = providers.gradleProperty("ANDROID_KEY_PASSWORD")
    .orElse(providers.environmentVariable("ANDROID_KEY_PASSWORD"))
val releaseKeystoreBase64 = providers.gradleProperty("ANDROID_KEYSTORE_BASE64")
    .orElse(providers.environmentVariable("ANDROID_KEYSTORE_BASE64"))
val releaseStoreFile = rootProject.layout.buildDirectory.file("signing/release.jks").get().asFile

if (releaseKeystoreBase64.isPresent) {
    releaseStoreFile.parentFile.mkdirs()
    releaseStoreFile.writeBytes(Base64.getDecoder().decode(releaseKeystoreBase64.get()))
}

android {
    namespace = "com.ajctrl.sumiresync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.sumiresync"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "0.8.0"

        buildConfigField(
            "String",
            "SUMIRE_PACKAGE_IDS",
            "\"com.example.sumire,com.example.sumire.lite,com.example.sumire.lite.fdroid\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile.takeIf { it.exists() }
                ?: rootProject.file("ci/shared-release.jks")
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.orNull
            keyPassword = releaseKeyPassword.orNull
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
