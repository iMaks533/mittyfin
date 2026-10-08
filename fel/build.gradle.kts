// GPU FEL path: Dolby Vision profile 7 FEL composed on the GPU from two ordinary HEVC decoder instances.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mittyfin.fel"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api("androidx.media3:media3-exoplayer:1.8.0")
    testImplementation("junit:junit:4.13.2")
}
