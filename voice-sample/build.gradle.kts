plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.casper.voice.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.casper.voice.sample"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Debug builds can prefill the keys from test-keys/env.properties (git-ignored).
val testKeys = rootProject.file(providers.gradleProperty("voiceTestKeys").getOrElse("test-keys"))
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (testKeys.resolve("env.properties").exists()) {
            variant.sources.assets?.addStaticSourceDirectory(testKeys.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":voice"))
    implementation(libs.kotlinx.coroutines.android)
}
