import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Numer wersji z numeru przebiegu CI (GitHub Actions); lokalnie 0 = wersja deweloperska bez aktualizacji.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
val appVersion = "0.1.$buildNumber"

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    jvmToolchain(17)

    sourceSets {
        val desktopMain by getting

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
        }
    }
}

android {
    namespace = "eu.smartsteer.navigator"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "eu.smartsteer.navigator"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = buildNumber.coerceAtLeast(1)
        versionName = appVersion
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "eu.smartsteer.navigator.MainKt"
        jvmArgs += listOf("-Dnavigator.version=$appVersion")

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "SmartSteer Navigator"
            packageVersion = appVersion
            vendor = "SmartSteer"
            description = "Prowadzenie i autosterowanie zgodne z płytkami AgOpenGPS"
            windows {
                menuGroup = "SmartSteer"
                shortcut = true
                dirChooser = true
                upgradeUuid = "8f0c6a9e-2b1d-4c7e-9a3f-5d2e7b1c4a60"
            }
        }
    }
}
