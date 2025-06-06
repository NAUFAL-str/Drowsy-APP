    plugins {
        alias(libs.plugins.android.application)
        alias(libs.plugins.kotlin.android)
        alias(libs.plugins.kotlin.compose)
    }

    android {
        namespace = "com.example.myapplication"
        compileSdk = 35

        defaultConfig {
            applicationId = "com.example.myapplication"
            minSdk = 26
            targetSdk = 35
            versionCode = 1
            versionName = "1.0"

            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

            // 🚨 CRITICAL
    //        ndk {
    //            abiFilters.clear()
    //            abiFilters += "arm64-v8a"; "x86_64"
    //        }
        }

        buildTypes {
            debug {
                isMinifyEnabled = false
                isShrinkResources = false
                isDebuggable = true
            }
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            }
        }

        // 🚨 CRITICAL: Split APKs by architecture
        splits {
            abi {
                isEnable = true
                reset()
                include("arm64-v8a", "x86_64", "x86") // Only build for 64-bit ARM
                isUniversalApk = false // Don't create universal APK
            }
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }

        kotlinOptions {
            jvmTarget = "11"
        }

        buildFeatures {
            compose = true
        }

        bundle {
            language {
                enableSplit = false
            }
        }

        // 🚨 CRITICAL: Exclude unnecessary files
        packagingOptions {
            resources {
                excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}",
                    "/META-INF/versions/**",
                    "**/kotlin/**",
                    "**/*.txt",
                    "**/*.version",
                    "**/MANIFEST.MF",
                    "**/*.properties",
                    "**/LICENSE*",
                    "**/NOTICE*",
                    "**/*.md"
                )
            }
            // Exclude duplicate native libraries
            pickFirst("**/libc++_shared.so")
            pickFirst("**/libjsc.so")
            pickFirst("**/libfbjni.so")
        }
    }

    dependencies {
        // 🚨 MINIMAL DEPENDENCIES ONLY
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.runtime.ktx)
        implementation(libs.androidx.activity.compose)
        implementation(platform(libs.androidx.compose.bom))
        implementation(libs.androidx.ui)
        implementation(libs.androidx.ui.graphics)
        implementation(libs.androidx.ui.tooling.preview)
        implementation(libs.androidx.material3)

        // Camera
        implementation("androidx.camera:camera-core:1.3.1")
        implementation("androidx.camera:camera-camera2:1.3.1")
        implementation("androidx.camera:camera-lifecycle:1.3.1")
        implementation("androidx.camera:camera-view:1.3.1")

        // MediaPipe - SINGLE VERSION ONLY
        implementation("com.google.mediapipe:tasks-vision:0.10.8") // Use specific version, not latest.release

        // TensorFlow Lite - MINIMAL
        implementation("com.google.ai.edge.litert:litert:1.0.1")
        implementation("org.tensorflow:tensorflow-lite-gpu:2.9.0")
        implementation("org.tensorflow:tensorflow-lite-select-tf-ops:2.15.0")

        // Essential only
        implementation("com.google.accompanist:accompanist-permissions:0.32.0")
        implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
        implementation(libs.litert)

        // 🚨 REMOVE ALL TEST DEPENDENCIES FOR NOW
        // testImplementation(libs.junit)
        // androidTestImplementation(libs.androidx.junit)
        // androidTestImplementation(libs.androidx.espresso.core)
        // debugImplementation(libs.androidx.ui.tooling)
    }