plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

fun git(vararg args: String): String? {
    val exec = providers.exec { commandLine("git", *args); isIgnoreExitValue = true }
    return if (exec.result.get().exitValue == 0) exec.standardOutput.asText.get() else null
}
val appVersion = AppVersion.fromGit(git("describe", "--tags", "--match", "v*", "--dirty"), git("rev-list", "--count", "HEAD"))

android {
    namespace = "app.mittyfin"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.mittyfin"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // The release key lives outside the repo; its path and passwords come from ~/.gradle/gradle.properties.
        val storePath = providers.gradleProperty("mittyfin.storeFile").orNull
        if (storePath != null) create("release") {
            storeFile = file(storePath)
            storePassword = providers.gradleProperty("mittyfin.storePassword").get()
            keyAlias = providers.gradleProperty("mittyfin.keyAlias").get()
            keyPassword = providers.gradleProperty("mittyfin.keyPassword").get()
        }
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    applicationVariants.all {
        if (buildType.name == "release") outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = "Mittyfin-$versionName.apk"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":fel"))

    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.8.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    // libass for ASS/SSA subtitles (the same build Nuvio uses, Media3 1.8).
    implementation("io.github.peerless2012:ass-media:0.4.0")
    // FFmpeg audio decoders (TrueHD, DTS, ...) built by the Jellyfin project for Media3.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.8.0+1")
    testImplementation("junit:junit:4.13.2")
}
