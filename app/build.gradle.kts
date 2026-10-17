import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val productionBackendUrl =
    Properties()
        .apply { rootProject.file("config/production.properties").inputStream().use { load(it) } }
        .getProperty("backendUrl", "")
        .trimEnd('/')
val releaseKeystorePath = providers.environmentVariable("SENSE_KEYSTORE_PATH").orNull

val backendUrl =
    (providers.gradleProperty("sense.backendUrl").orNull
            ?: System.getenv("SENSE_BACKEND_URL")
            ?: productionBackendUrl)
        .trimEnd('/')

require(
    backendUrl.isEmpty() ||
        URI(backendUrl).let {
            it.scheme == "https" &&
                it.host != null &&
                it.rawUserInfo == null &&
                it.rawQuery == null &&
                it.rawFragment == null
        }
) {
    "SENSE_BACKEND_URL must be a public HTTPS URL without credentials, query or fragment"
}

android {
    namespace = "app.sense"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        applicationId = "app.sense"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BACKEND_URL", "\"" + backendUrl + "\"")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.useLegacyPackaging = false
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
    }
    signingConfigs {
        if (releaseKeystorePath != null)
            create("production") {
                storeType = "PKCS12"
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("SENSE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("SENSE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("SENSE_KEY_PASSWORD").orNull
            }
    }
    buildTypes {
        debug { buildConfigField("String", "BACKEND_URL", "\"" + backendUrl + "\"") }
        release {
            buildConfigField("String", "BACKEND_URL", "\"" + productionBackendUrl + "\"")
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

val verifyProductionBackend by
    tasks.registering(Exec::class) {
        group = "verification"
        description = "Refuse production packaging until the official service and signing are ready"
        workingDir(rootProject.projectDir)
        commandLine("node", "tools/check_release.mjs", "config/production.properties")
    }

tasks
    .matching { it.name in setOf("packageRelease", "assembleRelease", "bundleRelease") }
    .configureEach { dependsOn(verifyProductionBackend) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.1")
    implementation("androidx.room:room-ktx:2.7.1")
    ksp("androidx.room:room-compiler:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("io.livekit:livekit-android:2.29.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
