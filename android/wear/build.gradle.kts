import java.util.Properties
import javax.inject.Inject

// Pocket Nomad for Wear OS: a standalone watch app (no phone needed) with SOS,
// compass and position, and first-aid steps. A port of the Apple Watch app in
// Watch/. No Google Play Services, so there is no phone link yet (see docs/ANDROID.md).
//
// It shares the phone app's applicationId so Google Play can pair the two later.
// Signing works the same way as :app: private/android-signing/keystore.properties
// (git-ignored) if it exists, otherwise release builds are left unsigned.
val keystorePropertiesFile = rootProject.file("../private/android-signing/keystore.properties")
val hasSigningConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasSigningConfig) keystorePropertiesFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Copies the release APK to build/outputs/pocket-nomad-wear.apk, the name used for GitHub Releases. */
abstract class NameApkTask : DefaultTask() {
    @get:InputFiles
    abstract val apkDir: DirectoryProperty

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun copy() {
        val apk = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }?.firstOrNull()
            ?: throw GradleException("No APK found in ${apkDir.get().asFile}")
        apk.copyTo(output.get().asFile, overwrite = true)
    }
}

/**
 * Copies index.json and the urgent guides from App/Resources/Guides into the APK's
 * assets at build time, like the Apple Watch target does, so the watch shows the
 * same "Do this now" steps as the phones and nothing is duplicated in git.
 */
abstract class CopyGuidesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val guides: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(guides) {
                include("index.json", "**/*.html")
            }
            into(outputDir.dir("Guides"))
        }
    }
}

android {
    namespace = "org.nomadpocket.wear"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Same as the phone app, so Play treats them as one app on two kinds of device.
        applicationId = "org.nomadpocket.app"
        // Wear OS 3 and later.
        minSdk = 30
        targetSdk = 37
        // Play needs every APK under one package to have its own version code:
        // the watch uses 10000 + the phone app's.
        versionCode = 10002
        versionName = "0.2.0-beta"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Like :app, a debug build installs beside a release build.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Pocket Nomad (debug)")
        }
        release {
            resValue("string", "app_name", "Pocket Nomad")
            // Shrinks the APK from about 27 MB to a size that suits a watch. The
            // libraries ship their own keep rules; the activity and tile are kept
            // because the manifest names them.
            optimization {
                enable = true
            }
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

androidComponents {
    onVariants { variant ->
        val copyGuides = tasks.register<CopyGuidesTask>(
            "copy${variant.name.replaceFirstChar { it.uppercase() }}WearGuides"
        ) {
            guides.set(rootProject.layout.projectDirectory.dir("../App/Resources/Guides"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyGuides, CopyGuidesTask::outputDir)
        if (variant.buildType == "release") {
            val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
            val named = tasks.register<NameApkTask>("nameReleaseWearApk") {
                this.apkDir.set(apkDir)
                output.set(layout.buildDirectory.file("outputs/pocket-nomad-wear.apk"))
            }
            // `./gradlew :wear:assembleRelease` also leaves build/outputs/pocket-nomad-wear.apk.
            tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(named) }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.androidx.concurrent.futures)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
