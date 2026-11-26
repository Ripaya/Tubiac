plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "tube.maniac"
    compileSdk = 37

    defaultConfig {
        applicationId = "tube.maniac"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = System.getenv("TUBIAC_VERSION_NAME") ?: "0.1.0-alpha1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("alphaRelease") {
            val keystorePath = System.getenv("TUBIAC_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TUBIAC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TUBIAC_KEY_ALIAS")
                keyPassword = System.getenv("TUBIAC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("alpha") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".alpha"
            versionNameSuffix = ""
            isDebuggable = true
            val alphaSigning = signingConfigs.getByName("alphaRelease")
            signingConfig = if (alphaSigning.storeFile != null) alphaSigning else signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug", "release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:extension-api"))
    implementation(project(":core:extension-loader"))
    implementation(project(":core:anilist"))
    implementation(project(":feature:home"))
    implementation(project(":feature:shelf"))
    implementation(project(":feature:browse"))
    implementation(project(":feature:detail"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
}
