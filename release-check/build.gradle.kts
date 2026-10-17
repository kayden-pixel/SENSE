plugins { id("com.android.application") }

// A separate process drives the consumer APK through Android accessibility.
// It has no dependency on :app or its optimized Kotlin/Compose classes.
android {
    namespace = "app.sense.releasecheck"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "app.sense.releasecheck"
        minSdk = 28
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
