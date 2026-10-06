import org.gradle.jvm.toolchain.JavaToolchainService
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
    alias(libs.plugins.ksp)
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

        testInstrumentationRunner = "de.aarondietz.beetmeister.e2e.BeetE2eAwareJUnitRunner"
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric does not yet run on the JDK 25 default toolchain; pin the
        // unit-test JVM to the installed JDK 17 (tests execute there, compile stays).
        unitTests.all {
            it.javaLauncher.set(
                project.extensions.getByType<JavaToolchainService>()
                    .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) },
            )
            // Robolectric sandbox reaches into JDK internals; required module flags
            // for JDK 17+ (FileDescriptor/SharedSecrets access).
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
            )
        }
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
    implementation(libs.slf4j.api)
    implementation(libs.logback.android)
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
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
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.koin.bom))
    androidTestImplementation(libs.koin.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
