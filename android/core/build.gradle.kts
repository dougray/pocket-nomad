// :core is the pure Kotlin shared by the phone app (:app) and the watch app (:wear):
// position formats (decimal, DMS, MGRS), Morse timing and the first-aid step
// extraction. No Android UI and no dependencies, so both apps show the same numbers.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.nomadpocket.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        // Reads the real guides from App/Resources/Guides.
        unitTests.all { it.systemProperty("guidesDir", rootProject.file("../App/Resources/Guides").absolutePath) }
    }
}

dependencies {
    testImplementation(libs.junit)
    // org.json is part of Android; JVM unit tests need the real one.
    testImplementation(libs.json)
}
