plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.datalogic.largeshareapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.datalogic.largeshareapp"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // android.util.Log etc. return defaults in plain JVM unit tests.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.core)

    testImplementation(libs.junit)
    // Real org.json for JVM tests (the Android stubs only throw).
    testImplementation("org.json:json:20231013")
}