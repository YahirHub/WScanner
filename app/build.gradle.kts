
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.thowilabs.wscanner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thowilabs.wscanner"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    // Iconics — librería de íconos profesional (Java-compatible)
    implementation("com.mikepenz:iconics-core:5.5.0")
    implementation("com.mikepenz:community-material-typeface:7.0.96.1-kotlin@aar")
}
