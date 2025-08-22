plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.diego.camperlevel.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.diego.camperlevel"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
        }
    }

    // ✅ Java/Kotlin 17
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // ❌ RIMOSSO: useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Con Kotlin 2.0 + plugin compose non serve impostare compilerExtensionVersion
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 🔷 Version Catalog (coerente con il tuo progetto)
    implementation(libs.play.services.wearable)

    // implementation(libs.androidx.material3)


    // Compose base (BOM + UI)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)

    // Wear Compose
    implementation(libs.androidx.compose.material)      // Material (puoi tenerla)
    implementation(libs.androidx.compose.foundation)    // Foundation comune
    implementation(libs.androidx.wear.tooling.preview)  // Anteprime Wear

    // Activity/Compat
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    // Test/Debug
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
