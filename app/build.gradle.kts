plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A release-only choice. Debug/diagnostic never inherit this value.
val releaseAds = providers.gradleProperty("hagemashi.releaseAds").orElse("test").get()
require(releaseAds in setOf("off", "test", "production")) { "hagemashi.releaseAds must be off, test or production" }
val releaseVersionCode = providers.gradleProperty("hagemashi.versionCode").orElse("2").get().toIntOrNull() ?: error("Invalid versionCode")
require(releaseVersionCode in 2..2100000000) { "versionCode must be an integer from 2 to 2100000000" }
val releaseVersionName = providers.gradleProperty("hagemashi.versionName").orElse("1.0.1").get()
require(releaseVersionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,49}"))) { "Invalid versionName" }
val sourceCommit = System.getenv("GITHUB_SHA") ?: "local"
require(sourceCommit.matches(Regex("[A-Za-z0-9._-]{1,100}")))
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
        getByName("release") {
            // Preserve verified reflection, JSON and component behavior; no R8/resource shrink change.
            isMinifyEnabled = false
            isShrinkResources = false
        }
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
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = releaseVersionName
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

// Register generated assets through the AGP Variant API so packaging AND lint models
// receive the producing-task dependency (not just an untracked directory).
abstract class AdDescriptorTask : org.gradle.api.DefaultTask() {
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDir: org.gradle.api.file.DirectoryProperty
    @get:org.gradle.api.tasks.Input
    abstract val mode: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input
    abstract val appId: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input
    abstract val bannerId: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Input
    abstract val buildInfo: org.gradle.api.provider.Property<String>
    @org.gradle.api.tasks.TaskAction
    fun generate() {
        val directory = outputDir.get().asFile.apply { mkdirs() }
        directory.resolve("build-info.json").writeText(buildInfo.get())
        directory.resolve("ad-config.json").writeText(groovy.json.JsonOutput.toJson(mapOf(
            "schema" to 1, "mode" to mode.get(), "applicationId" to appId.get(), "bannerUnitId" to bannerId.get(),
            "privacyGate" to if(mode.get() == "production") "UMP" else "TEST_ONLY")))
    }
}
androidComponents {
    onVariants(selector().all()) { variant ->
        val selectedMode = if(variant.buildType == "release") releaseAds else "test"
        val task = tasks.register<AdDescriptorTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}AdDescriptor") {
            outputDir.set(layout.buildDirectory.dir("generated/ad-config/${variant.name}"))
            buildInfo.set(groovy.json.JsonOutput.toJson(mapOf("sourceCommit" to sourceCommit,
                "versionCode" to releaseVersionCode, "versionName" to releaseVersionName,
                "targetSdk" to 36, "minSdk" to 26, "minifyEnabled" to false, "shrinkResources" to false)))
            mode.set(selectedMode)
            appId.set(if(selectedMode == "production") liveAppId else if(selectedMode == "test") demoAppId else "")
            bannerId.set(if(selectedMode == "production") liveBannerId else if(selectedMode == "test") demoBannerId else "")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, AdDescriptorTask::outputDir)
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
