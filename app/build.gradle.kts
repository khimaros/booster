plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// release builds take their version from the git tag (-Pbooster.version=1.2.3); the code orders
// versions numerically, two digits per minor and patch
val boosterVersion = (findProperty("booster.version") as String?) ?: "0.1.0"
val boosterVersionCode = boosterVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    require(minor < 100 && patch < 100) { "version parts above 99: $boosterVersion" }
    major * 10000 + minor * 100 + patch
}

// a release is signed only when a keystore is provided, as the tag workflow does
val keystore: String? = System.getenv("BOOSTER_KEYSTORE")

android {
    namespace = "com.khimaros.booster"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.khimaros.booster"
        minSdk = 26
        targetSdk = 35
        versionCode = boosterVersionCode
        versionName = boosterVersion
    }

    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("BOOSTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BOOSTER_KEY_ALIAS")
                keyPassword = System.getenv("BOOSTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkReleaseBuilds = false
        // ascii only: "..." rather than the ellipsis character
        disable += "TypographyEllipsis"
        // target sdk follows the api level the end-to-end emulator runs
        disable += "OldTargetApi"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
