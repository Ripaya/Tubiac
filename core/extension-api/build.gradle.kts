import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tube.maniac.core.extension.api"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.jsoup)
    api(libs.rxjava)
    api(libs.quickjs)
    api(libs.androidx.preference.ktx)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.json.okio)
    api(libs.kotlinx.serialization.protobuf)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
}
