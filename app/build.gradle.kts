plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yosikou.hagemashi"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    val buildIdentifier = System.getenv("HAGEMASHI_BUILD_ID") ?: "local"
    require(buildIdentifier.matches(Regex("[A-Za-z0-9._-]{1,100}")))
    val signingPath = System.getenv("HAGEMASHI_SIGNING_FILE")
    signingConfigs {
        getByName("debug") {
            if (signingPath != null) {
                storeFile = file(signingPath)
                storeType = "PKCS12"
                storePassword = System.getenv("HAGEMASHI_SIGNING_PASSWORD")
                keyAlias = "hagemashi-diagnostic"
                keyPassword = System.getenv("HAGEMASHI_SIGNING_PASSWORD")
            }
        }
    }
    buildTypes {
        create("diagnostic") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".diagnostic"
            versionNameSuffix = "-diagnostic"
            matchingFallbacks += "debug"
        }
    }

    defaultConfig {
        applicationId = "com.yosikou.hagemashi"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "BUILD_IDENTIFIER", "\"$buildIdentifier\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
