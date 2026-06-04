import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Release signing material lives outside the repo (passwords + keystore on ProtonDrive).
// local.properties holds the key `tsgbheater.signing.config` pointing at a keystore.properties
// file. When that's absent the release build stays unsigned so other machines still build.
val signingProps: Properties? = run {
    val localProps = rootProject.file("local.properties")
    if (!localProps.exists()) return@run null
    val configPath = Properties()
        .apply { FileInputStream(localProps).use { load(it) } }
        .getProperty("tsgbheater.signing.config") ?: return@run null
    val configFile = File(configPath)
    if (!configFile.exists()) return@run null
    Properties().apply { FileInputStream(configFile).use { load(it) } }
}

android {
    namespace   = "uk.co.twinscrollgridbalancer.tsgbheater"
    compileSdk  = 35

    defaultConfig {
        applicationId = "uk.co.twinscrollgridbalancer.tsgbheater"
        minSdk        = 31
        targetSdk     = 35
        versionCode   = 20299
        versionName   = "0.2.99-RC"
    }

    signingConfigs {
        signingProps?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingProps?.let {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Remote API client (talks to the Windows app's HMAC server).
    implementation(libs.okhttp)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // Google Play Billing — Pro unlock, supporter tiers, yearly subscription.
    implementation(libs.billing.ktx)
}
