@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.yausername.ffmpeg"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
kotlin {
    jvmToolchain(21)
}

// Local replacement for io.github.junkfood02.youtubedl-android:ffmpeg, carrying up-to-date
// ffmpeg jniLibs (see BUILD_NOTES.md and .github/workflows/build-ffmpeg.yml).
// Classes come from the published library/common artifacts so this module only owns the
// FFmpeg helper and the native binaries.
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:common:${libs.versions.youtubedlAndroid.get()}")
    compileOnly("io.github.junkfood02.youtubedl-android:library:${libs.versions.youtubedlAndroid.get()}")
    implementation("commons-io:commons-io:2.16.1")
}
