plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A release-only choice. Debug/diagnostic never inherit this value.
val releaseAds = providers.gradleProperty("hagemashi.releaseAds").orElse("test").get()
require(releaseAds in setOf("off", "test", "production")) { "hagemashi.releaseAds must be off, test or production" }
val demoAppId = "ca-app-pub-3940256099942544~3347511713"
val demoBannerId = "ca-app-pub-3940256099942544/9214589741"
val liveAppId = "ca-app-pub-3595062014413653~8954573313"
val liveBannerId = "ca-app-pub-3595062014413653/1814449890"

android {
    namespace = "com.yosikou.hagemashi"
    compileSdk = 36
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

    buildTypes.all {
        val mode = if(name == "release") releaseAds else "test"
        val enabled = mode != "off"
        val appId = if(mode == "production") liveAppId else if(enabled) demoAppId else ""
        val bannerId = if(mode == "production") liveBannerId else if(enabled) demoBannerId else ""
        buildConfigField("String", "AD_MODE", "\"$mode\"")
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$bannerId\"")
        manifestPlaceholders["adsMode"] = mode
        manifestPlaceholders["admobAppId"] = appId
        manifestPlaceholders["admobBannerId"] = bannerId
        val descriptor = tasks.register("generate${name.replaceFirstChar { it.uppercase() }}AdDescriptor") {
            val target = layout.buildDirectory.dir("generated/ad-config/$name")
            inputs.properties(mapOf("mode" to mode, "appId" to appId, "bannerId" to bannerId))
            outputs.dir(target)
            doLast {
                val directory = target.get().asFile.apply { mkdirs() }
                directory.resolve("ad-config.json").writeText(groovy.json.JsonOutput.toJson(mapOf(
                    "schema" to 1, "mode" to mode, "applicationId" to appId, "bannerUnitId" to bannerId,
                    "privacyGate" to if(mode == "production") "UMP" else "TEST_ONLY")))
            }
        }
        sourceSets.getByName(name).assets.srcDir(descriptor)

    }
    sourceSets {
        listOf("debug", "diagnostic", "release").forEach { variant ->
            val ads = variant != "release" || releaseAds != "off"
            getByName(variant).java.srcDir(if(ads) "src/ads/java" else "src/noAds/java")
            if(ads)getByName(variant).manifest.srcFile("src/ads/AndroidManifest.xml")
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
    listOf("debug", "diagnostic", "release").filter { it != "release" || releaseAds != "off" }.forEach { variant ->
        add("${variant}Implementation", "com.google.android.gms:play-services-ads:25.5.0")
        add("${variant}Implementation", "com.google.android.ump:user-messaging-platform:4.0.0")
    }
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
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
