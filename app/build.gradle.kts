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
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.example.sumiresync"
        minSdk = 30
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.0"

        buildConfigField(
            "String",
            "SUMIRE_PACKAGE_IDS",
            "\"com.example.sumire,com.example.sumire.lite,com.example.sumire.lite.fdroid\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

// AGP creates separate configurations for lint and the Unified Test Platform.
// Constraints inherited by all configurations also cover those tool classpaths;
// constraints alone do not add these libraries to the APK.
val securityConstraints by configurations.creating {
    isCanBeResolved = false
    isCanBeConsumed = false
}
configurations.configureEach {
    if (name != securityConstraints.name) {
        extendsFrom(securityConstraints)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    constraints {
        listOf(
            "netty-buffer", "netty-codec", "netty-codec-http", "netty-codec-http2",
            "netty-codec-socks", "netty-common", "netty-handler", "netty-handler-proxy",
            "netty-resolver", "netty-transport", "netty-transport-native-unix-common"
        ).forEach { module ->
            add(securityConstraints.name, "io.netty:$module:4.1.137.Final") {
                because("Patch the Netty 4.1 modules used by AGP's device test tools together")
            }
        }
        add(securityConstraints.name, "org.apache.httpcomponents:httpclient:4.5.14") {
            because("AGP's separate tool configurations can still resolve httpclient 4.5.6")
        }
        add(securityConstraints.name, "org.jdom:jdom2:2.0.6.1") {
            because("Patch XXE in the XML parser used by Android build tools")
        }
        testImplementation("org.bouncycastle:bcprov-jdk18on:1.86") {
            because("Use the patched provider for Robolectric's transitive test dependency")
        }
    }
}
