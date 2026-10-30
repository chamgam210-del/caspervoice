plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// JitPack sets GROUP and VERSION from the GitHub repo and tag it builds.
group = System.getenv("GROUP") ?: "com.casper"
version = System.getenv("VERSION") ?: "0.1.0"

android {
    namespace = "com.casper.voice"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The library has no native code of its own, so this only keeps the on-phone
    // test APK small; apps using the library choose their own ABIs.
    packaging {
        jniLibs.excludes += listOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

// The on-phone provider tests read keys from an env.properties in test-keys/ at the
// root of the build (git-ignored), or in the folder -PvoiceTestKeys names. Without
// one, tests that need a key are skipped.
val testKeys = rootProject.file(providers.gradleProperty("voiceTestKeys").getOrElse("test-keys"))
androidComponents {
    onVariants { variant ->
        if (testKeys.resolve("env.properties").exists()) {
            variant.androidTest?.sources?.assets?.addStaticSourceDirectory(testKeys.absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.onnxruntime.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar's org.json is a stub under unit tests.
    testImplementation("org.json:json:20240303")

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// `gradlew :voice:publishReleasePublicationToLocalRepository` writes the AAR, POM
// and sources to voice/build/repo. Nothing here uploads anywhere.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "caspervoice"
                pom {
                    name.set("CasperVoice")
                    description.set(
                        "A full-duplex voice for Android apps: echo-cancelled microphone, Silero speech detection, " +
                            "turn-taking, barge-in, and swappable speech-to-text and text-to-speech providers."
                    )
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                }
            }
        }
        repositories {
            maven {
                name = "local"
                url = uri(layout.buildDirectory.dir("repo"))
            }
        }
    }
}
