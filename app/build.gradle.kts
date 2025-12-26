plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.google.devtools.ksp)
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

val supabaseUrl: String = (project.findProperty("NEXT_PUBLIC_SUPABASE_URL") as String?)
    ?: System.getenv("NEXT_PUBLIC_SUPABASE_URL")
    ?: "https://supa.risc-v.tw"

val supabaseAnonKey: String = (project.findProperty("NEXT_PUBLIC_SUPABASE_ANON_KEY") as String?)
    ?: System.getenv("NEXT_PUBLIC_SUPABASE_ANON_KEY")
    ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzY2MDczNjAwLCJleHAiOjE5MjM4NDAwMDB9.JFC5hdPzUBYTxiEIYv4wBgQdxxtgL941HOB6YAa32Is"

android {
    namespace = "my.hinoki.booxreader"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

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
        versionCode = 99
        versionName = "1.1.98"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        androidResources {
            localeFilters += listOf("en", "zh", "zh-rTW")
        }
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildTypes {
        debug {
            // Use release signing config for debug to match release signing keys if needed
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        jvmToolchain(21)
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

    // --- Room ---
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    // --- Lifecycle ---
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // --- OkHttp + Gson ---
    implementation(libs.squareup.okhttp)
    implementation(libs.google.gson)

    // --- UI / Material ---
    implementation(libs.google.material)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.inline.parser)

    // --- Chinese Conversion ---
    implementation("com.github.houbb:opencc4j:1.8.1")

    // --- Auth & Security ---
    implementation(libs.androidx.security.crypto)
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
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

}
