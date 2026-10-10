plugins { id("com.android.application") }

android {
    namespace = "com.apollo.vrboxspatial"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apollo.vrboxspatial"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
