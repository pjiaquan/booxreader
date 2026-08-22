import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.google.devtools.ksp)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // iOS targets (compiled on macOS only; declared here for the iOS app build)
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // Room API 以 api 暴露，供 :app 的 repos 直接使用（RoomDatabase、withTransaction 等）
            api(libs.androidx.room.runtime)
            api(libs.androidx.room.ktx)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.sqlite.framework)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

dependencies {
    // Room KMP: KSP processes commonMain metadata + Android target
    add("kspCommonMainMetadata", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
}

android {
    namespace = "my.hinoki.booxreader.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
