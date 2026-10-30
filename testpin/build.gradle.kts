// an app that requests a pinned shortcut, used as an end-to-end test fixture
plugins {
    id("com.android.application")
}

android {
    namespace = "com.khimaros.booster.testpin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khimaros.booster.testpin"
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
