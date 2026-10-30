// a minimal adw/nova style icon pack used as an end-to-end test fixture
plugins {
    id("com.android.application")
}

android {
    namespace = "com.khimaros.booster.testpack"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khimaros.booster.testpack"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
