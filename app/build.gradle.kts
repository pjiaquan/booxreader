plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
    alias(libs.plugins.google.services)
}

import java.io.File
import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val storeFileFromEnv: File? = run {
    val encoded = System.getenv("STORE_FILE") ?: return@run null
    val keystoreDir = rootProject.layout.buildDirectory.dir("keystore").get().asFile
    keystoreDir.mkdirs()

    try {
        val decoded = Base64.getDecoder().decode(encoded)
        val outFile = File(keystoreDir, "release.keystore")
        outFile.writeBytes(decoded)
        outFile
    } catch (_: IllegalArgumentException) {
        // If the env var is not Base64, treat it as a path.
        val pathFile = rootProject.file(encoded)
        if (pathFile.exists()) pathFile else null
    }
}

android {
    namespace = "my.hinoki.booxreader"
    compileSdk = 35

    val releaseSigningConfig = signingConfigs.create("release") {
        keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties["keyAlias"] as String?
        keyPassword = System.getenv("KEY_ALIAS_PASSWORD") ?: keystoreProperties["keyPassword"] as String?

        val storeFileName = keystoreProperties["storeFile"] as String?

        storeFile = storeFileFromEnv ?: storeFileName?.let { file(it) }

        storePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProperties["storePassword"] as String?
    }

    val hasReleaseKeystore = releaseSigningConfig.storeFile != null
    if (!hasReleaseKeystore) {
        logger.lifecycle("Release keystore not configured; release builds will fail until a keystore is provided.")
    }

    val isReleaseTaskRequested = gradle.startParameter.taskNames.any { task ->
        task.contains("Release", ignoreCase = true)
    }
    if (isReleaseTaskRequested && !hasReleaseKeystore) {
        throw GradleException(
            "Release signing is not configured. " +
                "Configure keystore.properties or STORE_FILE/KEY* environment variables so existing users can upgrade."
        )
    }

    defaultConfig {
        applicationId = "my.hinoki.booxreader"
        minSdk = 24
        targetSdk = 35
        versionCode = 62
        versionName = "1.1.61"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // Use release signing config for debug to match Firebase SHA-1
            // signingConfig = signingConfigs.getByName("release")
        }
        release {
            signingConfig = releaseSigningConfig
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }

    lint {
        abortOnError = false
        disable += "FlowOperatorInvokedInComposition"
        disable += "StateFlowValueCalledInComposition"
        disable += "CoroutineCreationDuringComposition" // Workaround for lint crash with Kotlin 2.1.0 metadata
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

configurations.all {
    exclude(group = "com.intellij", module = "annotations")
}

dependencies {
    // --- Readium Kotlin Toolkit ---
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    // --- Room ---
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // --- Lifecycle ---
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // --- OkHttp + Gson ---
    implementation(libs.squareup.okhttp)
    implementation(libs.google.gson)

    // --- UI / Material ---
    implementation(libs.google.material)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)

    // --- Auth & Security ---
    implementation(libs.androidx.security.crypto)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.play.services.auth)
    
    // --- JDK Desugaring ---
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // --- AndroidX Core & Compose dependencies ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.recyclerview)

    // --- Testing ---
    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
