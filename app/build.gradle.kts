plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Folder build khusus di PC Windows pemilik (di luar OneDrive agar tidak lambat/terkunci).
// Di Linux/CI (GitHub Actions) memakai folder build bawaan.
if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
    layout.buildDirectory.set(file("C:/Users/Atta/.gradle-builds/anydrop/app"))
}

android {
    namespace = "com.downloader.allinone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.downloader.allinone"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // yt-dlp membawa Python & ffmpeg native: batasi ke ABI HP Android modern agar APK tidak membengkak.
        // Tambahkan "x86_64" bila perlu menguji di emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Wajib untuk youtubedl-android: library native harus diekstrak ke disk (extractNativeLibs=true)
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
    implementation("org.mozilla:rhino:1.7.14")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("org.chromium.net:cronet-embedded:119.6045.31")

    // yt-dlp untuk Android (1000+ situs, bisa di-update saat runtime) + ffmpeg untuk merge/konversi
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // Menyuntikkan script sniffer sebelum script halaman berjalan (document-start)
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
