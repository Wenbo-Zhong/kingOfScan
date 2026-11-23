plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.kingscan.verification.sender"
    compileSdk = 35
    sourceSets["main"].assets.srcDir("../../fixtures")
    defaultConfig {
        applicationId = "com.kingscan.verification.sender"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
