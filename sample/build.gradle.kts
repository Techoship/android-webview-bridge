plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.techoship.webviewbridge.sample"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.techoship.webviewbridge.sample"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":webview-bridge"))
    implementation(project(":webview-bridge-billing"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
