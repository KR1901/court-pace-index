plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Read through providers so the configuration cache invalidates when these change.
val keystorePath: String? = providers.environmentVariable("COURT_PACE_KEYSTORE").orNull
val keystorePass: String? = providers.environmentVariable("COURT_PACE_KEYSTORE_PASSWORD").orNull
val keyAliasName: String? = providers.environmentVariable("COURT_PACE_KEY_ALIAS").orNull
val keyPass: String? = providers.environmentVariable("COURT_PACE_KEY_PASSWORD").orNull
val hasReleaseSigning = keystorePath != null && keystorePass != null &&
    keyAliasName != null && keyPass != null && file(keystorePath).exists()

// R8 is on by default. Small build machines can pass -Pcourtpace.minify=false to skip it;
// CI and normal developer machines should leave this alone.
val minifyRelease: Boolean = providers.gradleProperty("courtpace.minify").orNull
    ?.toBooleanStrictOrNull() ?: true

android {
    namespace = "ink.courtpace.index"
    compileSdk = 37

    defaultConfig {
        applicationId = "ink.courtpace.index"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "2.1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.kotlin_module")
        }
    }

    sourceSets {
        // The bundled dataset is read from the classpath by the JVM tests, so the tests
        // guard the same file the app ships.
        getByName("test").resources.srcDir("src/main/assets")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.android)
}


