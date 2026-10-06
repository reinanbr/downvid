import java.util.Properties

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Release signing: android/key.properties (local) or environment variables
// (CI). Without them, release builds fall back to the debug key so that
// `flutter run --release` keeps working.
val keyProps = Properties().apply {
    rootProject.file("key.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signing(prop: String, env: String): String? = keyProps.getProperty(prop) ?: System.getenv(env)
val releaseStore = signing("storeFile", "DV_KEYSTORE_PATH")

android {
    namespace = "com.downvid.downvid"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.downvid.downvid"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

    }

    packaging {
        // youtubedl-android runs python/ffmpeg executables from nativeLibraryDir,
        // so native libs must be extracted at install time.
        jniLibs.useLegacyPackaging = true
        // Zip payloads named *.so (python/ffmpeg) are not ELF: don't strip.
        jniLibs.keepDebugSymbols += "**/*.zip.so"

        // Package only the requested ABIs (yt-dlp/python/ffmpeg are ~60 MB per
        // ABI): `--target-platform` (release builds) or DV_ABI, e.g.
        // DV_ABI=arm64-v8a. Done here because the Flutter plugin resets
        // ndk.abiFilters, and ignores --target-platform in debug builds.
        val abiOf = mapOf(
            "android-arm" to "armeabi-v7a", "android-arm64" to "arm64-v8a",
            "android-x64" to "x86_64", "android-x86" to "x86",
        )
        val wanted = System.getenv("DV_ABI")?.split(",")?.map { it.trim() }
            ?: (project.findProperty("target-platform") as String?)?.split(",")?.mapNotNull { abiOf[it.trim()] }
        if (!wanted.isNullOrEmpty()) {
            for (abi in abiOf.values - wanted.toSet()) jniLibs.excludes += "lib/$abi/**"
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
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    // yt-dlp (+ Python) and ffmpeg packaged for Android; used for platform
    // extraction (-J) and for merging streams. Downloads stay in the Go core.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    // Document-start scripts (Threads: keeps the post data the page fetches).
    implementation("androidx.webkit:webkit:1.12.1")
}

// Builds libdvcore.so for every ABI (scripts/build_go.sh). Incremental:
// re-runs only when Go sources or the script change.
val goCoreDir = rootProject.file("../go")
val buildGoCore by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Go core (libdvcore.so) for all Android ABIs"
    inputs.dir(goCoreDir)
    inputs.file(rootProject.file("../scripts/build_go.sh"))
    outputs.files(
        listOf("arm64-v8a", "armeabi-v7a", "x86_64").map {
            file("src/main/jniLibs/$it/libdvcore.so")
        }
    )
    commandLine("bash", rootProject.file("../scripts/build_go.sh").absolutePath)
}
tasks.named("preBuild") { dependsOn(buildGoCore) }
