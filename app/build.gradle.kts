import java.util.UUID

val buildId = UUID.randomUUID().toString().substring(0, 12)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.customhud"
    compileSdk = 34
    setProperty("archivesBaseName", "CustomHUD-0.1.0-$buildId")

    defaultConfig {
        applicationId = "dev.customhud"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures.buildConfig = true

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions.jvmTarget = "17"

    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    // Same as CarLight: lets the read-only ECARX gear subscription reflect vendor framework members.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
