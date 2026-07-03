plugins {
    id("com.android.application")
}

import org.gradle.api.tasks.compile.JavaCompile

android {
    namespace = "com.codex.applockguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.codex.applockguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    lint {
        abortOnError = true
    }

    buildFeatures {
        buildConfig = true
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("com.google.zxing:core:3.5.3")
}
