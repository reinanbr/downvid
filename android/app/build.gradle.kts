import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: android/key.properties (local) or environment variables
// (CI). Without them, release builds fall back to the debug key.
val keyProps = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signing(prop: String, env: String): String? = keyProps.getProperty(prop) ?: System.getenv(env)
val releaseStore = signing("storeFile", "DV_KEYSTORE_PATH")

val dvVersionName = System.getenv("DV_VERSION") ?: providers.gradleProperty("dv.versionName").get()
val dvBuildNumber = (System.getenv("DV_BUILD_NUMBER") ?: providers.gradleProperty("dv.buildNumber").get()).toInt()

// ABIs: DV_ABI=arm64-v8a (comma-separated) limits what is packaged; yt-dlp,
// Python and ffmpeg weigh ~60 MB per ABI.
val allAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val wantedAbis = System.getenv("DV_ABI")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: allAbis
// Same version codes as the Flutter builds (abi * 1000 + build), so release
// APKs keep updating in place.
val abiCode = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 4)

android {
    namespace = "com.downvid.downvid"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.downvid.downvid"
        minSdk = 24
        targetSdk = 36
        versionCode = dvBuildNumber
        versionName = dvVersionName
        // Without splits, only the wanted ABIs are packaged: the yt-dlp
        // libraries also ship x86 (32-bit) binaries, ~50 MB of them.
        if (System.getenv("DV_SPLIT") != "1") {
            ndk { abiFilters += wantedAbis }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // youtubedl-android runs python/ffmpeg executables from nativeLibraryDir,
        // so native libs must be extracted at install time.
        jniLibs.useLegacyPackaging = true
        // Zip payloads named *.so (python/ffmpeg) are not ELF: don't strip.
        jniLibs.keepDebugSymbols += "**/*.zip.so"
        for (abi in allAbis - wantedAbis.toSet()) jniLibs.excludes += "lib/$abi/**"
        resources.excludes += listOf("META-INF/*.version", "META-INF/**/LICENSE*", "DebugProbesKt.bin", "kotlin/**.kotlin_builtins")
    }

    splits {
        abi {
            // One APK per ABI for releases (DV_SPLIT=1); one APK otherwise.
            isEnable = System.getenv("DV_SPLIT") == "1"
            reset()
            include(*wantedAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = signing("storePassword", "DV_KEYSTORE_PASSWORD")
                keyAlias = signing("keyAlias", "DV_KEY_ALIAS")
                keyPassword = signing("keyPassword", "DV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code (R8, baseline profiles) signed with the debug key: it
        // installs over a debug build, keeping the data, to measure start-up
        // and memory as users get them (./gradlew :app:installBenchmark).
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isProfileable = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // The Go core is built outside Gradle; lint errors must not block
        // the APK on CI, they are reported instead.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        for (output in variant.outputs) {
            val abi = output.filters.find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }?.identifier
            val code = abiCode[abi]
            if (code != null) output.versionCode.set(code * 1000 + dvBuildNumber)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        freeCompilerArgs.addAll("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Compiles the app's hot paths ahead of time from the baseline profile.
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")

    // Browser: document-start scripts, web message listeners, profiles.
    implementation("androidx.webkit:webkit:1.15.0")

    // Player (HLS, DASH, progressive) and background playback.
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.2")
    implementation("androidx.media3:media3-exoplayer-dash:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.media3:media3-session:1.9.2")

    // yt-dlp (+ Python) and ffmpeg packaged for Android; used for platform
    // extraction (-J) and for muxing. Downloads stay in the Go core.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    testImplementation("junit:junit:4.13.2")
}

// Builds libdvcore.so for the packaged ABIs (scripts/build_go.sh).
// Incremental: re-runs only when Go sources or the script change.
val goCoreDir = rootProject.file("../go")
val buildGoCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Go core (libdvcore.so) for the Android ABIs"
    inputs.dir(goCoreDir)
    inputs.file(rootProject.file("../scripts/build_go.sh"))
    inputs.property("abis", wantedAbis)
    outputs.files(wantedAbis.map { file("src/main/jniLibs/$it/libdvcore.so") })
    commandLine(listOf("bash", rootProject.file("../scripts/build_go.sh").absolutePath) + wantedAbis)
}
// Builds libdvadblock.so, the ad blocker (Brave's adblock-rust), the same way
// (scripts/build_rust.sh). Its filter lists are a prebuilt asset
// (scripts/build_adblock_lists.py).
val adblockDir = rootProject.file("../adblock")
val buildAdblock by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the ad blocker (libdvadblock.so) for the Android ABIs"
    inputs.dir(adblockDir.resolve("src"))
    inputs.files(adblockDir.resolve("Cargo.toml"), adblockDir.resolve("Cargo.lock"), rootProject.file("../scripts/build_rust.sh"))
    inputs.property("abis", wantedAbis)
    outputs.files(wantedAbis.map { file("src/main/jniLibs/$it/libdvadblock.so") })
    commandLine(listOf("bash", rootProject.file("../scripts/build_rust.sh").absolutePath) + wantedAbis)
}
tasks.named("preBuild") { dependsOn(buildGoCore, buildAdblock) }
