import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem
import java.util.Properties

val protocolVersions = Properties().apply {
    rootProject.file("../config/protocol_versions.properties").inputStream().use(::load)
}
val beetMaintenanceProtocolVersion = protocolVersions.getProperty("maintenance_protocol_version")
    ?: error("Missing maintenance_protocol_version in config/protocol_versions.properties")
val beetRuntimeProtocolVersion = protocolVersions.getProperty("runtime_protocol_version")
    ?: error("Missing runtime_protocol_version in config/protocol_versions.properties")

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.aarondietz.beetmeister"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.aarondietz.beetmeister"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("int", "BEET_MAINTENANCE_PROTOCOL_VERSION", beetMaintenanceProtocolVersion)
        buildConfigField("int", "BEET_RUNTIME_PROTOCOL_VERSION", beetRuntimeProtocolVersion)
    }

    signingConfigs {
        create("ciRelease") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        assets.srcDir(layout.buildDirectory.dir("generated/bundledFirmware/main/assets").get().asFile)
    }
}

val bundledFirmwareAssetsDir = layout.buildDirectory.dir("generated/bundledFirmware/main/assets/firmware")

val prepareBundledFirmware by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds or stages the bundled BeetMeister firmware asset for the app."

    val output = bundledFirmwareAssetsDir.get().asFile.absolutePath
    val prebuiltImage = System.getenv("BEET_BUNDLED_FIRMWARE_IMAGE")

    val shell = if (OperatingSystem.current().isWindows) "powershell" else "pwsh"
    commandLine(
        shell,
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        rootProject.file("../scripts/release/export-bundled-firmware.ps1").absolutePath,
        "-OutputDir",
        output,
    )
    if (!prebuiltImage.isNullOrBlank()) {
        args("-PrebuiltImagePath", prebuiltImage)
    }
}

tasks.matching { task ->
    task.name == "preBuild"
}.configureEach {
    dependsOn(prepareBundledFirmware)
}

dependencies {
    implementation(platform(libs.koin.bom))
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
