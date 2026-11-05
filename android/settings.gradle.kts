pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketNomad"
// :app is the phone app; :mesh is the radio layer (Meshtastic + MeshCore over
// Bluetooth, coverage recording, drive mode) with its own screens; :library reads
// offline Kiwix .zim books and searches their .nomadidx full-text indexes; :llm runs
// AI models on the phone (llama.cpp through JNI) for Ask.
include(":app", ":mesh", ":library", ":llm")
// :core is the pure maths both apps share (positions, Morse, first-aid steps);
// :wear is the standalone Wear OS watch app.
include(":core", ":wear")
