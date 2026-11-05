import java.util.Properties
import javax.inject.Inject

// Pocket Nomad for Android: the phone app. The radio layer and its screens are in :mesh;
// the offline-book reader and full-text search (no UI) are in :library.
//
// Signing: the keystore path and passwords live in the git-ignored
// private/android-signing/keystore.properties at the repo root, never in this file.
// Without it, release builds are left unsigned rather than failing.
val keystorePropertiesFile = rootProject.file("../private/android-signing/keystore.properties")
val hasSigningConfig = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasSigningConfig) keystorePropertiesFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Copies the iOS app's bundled guides (App/Resources/Guides) into the APK's assets
 * at build time, so iOS and Android share one source and nothing is duplicated in git.
 */
/** Copies the release APK to build/outputs/pocket-nomad.apk, the name used for GitHub Releases. */
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
            from(guides)
            into(outputDir.dir("Guides"))
        }
    }
}

android {
    namespace = "org.nomadpocket.app"
    compileSdk {
        version = release(37)
    }
    // The same NDK as :llm, so the packaging step can strip libnomadllm.so (54 MB -> 6 MB).
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "org.nomadpocket.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.4.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        // A debug build installs beside a release build instead of replacing it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Pocket Nomad (debug)")
        }
        release {
            resValue("string", "app_name", "Pocket Nomad")
            optimization {
                enable = false
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
        // Robolectric reads the merged assets (the copied guides) and manifest.
        unitTests.isIncludeAndroidResources = true
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
            "copy${variant.name.replaceFirstChar { it.uppercase() }}Guides"
        ) {
            guides.set(rootProject.layout.projectDirectory.dir("../App/Resources/Guides"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copyGuides, CopyGuidesTask::outputDir)
        if (variant.buildType == "release") {
            val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
            val named = tasks.register<NameApkTask>("nameReleaseApk") {
                this.apkDir.set(apkDir)
                output.set(layout.buildDirectory.file("outputs/pocket-nomad.apk"))
            }
            // `./gradlew :app:assembleRelease` also leaves build/outputs/pocket-nomad.apk.
            tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(named) }
        }
    }
}

dependencies {
    implementation(project(":mesh"))
    implementation(project(":library"))
    implementation(project(":llm"))
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre.android)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    // Desktop builds of the SQLite and zstd that :library bundles, for the guide-index tests.
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.zstd.jni)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
