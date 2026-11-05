// :library reads offline books: Kiwix .zim archives (a Kotlin port of NomadKit's
// ZIMKit, written from the openZIM spec) and their SQLite FTS5 passage indexes
// (.nomadidx, a port of NomadSearch). No libzim or libkiwix: both are GPL.
// No UI here; the Library screens are in :app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.nomadpocket.library"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // The same small ZIM files NomadKit's tests use (NomadKit/Tests/Fixtures/fetch-fixtures.sh).
            // Tests that need one skip when it is missing.
            it.systemProperty("fixtures", rootProject.file("../NomadKit/Tests/Fixtures").absolutePath)
        }
    }
}

dependencies {
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.xz)
    // The .aar carries zstd built for each Android ABI; unit tests use the plain jar,
    // which carries it for desktop systems.
    implementation("${libs.zstd.jni.get()}@aar")
    testImplementation(libs.zstd.jni)
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.junit)
}
