plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Reuse what is already installed on this machine (see docs/phase0-environment.md).
val ndkVer = "27.2.12479018"
val cmakeVer = "3.22.1"

android {
    namespace = "dev.rq.llmceiling"
    compileSdk = 36
    ndkVersion = ndkVer

    defaultConfig {
        applicationId = "dev.rq.llmceiling"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a") }   // ARM64 CPU only
        externalNativeBuild {
            cmake {
                // Same llama.cpp options as upstream examples/llama.android, CPU only.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_OPENCL=OFF"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = cmakeVer
        }
    }

    buildTypes {
        // Native code is always built Release (see CMAKE_BUILD_TYPE above); debug only affects Java/Kotlin
        // and makes `run-as` available for pulling results.
        debug { isMinifyEnabled = false; isJniDebuggable = false }
    }

    packaging { jniLibs { useLegacyPackaging = true } } // CPU backend variants are dlopen()ed from nativeLibraryDir

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // real org.json on the JVM (Android's is a stub in unit tests)
}
