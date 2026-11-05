import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

// :llm runs AI models on the phone: llama.cpp (MIT) built with the NDK, behind a
// small JNI wrapper (src/main/cpp) and a Kotlin API (org.nomadpocket.llm). It knows
// nothing about guides or books; Ask in :app does the retrieval and the prompt.
plugins {
    alias(libs.plugins.android.library)
}

/** The llama.cpp release this module is built from. Same tag as the iOS app (tools/fetch-vendor.sh). */
val llamaTag = "b11976"
val llamaSha256 = "248756d1e3eac86a12b37a658ca545cf98bc0d8203977cd3450ee1499f60023f"

/**
 * Downloads the pinned llama.cpp source archive once (kept in Gradle's cache, so
 * `clean` doesn't fetch it again), refuses it unless its SHA-256 matches, and unpacks
 * it for CMake. Builds without network access pass -Pnomad.llamaSourceDir instead.
 */
abstract class FetchLlamaTask : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:Internal abstract val archive: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun fetch() {
        val file = archive.get().asFile
        fun hash() = MessageDigest.getInstance("SHA-256").let { d ->
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
            }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        if (!file.exists() || hash() != sha256.get()) {
            file.parentFile.mkdirs()
            logger.lifecycle("Downloading ${url.get()}")
            // GitHub redirects to codeload.github.com; HttpURLConnection follows https to https.
            val conn = URI(url.get()).toURL().openConnection() as HttpURLConnection
            if (conn.responseCode != 200) throw GradleException("llama.cpp download failed: HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            val got = hash()
            if (got != sha256.get()) {
                file.delete()
                throw GradleException("llama.cpp archive checksum mismatch: expected ${sha256.get()}, got $got")
            }
        }
        fs.sync {
            from(archives.tarTree(archives.gzip(file)))
            into(outputDir)
        }
    }
}

val llamaSourceOverride = providers.gradleProperty("nomad.llamaSourceDir").orNull
/**
 * -Pnomad.skipNative builds without the NDK: the APK then has no libnomadllm.so,
 * `Llama.isAvailable` is false and Ask shows passages only. For machines without
 * the NDK and for quick JVM test runs; release builds must not use it.
 */
val skipNative = providers.gradleProperty("nomad.skipNative").isPresent
val fetchLlama = tasks.register<FetchLlamaTask>("fetchLlamaCpp") {
    url.set("https://github.com/ggml-org/llama.cpp/archive/refs/tags/$llamaTag.tar.gz")
    sha256.set(llamaSha256)
    archive.set(File(gradle.gradleUserHomeDir, "caches/nomadpocket/llama.cpp-$llamaTag.tar.gz"))
    outputDir.set(layout.buildDirectory.dir("llama.cpp"))
}
val llamaSourceDir = llamaSourceOverride
    ?: layout.buildDirectory.dir("llama.cpp/llama.cpp-$llamaTag").get().asFile.absolutePath

android {
    namespace = "org.nomadpocket.llm"
    compileSdk {
        version = release(37)
    }
    // Pinned so every machine (and F-Droid) builds the same native code.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Phones are arm64, and so are emulators on Apple-silicon Macs. Add "x86_64"
            // here to test on an Intel emulator; it doubles the native build time.
            abiFilters += listOf("arm64-v8a")
        }
        if (!skipNative) externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DFETCHCONTENT_SOURCE_DIR_LLAMA_CPP=$llamaSourceDir",
                    "-DFETCHCONTENT_FULLY_DISCONNECTED=ON",
                    "-DANDROID_STL=c++_static",
                )
            }
        }
    }

    buildTypes {
        // llama.cpp is unusably slow unoptimised, so debug builds get release native code too.
        debug {
            if (!skipNative) externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
        }
    }

    if (!skipNative) externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// CMake reads the unpacked source, so fetch it before any native configure step.
if (llamaSourceOverride == null && !skipNative) {
    tasks.configureEach {
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchLlama)
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
