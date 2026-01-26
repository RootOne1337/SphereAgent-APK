/**
 * SphereAgent - Enterprise Android Agent
 * Build configuration with all optimizations
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sphere.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sphere.agent"
        minSdk = 24
        targetSdk = 35
        versionCode = 78
        versionName = "2.28.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Build config fields для конфигурации
        // PRIMARY: Remote Config с GitHub (всегда доступен)
        buildConfigField("String", "REMOTE_CONFIG_URL", "\"https://raw.githubusercontent.com/RootOne1337/sphere-config/main/agent-config.json\"")
        buildConfigField("String", "CHANGELOG_URL", "\"https://raw.githubusercontent.com/RootOne1337/sphere-config/main/changelog.json\"")
        
        // FALLBACK: Прямой URL прод-сервера (сайт + API на одном домене)
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://adb.leetpc.com\"")
        buildConfigField("String", "DEFAULT_WS_URL", "\"wss://adb.leetpc.com/api/v1/agent/ws\"")
        
        // Настройки по умолчанию
        buildConfigField("int", "DEFAULT_STREAM_QUALITY", "70")
        buildConfigField("int", "DEFAULT_STREAM_FPS", "10")
        buildConfigField("int", "RECONNECT_DELAY_MS", "5000")
        buildConfigField("int", "MAX_RECONNECT_DELAY_MS", "60000")
        buildConfigField("int", "HEARTBEAT_INTERVAL_MS", "30000")
        buildConfigField("int", "CONNECTION_TIMEOUT_MS", "30000")
        buildConfigField("int", "UPDATE_CHECK_INTERVAL_HOURS", "1")
        buildConfigField("boolean", "AUTO_UPDATE_ENABLED", "true")
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Подпись релизной сборки
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose UI
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    
    // Navigation
    implementation(libs.androidx.navigation.compose)
    
    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    
    // DataStore для локального хранения
    implementation(libs.androidx.datastore.preferences)
    
    // OkHttp для WebSocket
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    
    // Ktor Client (альтернатива)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    
    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)
    
    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    
    // WorkManager для фоновых задач
    implementation(libs.androidx.work.runtime.ktx)
    
    // Coil для изображений
    implementation(libs.coil.compose)
    
    // Accompanist
    implementation(libs.accompanist.permissions)
    implementation(libs.accompanist.systemuicontroller)
    
    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    
    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
}
