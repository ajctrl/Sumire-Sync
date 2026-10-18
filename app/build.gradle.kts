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
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.sumiresync"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "0.9.0"

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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    constraints {
        testImplementation("org.bouncycastle:bcprov-jdk18on:1.86") {
            because("Use the patched provider for Robolectric's transitive test dependency")
        }
    }
}
