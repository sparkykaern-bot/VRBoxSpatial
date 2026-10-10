plugins { id("com.android.application") }

android {
    namespace = "com.apollo.vrboxspatial"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apollo.vrboxspatial"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.9.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.ar:core:1.56.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
}
