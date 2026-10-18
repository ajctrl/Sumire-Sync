buildscript {
    dependencies {
        // AGP's built-in Kotlin otherwise brings in the vulnerable 2.2.10 plugin.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        constraints {
            // Patch AGP's build-time dependencies, not just the APK/test classpath.
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            classpath("org.apache.commons:commons-lang3:3.18.0")
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
        }
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
}
