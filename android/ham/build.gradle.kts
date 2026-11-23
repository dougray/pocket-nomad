// :ham is Pocket Nomad's ham radio layer: a KISS TNC over Bluetooth (LE on
// every phone, classic SPP too on Android), AX.25 connected mode, APRS,
// Winlink email (B2F) and the packet terminal, plus the Ham screens.
// A port of App/Sources/Ham in the iOS app.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.nomadpocket.ham"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
    }
}

// The FT8 test recordings are shared with the iOS tests: one copy, in AppTests/Fixtures/Ft8.
androidComponents {
    onVariants { variant ->
        (variant as? com.android.build.api.variant.HasUnitTest)?.unitTest?.sources?.resources?.addStaticSourceDirectory(rootProject.layout.projectDirectory.dir("../AppTests/Fixtures/Ft8").asFile.path)
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":mesh"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
