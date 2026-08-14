plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The OAuth client id lives in exactly one place: the `OAUTH_CLIENT_ID` constant
 * in Config.kt. The manifest needs the *reversed* form of it as a custom URL
 * scheme, so it is derived here and injected as a manifest placeholder rather
 * than being written out a second time (two copies drift; one does not).
 */
val oauthClientId: String = run {
    val configFile = file("src/main/java/com/framecut/app/Config.kt")
    val match = Regex("""OAUTH_CLIENT_ID\s*=\s*"([^"]+)"""").find(configFile.readText())
    requireNotNull(match) { "Could not find OAUTH_CLIENT_ID in ${configFile.path}" }
    match.groupValues[1]
}

// "1234-abcd.apps.googleusercontent.com" -> "com.googleusercontent.apps.1234-abcd"
val oauthRedirectScheme: String =
    "com.googleusercontent.apps." + oauthClientId.removeSuffix(".apps.googleusercontent.com")

android {
    namespace = "com.framecut.app"
    compileSdk = 34
    // Pinned: only build-tools 34.0.0 is installed locally, and AGP 8.7 would
    // otherwise default to 35.0.0 and try to download it.
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.framecut.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["oauthRedirectScheme"] = oauthRedirectScheme
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Version-freshness noise: dependency versions are pinned deliberately
        // to what is already in the local Gradle cache.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
        // <adaptive-icon> is only linkable from a -v26 folder, so the qualifier
        // is required even though minSdk is already 26.
        disable += "ObsoleteSdkInt"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
}
